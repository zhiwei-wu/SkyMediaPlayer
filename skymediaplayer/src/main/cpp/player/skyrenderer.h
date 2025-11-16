#ifndef MY_PLAYER_SKYRENDERER_H
#define MY_PLAYER_SKYRENDERER_H

#include <array>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include "libavutil/frame.h"
#include "logger.h"
#include "libavutil/pixdesc.h"

using Matrix4x4Std = std::array<float, 16>;

constexpr int SKY_GLES2_MAX_PLANE = 3; // 根据实际需求定义
constexpr int INVALID_PROGRAM = 0;

#define GLES_STRINGIZE(x)   #x
#define GLES_STRINGIZE2(x)  GLES_STRINGIZE(x)
#define GLES_STRING(x)      GLES_STRINGIZE2(x)

void skyElg2CheckError(const char* op);

constexpr static const char YUV_VERTEX_SHADER_DEFAULT[] = GLES_STRING(
        precision highp float;
        varying   highp vec2 vv2_Texcoord;
        attribute highp vec4 av4_Position;
        attribute highp vec2 av2_Texcoord;
        uniform         mat4 um4_ModelViewProjection;

        void main()
        {
            gl_Position  = um4_ModelViewProjection * av4_Position;
            vv2_Texcoord = av2_Texcoord.xy;
        }
);

class SkyEGL2RendererImp {
public:
    SkyEGL2RendererImp(AVPixelFormat format) {
        avPixFormat = format;
    }
    // 虚析构函数确保正确释放资源
    virtual ~SkyEGL2RendererImp() = default;
    // 接口方法
    virtual void init();
    virtual GLboolean isValid() = 0;
    virtual GLboolean use() = 0;
    virtual GLsizei getBufferWidth(AVFrame* avFrame) = 0;
    virtual GLboolean uploadTexture(AVFrame* avFrame) = 0;
    virtual const char* getFragmentShaderSource() = 0;
    virtual void reset() = 0;

    void resetTextureCoordinatesToCover();
    void buildAndEnableTextureCoordinatesAttributes();
    void resetVerticesToNDC();
    void buildAndEnableVerticesAttributes();
    EGLBoolean renderImage(AVFrame *avFrame);

    static GLuint compileShader(GLenum type, const char* source);
    static void printShaderInfo(GLuint shader);
    static void printProgramInfo(GLuint program);
    static void buildOrthoMatrix(Matrix4x4Std &matrix, GLfloat left, GLfloat right,
                                 GLfloat bottom, GLfloat top, GLfloat near, GLfloat far);

public:
    AVPixelFormat avPixFormat = AV_PIX_FMT_NONE;

protected:
    // OpenGL 资源使用现代 C++ 类型（保持与 OpenGL API 兼容）
    GLuint program = INVALID_PROGRAM;
    GLuint vertexShader = 0;
    GLuint fragmentShader = 0;
    std::array<GLuint, SKY_GLES2_MAX_PLANE> plane_textures = {0};

    GLuint av4_position = 0;
    GLuint av2_texcoord = 0;
    GLuint um4_mvp = 0;
    std::array<GLuint, SKY_GLES2_MAX_PLANE> us2_sampler = {0};
    GLuint um3_color_conversion = 0;

    GLsizei buffer_width = 0;
    GLsizei visible_width = 0;
    std::array<GLfloat, 8> texcoords{};
    std::array<GLfloat, 8> vertices{};
    bool verticesChanged = 0;

    // 上一帧的宽高数据
    GLsizei frameWidth;
    GLsizei frameHeight;
    int     frameSarNum;
    int     frameSarDen;
    GLsizei lastBufferWidth;
};

class SkyRenderer {
public:
    virtual ~SkyRenderer(){}
    virtual bool displayImage(EGLNativeWindowType window, AVFrame *frame) = 0;
    virtual bool isValid() = 0;
    virtual void terminate() = 0;
};

class SkyEGL2Renderer : public SkyRenderer {
public:
    ~SkyEGL2Renderer();

    bool displayImage(EGLNativeWindowType window, AVFrame *frame) override;
    bool isValid() override;
    void terminate() override;

private:
    EGLBoolean setup();
    EGLBoolean makeCurrent(EGLNativeWindowType window);
    EGLBoolean prepareRenderer(AVFrame *avFrame);
    EGLBoolean setSurfaceSize(int frameWidth, int frameHeight);

    int querySurfaceSurfaceWidth();
    int querySurfaceSurfaceHeight();

private:
    std::unique_ptr<SkyEGL2RendererImp> rendererImp_;

    EGLNativeWindowType window_;
    EGLDisplay display_;
    EGLSurface surface_;
    EGLContext context_;

    // surface 宽高
    EGLint surfaceWidth_;
    EGLint surfaceHeight_;
};

std::unique_ptr<SkyEGL2RendererImp> createRenderImpFactory(AVPixelFormat format);

#endif //MY_PLAYER_SKYRENDERER_H