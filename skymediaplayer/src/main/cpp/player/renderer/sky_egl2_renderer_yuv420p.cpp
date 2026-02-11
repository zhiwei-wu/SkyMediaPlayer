#include "sky_egl2_renderer_yuv420p.h"
#include <cassert>

static const char* TAG = "SkyEGL2RendererYUV420pImp";

SkyEGL2RendererYUV420pImp::SkyEGL2RendererYUV420pImp(AVPixelFormat format) : SkyEGL2RendererImp(format) {
}

const char* SkyEGL2RendererYUV420pImp::getFragmentShaderSource() {
    return YUV420P_FRAGMENT_SHADER;
}

void SkyEGL2RendererYUV420pImp::init() {
    SkyEGL2RendererImp::init();
    if (!isValid()) {
        return;
    }

    us2_sampler[0] = glGetUniformLocation(program, "us2_SamplerX");     skyElg2CheckError("glGetUniformLocation(us2_SamplerX)");
    us2_sampler[1] = glGetUniformLocation(program, "us2_SamplerY");     skyElg2CheckError("glGetUniformLocation(us2_SamplerY)");
    us2_sampler[2] = glGetUniformLocation(program, "us2_SamplerZ");     skyElg2CheckError("glGetUniformLocation(us2_SamplerZ)");

    um3_color_conversion = glGetUniformLocation(program, "um3_ColorConversion");        skyElg2CheckError("glGetUniformLocation(um3_ColorConversion)");
}

GLboolean SkyEGL2RendererYUV420pImp::use() {
    FUNC_TRACE()

    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

    glUseProgram(program);      skyElg2CheckError("glUseProgram");

    if (0 == plane_textures[0]) {
        glGenTextures(static_cast<GLsizei>(plane_textures.size()), plane_textures.data());
    }

    for (size_t i = 0; i < plane_textures.size(); ++i) {
        glActiveTexture(GL_TEXTURE0 + static_cast<GLenum>(i));
        glBindTexture(GL_TEXTURE_2D, plane_textures[i]);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glUniform1i(us2_sampler[i], static_cast<GLint>(i));
    }
    skyElg2CheckError("use");

    // YUV 到 RGB 颜色转换矩阵 (BT.601 标准)
    static const GLfloat colorConversion[] = {
        1.164f,  1.164f, 1.164f,
        0.0f,   -0.392f, 2.017f,
        1.596f, -0.813f, 0.0f,
    };
    glUniformMatrix3fv(um3_color_conversion, 1, GL_FALSE, colorConversion);

    // 正交投影
    Matrix4x4Std modelViewProj;
    SkyEGL2RendererImp::buildOrthoMatrix(modelViewProj, -1.0f, 1.0f, -1.0f, 1.0f, -1.0f, 1.0f);
    glUniformMatrix4fv(um4_mvp, 1, GL_FALSE, modelViewProj.data());

    // 默认纹理坐标
    resetTextureCoordinatesToCover();
    buildAndEnableTextureCoordinatesAttributes();

    // NDC顶点坐标
    resetVerticesToNDC();
    buildAndEnableVerticesAttributes();

    return GL_TRUE;
}

GLboolean SkyEGL2RendererYUV420pImp::isValid() {
    return program > 0;
}

GLsizei SkyEGL2RendererYUV420pImp::getBufferWidth(AVFrame *avFrame) {
    assert(avFrame != nullptr);
    return avFrame->linesize[0];
}

GLboolean SkyEGL2RendererYUV420pImp::uploadTexture(AVFrame *avFrame) {
    if (!isValid() || !avFrame) {
        ALOG_E(TAG, "uploadTexture() invalid()");
        return GL_FALSE;
    }

    const std::array<GLsizei, 3> widths = {avFrame->linesize[0], avFrame->linesize[1], avFrame->linesize[2]};
    const std::array<GLsizei, 3> heights = {avFrame->height, avFrame->height / 2, avFrame->height / 2};
    const std::array<GLubyte*, 3> pixels = {avFrame->data[0], avFrame->data[1], avFrame->data[2]};

    for (size_t i = 0; i < 3; ++i) {
        glBindTexture(GL_TEXTURE_2D, plane_textures[i]);

        glTexImage2D(GL_TEXTURE_2D,
                     0,
                     GL_LUMINANCE,
                     widths[i],
                     heights[i],
                     0,
                     GL_LUMINANCE,
                     GL_UNSIGNED_BYTE,
                     pixels[i]);
    }

    return GL_TRUE;
}

void SkyEGL2RendererYUV420pImp::reset() {
    if (vertexShader) {
        glDeleteShader(vertexShader);
    }
    if (fragmentShader) {
        glDeleteShader(fragmentShader);
    }
    if (program) {
        glDeleteProgram(program);
    }
    vertexShader = 0;
    fragmentShader = 0;
    program = 0;
    for (GLuint& tex : plane_textures) {
        if (tex != 0) {
            glDeleteTextures(1, &tex);
            tex = 0;  // 重置为0，避免重复删除
        }
    }
    plane_textures.fill(0);
    avPixFormat = AV_PIX_FMT_NONE;
}