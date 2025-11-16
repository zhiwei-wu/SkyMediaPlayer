#include "sky_egl2_renderer_rgba.h"

static const char* TAG = "SkyEGL2RendererRGBAImp";

SkyEGL2RendererRGBAImp::SkyEGL2RendererRGBAImp(AVPixelFormat format) : SkyEGL2RendererImp(format) {
}

const char* SkyEGL2RendererRGBAImp::getFragmentShaderSource() {
    return RGBA_FRAGMENT_SHADER;
}

void SkyEGL2RendererRGBAImp::init() {
    SkyEGL2RendererImp::init();
    if (!isValid()) {
        return;
    }

    us2_sampler_rgba = glGetUniformLocation(program, "us2_SamplerRGBA");    skyElg2CheckError("glGetUniformLocation(us2_SamplerRGBA)");
}

GLboolean SkyEGL2RendererRGBAImp::use() {
    FUNC_TRACE()

    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);  // RGBA uses 4-byte alignment

    glUseProgram(program);      skyElg2CheckError("glUseProgram");

    if (0 == rgba_texture) {
        glGenTextures(1, &rgba_texture);
    }

    // RGBA texture (texture unit 0)
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, rgba_texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glUniform1i(us2_sampler_rgba, 0);

    skyElg2CheckError("use");

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

GLboolean SkyEGL2RendererRGBAImp::isValid() {
    return program > 0;
}

GLsizei SkyEGL2RendererRGBAImp::getBufferWidth(AVFrame *avFrame) {
    return avFrame->linesize[0];
}

GLboolean SkyEGL2RendererRGBAImp::uploadTexture(AVFrame *avFrame) {
    if (!isValid() || !avFrame) {
        ALOG_E(TAG, "uploadTexture() invalid()");
        return GL_FALSE;
    }

    // RGBA: Single plane with 4 components per pixel
    glBindTexture(GL_TEXTURE_2D, rgba_texture);
    
    GLenum format = GL_RGBA;
    GLenum internalFormat = GL_RGBA;
    
    // Handle different RGB formats
    switch (avPixFormat) {
        case AV_PIX_FMT_RGB24:
            format = GL_RGB;
            internalFormat = GL_RGB;
            break;
        case AV_PIX_FMT_BGR24:
            format = GL_RGB;  // Will need pixel data reordering
            internalFormat = GL_RGB;
            break;
        case AV_PIX_FMT_RGBA:
        case AV_PIX_FMT_BGRA:
        case AV_PIX_FMT_ARGB:
        case AV_PIX_FMT_ABGR:
        default:
            format = GL_RGBA;
            internalFormat = GL_RGBA;
            break;
    }
    
    glTexImage2D(GL_TEXTURE_2D,
                 0,
                 internalFormat,
                 avFrame->width,
                 avFrame->height,
                 0,
                 format,
                 GL_UNSIGNED_BYTE,
                 avFrame->data[0]);

    return GL_TRUE;
}

void SkyEGL2RendererRGBAImp::reset() {
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
    if (rgba_texture != 0) {
        glDeleteTextures(1, &rgba_texture);
        rgba_texture = 0;
    }
    avPixFormat = AV_PIX_FMT_NONE;
}