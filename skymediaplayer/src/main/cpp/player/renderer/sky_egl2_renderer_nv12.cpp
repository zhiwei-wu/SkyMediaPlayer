#include "sky_egl2_renderer_nv12.h"

static const char* TAG = "SkyEGL2RendererNV12Imp";

SkyEGL2RendererNV12Imp::SkyEGL2RendererNV12Imp(AVPixelFormat format) : SkyEGL2RendererImp(format) {
}

const char* SkyEGL2RendererNV12Imp::getFragmentShaderSource() {
    return NV12_FRAGMENT_SHADER;
}

void SkyEGL2RendererNV12Imp::init() {
    SkyEGL2RendererImp::init();
    if (!isValid()) {
        return;
    }

    us2_sampler_y = glGetUniformLocation(program, "us2_SamplerY");      skyElg2CheckError("glGetUniformLocation(us2_SamplerY)");
    us2_sampler_uv = glGetUniformLocation(program, "us2_SamplerUV");    skyElg2CheckError("glGetUniformLocation(us2_SamplerUV)");

    um3_color_conversion = glGetUniformLocation(program, "um3_ColorConversion");        skyElg2CheckError("glGetUniformLocation(um3_ColorConversion)");
}

GLboolean SkyEGL2RendererNV12Imp::use() {
    FUNC_TRACE()

    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

    glUseProgram(program);      skyElg2CheckError("glUseProgram");

    if (0 == nv12_textures[0]) {
        glGenTextures(2, nv12_textures);
    }

    // Y texture (texture unit 0)
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, nv12_textures[0]);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glUniform1i(us2_sampler_y, 0);

    // UV texture (texture unit 1)
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, nv12_textures[1]);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glUniform1i(us2_sampler_uv, 1);

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

GLboolean SkyEGL2RendererNV12Imp::isValid() {
    return program > 0;
}

GLsizei SkyEGL2RendererNV12Imp::getBufferWidth(AVFrame *avFrame) {
    return avFrame->width;  // 修正：与uploadTexture保持一致，使用实际像素宽度
}

GLboolean SkyEGL2RendererNV12Imp::uploadTexture(AVFrame *avFrame) {
    if (!isValid() || !avFrame) {
        ALOG_E(TAG, "uploadTexture() invalid()");
        return GL_FALSE;
    }

    // NV12: Y plane + interleaved UV plane
    // Y plane
    glBindTexture(GL_TEXTURE_2D, nv12_textures[0]);
    glTexImage2D(GL_TEXTURE_2D,
                 0,
                 GL_LUMINANCE,
                 avFrame->width,        // 修正：使用实际像素宽度而不是linesize
                 avFrame->height,
                 0,
                 GL_LUMINANCE,
                 GL_UNSIGNED_BYTE,
                 avFrame->data[0]);

    // UV plane (interleaved)
    glBindTexture(GL_TEXTURE_2D, nv12_textures[1]);
    glTexImage2D(GL_TEXTURE_2D,
                 0,
                 GL_LUMINANCE_ALPHA,  // NV12 UV plane is interleaved
                 avFrame->width / 2,  // 修正：NV12 UV平面宽度是Y平面的一半
                 avFrame->height / 2, // UV height is half
                 0,
                 GL_LUMINANCE_ALPHA,
                 GL_UNSIGNED_BYTE,
                 avFrame->data[1]);

    return GL_TRUE;
}

void SkyEGL2RendererNV12Imp::reset() {
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
    for (int i = 0; i < 2; ++i) {
        if (nv12_textures[i] != 0) {
            glDeleteTextures(1, &nv12_textures[i]);
            nv12_textures[i] = 0;
        }
    }
    avPixFormat = AV_PIX_FMT_NONE;
}