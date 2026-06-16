#ifndef MY_PLAYER_SKYRENDERER_H
#define MY_PLAYER_SKYRENDERER_H

#include <array>
#include <vector>
#include <mutex>
#include <cstdint>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include "libavutil/frame.h"
#include "logger.h"
#include "libavutil/pixdesc.h"
#include "sky_renderer_types.h"

using Matrix4x4Std = std::array<float, 16>;

constexpr int SKY_GLES2_MAX_PLANE = 3; // 根据实际需求定义
constexpr int INVALID_PROGRAM = 0;

#define GLES_STRINGIZE(x)   #x
#define GLES_STRINGIZE2(x)  GLES_STRINGIZE(x)
#define GLES_STRING(x)      GLES_STRINGIZE2(x)

// 画质增强共享 GLSL 片段，依赖上面的 GLES_STRING，必须在其后包含
#include "sky_enhance_glsl.h"

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

    /**
     * 更新当前 impl 的 LUT 状态（在 GL 线程调用）
     * @param rgba 512x512 RGBA 数据；为 nullptr 表示不更新数据（仅切换开关/强度）
     * @param intensity 强度 0..1
     * @param enabled 是否启用
     */
    void updateLut(const uint8_t* rgba, float intensity, bool enabled);

    /**
     * 更新当前 impl 的画质增强强度（在 GL 线程调用）
     * @param sharpness  CAS 锐化强度 0..1
     * @param deband     去色带强度 0..1
     */
    void updateEnhance(float sharpness, float deband);

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

    // LUT（GPUImage 512x512 lookup）状态
    GLuint lut_texture_ = 0;
    GLint  us2_sampler_lut_ = -1;
    GLint  u_lut_enabled_ = -1;
    bool   lut_enabled_ = false;
    float  lut_intensity_ = 1.0f;
    std::vector<uint8_t> lut_pending_;   // 512*512*4，待上传
    bool   lut_pending_dirty_ = false;

    // 画质增强（CAS 锐化/去色带）状态，0=关闭
    GLint  u_sharpness_ = -1;
    GLint  u_deband_ = -1;
    GLint  u_texel_size_ = -1;
    float  enhance_sharpness_ = 0.0f;
    float  enhance_deband_ = 0.0f;

    // 在 renderImage() 内绑定 LUT 纹理并设置 LUT/增强 uniform（保存/恢复 active 纹理单元）
    void applyEffectsInRender(const AVFrame* avFrame);
    // 释放 LUT 纹理（在 reset() 中调用，需 GL 上下文 current）
    void releaseLutTexture();
};

class SkyRenderer {
public:
    virtual ~SkyRenderer() = default;
    virtual bool displayImage(EGLNativeWindowType window, AVFrame *frame) = 0;
    virtual bool isValid() = 0;
    virtual void terminate() = 0;

    /**
     * 设置 LUT 滤镜（512x512 GPUImage lookup，RGBA）。线程安全，可播放中调用。
     * @param rgba 512*512*4 字节
     * @param len 字节数（应为 512*512*4）
     * @param intensity 强度 0..1
     */
    virtual void setLut(const uint8_t* rgba, int len, float intensity) {}
    /** 关闭 LUT 滤镜 */
    virtual void clearLut() {}

    /**
     * 设置画质增强强度（各 0..1，0=关闭，全 0 等效关闭）。线程安全，可播放中调用。
     * @param sharpness  CAS 锐化
     * @param deband     去色带
     */
    virtual void setEnhance(float sharpness, float deband) {}

    /**
     * 工厂方法：根据渲染后端类型创建对应的渲染器实例
     * @param backend 渲染后端类型
     * @return 渲染器实例，Vulkan/Metal 暂未实现时回退到 OpenGL ES
     */
    static std::unique_ptr<SkyRenderer> create(RendererBackend backend);
};

class SkyEGL2Renderer : public SkyRenderer {
public:
    ~SkyEGL2Renderer();

    bool displayImage(EGLNativeWindowType window, AVFrame *frame) override;
    bool isValid() override;
    void terminate() override;

    void setLut(const uint8_t* rgba, int len, float intensity) override;
    void clearLut() override;
    void setEnhance(float sharpness, float deband) override;

private:
    EGLBoolean setup();
    EGLBoolean makeCurrent(EGLNativeWindowType window);
    EGLBoolean prepareRenderer(AVFrame *avFrame);
    EGLBoolean setSurfaceSize(int frameWidth, int frameHeight);

    int querySurfaceSurfaceWidth();
    int querySurfaceSurfaceHeight();

private:
    std::unique_ptr<SkyEGL2RendererImp> rendererImp_;

    // LUT 状态（跨 impl 重建存活），脏标记驱动，在 displayImage() 下发给当前 impl
    std::mutex lutMtx_;
    std::vector<uint8_t> lutData_;
    float lutIntensity_ = 1.0f;
    bool  lutEnabled_ = false;
    bool  lutDirty_ = false;
    bool  implRecreated_ = false;

    // 画质增强状态（与 LUT 同模式，复用 lutMtx_）
    float enhanceSharpness_ = 0.0f;
    float enhanceDeband_ = 0.0f;
    bool  enhanceDirty_ = false;

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