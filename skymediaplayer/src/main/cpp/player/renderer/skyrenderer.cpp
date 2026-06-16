#include <cassert>
#include "logger.h"
#include "skyrenderer.h"
#include "sky_vk_renderer.h"
#include "sky_egl2_renderer_yuv420p.h"
#include "sky_egl2_renderer_nv12.h"
#include "sky_egl2_renderer_nv21.h"
#include "sky_egl2_renderer_rgba.h"
#include "sky_egl2_renderer_yuv422p.h"

inline static const char *TAG = "SkyEGL2Renderer";

SkyEGL2Renderer::~SkyEGL2Renderer() noexcept {
    // 释放资源
}

bool SkyEGL2Renderer::displayImage(EGLNativeWindowType window, AVFrame *frame) {
    if (nullptr == window || nullptr == frame) {
        ALOG_E(TAG, "%s null ptr", __func__);
        return false;
    }

    if (!makeCurrent(window)) {
        ALOG_E(TAG, "%s makeCurrent fail", __func__);
        return false;
    }

    if (!prepareRenderer(frame)) {
        ALOG_E(TAG, "prepareRenderer() fail!");
        return false;
    }

    // 下发 LUT/增强状态到当前 impl（脏标记或 impl 刚重建时）
    {
        std::lock_guard<std::mutex> lk(lutMtx_);
        if (rendererImp_) {
            if (lutDirty_ || implRecreated_) {
                rendererImp_->updateLut(
                        (lutEnabled_ && !lutData_.empty()) ? lutData_.data() : nullptr,
                        lutIntensity_, lutEnabled_);
                lutDirty_ = false;
            }
            if (enhanceDirty_ || implRecreated_) {
                rendererImp_->updateEnhance(enhanceSharpness_, enhanceDeband_);
                enhanceDirty_ = false;
            }
            implRecreated_ = false;
        }
    }

    EGLBoolean ret = rendererImp_->renderImage(frame);
    if (!ret) {
        ALOG_E(TAG, "displayImage() renderImage fail");
        return false;
    }
    eglSwapBuffers(display_, surface_);
    eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglReleaseThread();

    return true;
}


void SkyEGL2Renderer::terminate() {
    if (!isValid()) {
        return;
    }
    FUNC_TRACE()

    eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroyContext(display_, context_);
    eglDestroySurface(display_, surface_);
    eglTerminate(display_);
    eglReleaseThread();

    context_ = EGL_NO_CONTEXT;
    surface_ = EGL_NO_SURFACE;
    display_ = EGL_NO_DISPLAY;

    if (nullptr != rendererImp_) {
        rendererImp_->reset();
    }
}

bool SkyEGL2Renderer::isValid() {
    return window_ && display_ && surface_ && context_;
}

void SkyEGL2Renderer::setLut(const uint8_t* rgba, int len, float intensity) {
    std::lock_guard<std::mutex> lk(lutMtx_);
    if (rgba == nullptr || len <= 0) {
        lutEnabled_ = false;
        lutDirty_ = true;
        return;
    }
    lutData_.assign(rgba, rgba + len);
    lutIntensity_ = intensity;
    lutEnabled_ = true;
    lutDirty_ = true;
}

void SkyEGL2Renderer::clearLut() {
    std::lock_guard<std::mutex> lk(lutMtx_);
    lutEnabled_ = false;
    lutDirty_ = true;
}

void SkyEGL2Renderer::setEnhance(float sharpness, float deband) {
    std::lock_guard<std::mutex> lk(lutMtx_);
    enhanceSharpness_ = sharpness;
    enhanceDeband_ = deband;
    enhanceDirty_ = true;
}

EGLBoolean SkyEGL2Renderer::makeCurrent(EGLNativeWindowType window) {
    if (window == window_ && isValid()) {
        if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
            ALOG_E(TAG, "%s error", __func__);
            return EGL_FALSE;
        }

        return EGL_TRUE;
    }

    // 如果需要释放旧资源
    terminate();

    EGLDisplay  display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY) {
        ALOG_E(TAG, "[EGL] no display");
        return EGL_FALSE;
    }

    EGLint major, minor;
    if (!eglInitialize(display, &major, &minor)) {
        ALOG_E(TAG, "[EGL] eglInitialize fail");
        return EGL_FALSE;
    }
    ALOG_I(TAG, "[EGL] eglInitialize %d.%d", major, minor);

    static const EGLint configAttribs[] = {
            EGL_RENDERABLE_TYPE,    EGL_OPENGL_ES2_BIT,
            EGL_SURFACE_TYPE,       EGL_WINDOW_BIT,
            EGL_BLUE_SIZE,          8,
            EGL_GREEN_SIZE,         8,
            EGL_RED_SIZE,           8,
            EGL_NONE
    };

    static const EGLint contextAttribs[] = {
            EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL_NONE
    };

    EGLConfig config;
    EGLint numConfigs;
    if (!eglChooseConfig(display, configAttribs, &config, 1, &numConfigs)) {
        ALOG_E(TAG, "[EGL] eglChooseConfig failed");
        eglTerminate(display);
        return EGL_FALSE;
    }

    EGLint native_visual_id = 0;
    if (!eglGetConfigAttrib(display, config, EGL_NATIVE_VISUAL_ID, &native_visual_id)) {
        ALOG_E(TAG, "[EGL] eglGetConfigAttrib return error %d", eglGetError());
        eglTerminate(display);
        return EGL_FALSE;
    }

    int32_t width  = ANativeWindow_getWidth(window);
    int32_t height = ANativeWindow_getHeight(window);
    ALOG_I(TAG, "[EGL] ANativeWindow_setBuffersGeometry(f=%d);", native_visual_id);
    int ret = ANativeWindow_setBuffersGeometry(window, width, height, native_visual_id);
    if (ret) {
        ALOG_E(TAG, "[EGL] ANativeWindow_setBuffersGeometry(format) returned error %d", ret);
        eglTerminate(display);
        return EGL_FALSE;
    }

    EGLSurface surface = eglCreateWindowSurface(display, config, window, nullptr);
    if (surface == EGL_NO_SURFACE) {
        ALOG_E(TAG, "[EGL] eglCreateWindowSurface failed\n");
        eglTerminate(display);
        return EGL_FALSE;
    }

    EGLSurface context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);
    if (context == EGL_NO_CONTEXT) {
        ALOG_E(TAG, "[EGL] eglCreateContext failed\n");
        eglDestroySurface(display, surface);
        eglTerminate(display);
        return EGL_FALSE;
    }

    if (!eglMakeCurrent(display, surface, surface, context)) {
        ALOG_E(TAG, "[EGL] elgMakeCurrent() failed (new)\n");
        eglDestroyContext(display, context);
        eglDestroySurface(display, surface);
        eglTerminate(display);
        return EGL_FALSE;
    }

    window_ = window;
    context_ = context;
    surface_ = surface;
    display_ = display;
    if (EGL_FALSE == setup()) {
        ALOG_E(TAG, "setup() fail");
        terminate();
        return EGL_FALSE;
    }
    ALOG_I(TAG, "makeCurrent success");

    return EGL_TRUE;
}

EGLBoolean SkyEGL2Renderer::setup() {
    if (!isValid()) {
        ALOG_E(TAG, "setup but renderer invalid");
        return EGL_FALSE;
    }

    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);       skyElg2CheckError("glClearColor");
    glEnable(GL_CULL_FACE);     skyElg2CheckError("glEnable");
    glCullFace(GL_BACK);        skyElg2CheckError("glCullFace");
    glDisable(GL_DEPTH_TEST);       skyElg2CheckError("glDisable");

    return EGL_TRUE;
}

EGLBoolean SkyEGL2Renderer::prepareRenderer(AVFrame *avFrame) {
    if (nullptr == rendererImp_ || !rendererImp_->isValid() || rendererImp_->avPixFormat != avFrame->format) {
        if (nullptr != rendererImp_) {
            rendererImp_.reset();
        }

        rendererImp_ = createRenderImpFactory(static_cast<AVPixelFormat>(avFrame->format));
        if (nullptr == rendererImp_) {
            ALOG_E(TAG, "[EGL] createRenderImpFactory fail.");
            return EGL_FALSE;
        }
        rendererImp_->init();
        if (!rendererImp_->isValid()) {
            ALOG_E(TAG, "[EGL] renderImp init fail!");
            return GL_FALSE;
        }
        if (!rendererImp_->use()) {
            ALOG_E(TAG, "renderImp use() fail.");
            return GL_FALSE;
        }
        // 新建 impl：标记需把 LUT 状态回灌到新 impl（在 displayImage 下发）
        {
            std::lock_guard<std::mutex> lk(lutMtx_);
            implRecreated_ = true;
        }
    }

    // todo 这里根据帧宽高设置 surface 的大小，那 顶点坐标 剪切的处理怎么影响这里的大小？
    if (!setSurfaceSize(avFrame->width, avFrame->height)) {
        ALOG_E(TAG, "[EGL] setSurfaceSize() error");
        return GL_FALSE;
    }
    glViewport(0, 0, surfaceWidth_, surfaceHeight_);  skyElg2CheckError("glViewport");

    return GL_TRUE;
}

EGLBoolean SkyEGL2Renderer::setSurfaceSize(int frameWidth, int frameHeight) {
    if (!isValid()) {
        ALOG_E(TAG, "[EGL] setSurfaceSize() invalid");
        return GL_FALSE;
    }

    surfaceWidth_ = querySurfaceSurfaceWidth();
    surfaceHeight_ = querySurfaceSurfaceHeight();
    if (surfaceWidth_ != frameWidth || surfaceHeight_ != frameHeight) {
        int format = ANativeWindow_getFormat(window_);
        ALOG_I(TAG, "ANativeWindow_setBuffersGeometry(w=%d,h=%d) -> (w=%d,h=%d);",
               surfaceWidth_, surfaceHeight_,
               frameWidth, frameHeight);
        int ret = ANativeWindow_setBuffersGeometry(window_, frameWidth, frameHeight, format);
        if (ret) {
            ALOG_E(TAG, "[EGL] ANativeWindow_setBuffersGeometry() returned error %d", ret);
            return EGL_FALSE;
        }

        surfaceWidth_ = querySurfaceSurfaceWidth();
        surfaceHeight_ = querySurfaceSurfaceHeight();
        return (surfaceWidth_ && surfaceHeight_) ? EGL_TRUE : EGL_FALSE;
    }
    return GL_TRUE;
}

int SkyEGL2Renderer::querySurfaceSurfaceWidth() {
    EGLint width = 0;
    if (!eglQuerySurface(display_, surface_, EGL_WIDTH, &width)) {
        ALOG_E(TAG, "[EGL] eglQuerySurface(EGL_WIDTH) returned error %d", eglGetError());
        return 0;
    }

    return width;
}

int SkyEGL2Renderer::querySurfaceSurfaceHeight() {
    EGLint height = 0;
    if (!eglQuerySurface(display_, surface_, EGL_HEIGHT, &height)) {
        ALOG_E(TAG, "[EGL] eglQuerySurface(EGL_HEIGHT) returned error %d", eglGetError());
        return 0;
    }

    return height;
}

void skyElg2CheckError(const char* op) {
    GLenum err;
    while ((err = glGetError()) != GL_NO_ERROR) {
        ALOG_E(TAG, "[EGL] Error after %s() glError(0x%x)", op, err);
    }
}

GLuint SkyEGL2RendererImp::compileShader(GLenum type, const char *source) {
    assert(source);

    GLuint shader = glCreateShader(type);
    if (!shader) {
        return 0;
    }

    glShaderSource(shader, 1, &source, nullptr);    skyElg2CheckError("glShaderSource");
    glCompileShader(shader);        skyElg2CheckError("glCompileShader");

    GLint compileStatus = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compileStatus);
    if (!compileStatus) {
        goto fail;
    }

    return shader;

fail:
    printShaderInfo(shader);
    glDeleteShader(shader);

    return 0;
}

void SkyEGL2RendererImp::printShaderInfo(GLuint shader) {
    if (!shader) {
        return;
    }

    GLint info_len = 0;
    glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &info_len);

    if (info_len <= 0) {
        ALOG_E(TAG, "empty info");
        return;
    }

    // 使用栈上小缓冲区，如果不够则使用堆分配
    constexpr GLsizei stack_buf_size = 32;
    char stack_buf[stack_buf_size];
    char* buf = stack_buf;
    GLsizei buf_len = stack_buf_size - 1;

    std::unique_ptr<char[]> heap_buf;
    if (info_len > stack_buf_size) {
        heap_buf = std::make_unique<char[]>(info_len + 1);
        if (heap_buf) {
            buf = heap_buf.get();
            buf_len = info_len;
        }
    }

    // 获取日志信息
    glGetShaderInfoLog(shader, buf_len, nullptr, buf);
    // 打印错误信息
    ALOG_E(TAG, "error info:\n %s", buf);
}

void SkyEGL2RendererImp::printProgramInfo(GLuint program) {
    if (!program) {
        return;
    }

    GLint info_len = 0;
    glGetProgramiv(program, GL_INFO_LOG_LENGTH, &info_len);

    if (info_len <= 0) {
        ALOG_E(TAG, "empty info");
        return;
    }

    // 使用栈缓冲区，如果不够则使用堆分配
    constexpr GLsizei kStackBufferSize = 32;
    char stack_buf[kStackBufferSize];
    char* buf = stack_buf;
    GLsizei buf_len = kStackBufferSize - 1;

    std::unique_ptr<char[]> heap_buf;
    if (info_len > kStackBufferSize) {
        heap_buf = std::make_unique<char[]>(info_len + 1);
        if (heap_buf) {
            buf = heap_buf.get();
            buf_len = info_len;
        }
    }

    glGetProgramInfoLog(program, buf_len, nullptr, buf);
    ALOG_E(TAG, "error:\n%s", buf);
}

void SkyEGL2RendererImp::init() {
    vertexShader = compileShader(GL_VERTEX_SHADER, YUV_VERTEX_SHADER_DEFAULT);
    if (!vertexShader) {
        goto fail;
    }
    fragmentShader = compileShader(GL_FRAGMENT_SHADER, getFragmentShaderSource());
    if (!fragmentShader) {
        goto fail;
    }
    program = glCreateProgram();        skyElg2CheckError("glCreateProgram");
    if (!program) {
        goto fail;
    }
    glAttachShader(program, vertexShader);      skyElg2CheckError("glAttachShader");
    glAttachShader(program, fragmentShader);    skyElg2CheckError("glAttachShader");
    glLinkProgram(program);
    GLint linkStatus;
    glGetProgramiv(program, GL_LINK_STATUS, &linkStatus);
    if (!linkStatus) {
        goto fail;
    }
    av4_position = glGetAttribLocation(program, "av4_Position");     skyElg2CheckError("glGetAttribLocation(av4_Position)");
    av2_texcoord = glGetAttribLocation(program, "av2_Texcoord");     skyElg2CheckError("glGetAttribLocation(av2_Texcoord)");
    um4_mvp = glGetUniformLocation(program, "um4_ModelViewProjection");     skyElg2CheckError("glGetUniformLocation(um4_ModelViewProjection)");

    // LUT/增强 uniform（所有片段着色器同名；若被优化掉返回 -1，glUniform 对 -1 为 no-op）
    us2_sampler_lut_ = glGetUniformLocation(program, "us2_SamplerLUT");
    u_lut_enabled_   = glGetUniformLocation(program, "u_LutEnabled");
    u_sharpness_     = glGetUniformLocation(program, "u_Sharpness");
    u_deband_        = glGetUniformLocation(program, "u_Deband");
    u_texel_size_    = glGetUniformLocation(program, "u_TexelSize");

    return;

fail:
    if (program) {
        printProgramInfo(program);
        glDeleteProgram(program);
        program = INVALID_PROGRAM;
    }
}

void
SkyEGL2RendererImp::buildOrthoMatrix(Matrix4x4Std &matrix, GLfloat left, GLfloat right, GLfloat bottom,
                                     GLfloat top, GLfloat near, GLfloat far) {
    GLfloat r_l = right - left;
    GLfloat t_b = top - bottom;
    GLfloat f_n = far - near;
    GLfloat tx = - (right + left) / (right - left);
    GLfloat ty = - (top + bottom) / (top - bottom);
    GLfloat tz = - (far + near) / (far - near);

    matrix[0] = 2.0f / r_l;
    matrix[1] = 0.0f;
    matrix[2] = 0.0f;
    matrix[3] = 0.0f;

    matrix[4] = 0.0f;
    matrix[5] = 2.0f / t_b;
    matrix[6] = 0.0f;
    matrix[7] = 0.0f;

    matrix[8] = 0.0f;
    matrix[9] = 0.0f;
    matrix[10] = -2.0f / f_n;
    matrix[11] = 0.0f;

    matrix[12] = tx;
    matrix[13] = ty;
    matrix[14] = tz;
    matrix[15] = 1.0f;
}

void SkyEGL2RendererImp::resetTextureCoordinatesToCover() {
    texcoords[0] = 0.0f;
    texcoords[1] = 1.0f; // left-top
    texcoords[2] = 1.0f;
    texcoords[3] = 1.0f; // right-top
    texcoords[4] = 0.0f;
    texcoords[5] = 0.0f; // left-bottom
    texcoords[6] = 1.0f;
    texcoords[7] = 0.0f; // right-bottom
}

void SkyEGL2RendererImp::buildAndEnableTextureCoordinatesAttributes() {
    glVertexAttribPointer(av2_texcoord, 2, GL_FLOAT, GL_FALSE, 0, texcoords.data());        skyElg2CheckError("glVertexAttribPointer(av2_texcoord)");
    glEnableVertexAttribArray(av2_texcoord);        skyElg2CheckError("glEnableVertexAttribArray(av2_texcoord)");
}

void SkyEGL2RendererImp::resetVerticesToNDC() {
    vertices[0] = -1.0f;
    vertices[1] = -1.0f; // left-bottom
    vertices[2] =  1.0f;
    vertices[3] = -1.0f; // right-bottom
    vertices[4] = -1.0f;
    vertices[5] =  1.0f; // left-top
    vertices[6] =  1.0f;
    vertices[7] =  1.0f; // right-top
}

void SkyEGL2RendererImp::buildAndEnableVerticesAttributes() {
    glVertexAttribPointer(av4_position, 2, GL_FLOAT, GL_FALSE, 0, vertices.data());     skyElg2CheckError("glVertexAttribPointer(av2_texcoord)");
    glEnableVertexAttribArray(av4_position);        skyElg2CheckError("glEnableVertexAttribArray(av4_position)");
}

EGLBoolean SkyEGL2RendererImp::renderImage(AVFrame *avFrame) {
    glClear(GL_COLOR_BUFFER_BIT);               skyElg2CheckError("glClear");

    GLsizei visibleWidth = avFrame->width;
    GLsizei visibleHeight = avFrame->height;
    if (frameWidth != visibleWidth || frameHeight != visibleHeight
        || frameSarNum != avFrame->sample_aspect_ratio.num || frameSarDen != avFrame->sample_aspect_ratio.den) {
        frameWidth = visibleWidth;
        frameHeight = visibleHeight;
        frameSarNum = avFrame->sample_aspect_ratio.num;
        frameSarDen = avFrame->sample_aspect_ratio.den;

        verticesChanged = true;
    }

    lastBufferWidth = getBufferWidth(avFrame);
    GLboolean ret = uploadTexture(avFrame);
    if (!ret) {
        ALOG_E(TAG, "[EGL] renderImage fail!!!");
        return EGL_FALSE;
    }

    if (verticesChanged) {

    }

    applyEffectsInRender(avFrame);

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);      skyElg2CheckError("glDrawArrays");
    return GL_TRUE;
}

void SkyEGL2RendererImp::updateLut(const uint8_t* rgba, float intensity, bool enabled) {
    lut_enabled_ = enabled;
    lut_intensity_ = intensity;
    if (rgba && enabled) {
        constexpr size_t kLutBytes = 512 * 512 * 4;
        lut_pending_.assign(rgba, rgba + kLutBytes);
        lut_pending_dirty_ = true;
    }
}

void SkyEGL2RendererImp::updateEnhance(float sharpness, float deband) {
    enhance_sharpness_ = sharpness;
    enhance_deband_ = deband;
}

void SkyEGL2RendererImp::applyEffectsInRender(const AVFrame* avFrame) {
    // 保存当前 active 纹理单元（= use() 最后设置的单元），LUT 用单元 3，结束后还原，
    // 以免影响下一帧 uploadTexture 依赖的 active 单元状态。
    GLint savedUnit = GL_TEXTURE0;
    glGetIntegerv(GL_ACTIVE_TEXTURE, &savedUnit);

    if (lut_pending_dirty_ && !lut_pending_.empty()) {
        if (0 == lut_texture_) {
            glGenTextures(1, &lut_texture_);
        }
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, lut_texture_);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 512, 512, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                     lut_pending_.data());
        lut_pending_dirty_ = false;
        skyElg2CheckError("upload LUT texture");
    }

    if (lut_texture_ != 0) {
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, lut_texture_);
        glUniform1i(us2_sampler_lut_, 3);
    }
    glUniform1f(u_lut_enabled_, (lut_enabled_ && lut_texture_ != 0) ? lut_intensity_ : 0.0f);

    // 画质增强 uniform：texel size 按当前帧设置，对 impl 重建/分辨率变化天然鲁棒
    glUniform1f(u_sharpness_, enhance_sharpness_);
    glUniform1f(u_deband_, enhance_deband_);
    if (avFrame && avFrame->width > 0 && avFrame->height > 0) {
        glUniform2f(u_texel_size_, 1.0f / (float) avFrame->width, 1.0f / (float) avFrame->height);
    }

    glActiveTexture(savedUnit);
}

void SkyEGL2RendererImp::releaseLutTexture() {
    if (lut_texture_ != 0) {
        glDeleteTextures(1, &lut_texture_);
        lut_texture_ = 0;
    }
    lut_pending_dirty_ = false;
}

std::unique_ptr<SkyRenderer> SkyRenderer::create(RendererBackend backend) {
    switch (backend) {
        case RendererBackend::VULKAN:
            ALOG_I("SkyRenderer", "========== RENDER BACKEND: Vulkan ==========");
            return std::make_unique<SkyVkRenderer>();

        case RendererBackend::METAL:
            ALOG_W("SkyRenderer", "Metal backend not yet implemented, falling back to OpenGL ES");
            ALOG_I("SkyRenderer", "========== RENDER BACKEND: OpenGL ES 2.0 (Metal fallback) ==========");
            return std::make_unique<SkyEGL2Renderer>();

        case RendererBackend::AUTO:
        case RendererBackend::OPENGL_ES:
        default:
            ALOG_I("SkyRenderer", "========== RENDER BACKEND: OpenGL ES 2.0 ==========");
            return std::make_unique<SkyEGL2Renderer>();
    }
}

inline std::unique_ptr<SkyEGL2RendererImp> createRenderImpFactory(AVPixelFormat format) {
    ALOG_I("SkyEGL2Renderer", "createRenderImpFactory format=%d", format);
    switch (format) {
        case AV_PIX_FMT_YUV420P:
            return std::make_unique<SkyEGL2RendererYUV420pImp>(format);

        // Semi-planar YUV formats - 使用专用渲染器
        case AV_PIX_FMT_NV12:
            ALOG_I("SkyEGL2Renderer", "Using dedicated NV12 renderer");
            return std::make_unique<SkyEGL2RendererNV12Imp>(format);
        case AV_PIX_FMT_NV21:
            ALOG_I("SkyEGL2Renderer", "Using dedicated NV21 renderer");
            return std::make_unique<SkyEGL2RendererNV21Imp>(format);

        // YUV422 formats - 使用专用渲染器
        case AV_PIX_FMT_YUV422P:
            ALOG_I("SkyEGL2Renderer", "Using dedicated YUV422P renderer");
            return std::make_unique<SkyEGL2RendererYUV422pImp>(format);
        case AV_PIX_FMT_YUYV422:
        case AV_PIX_FMT_UYVY422:
            ALOG_I("SkyEGL2Renderer", "Using YUV422P renderer for packed YUV422 format");
            return std::make_unique<SkyEGL2RendererYUV422pImp>(AV_PIX_FMT_YUV422P);

        // YUV444 formats - 回退到 YUV420P (需要 FFmpeg 转换)
        case AV_PIX_FMT_YUV444P:
            ALOG_I("SkyEGL2Renderer", "Using YUV420P renderer for YUV444P format (FFmpeg conversion)");
            return std::make_unique<SkyEGL2RendererYUV420pImp>(AV_PIX_FMT_YUV420P);

        // RGB formats - 使用专用渲染器
        case AV_PIX_FMT_RGB24:
        case AV_PIX_FMT_BGR24:
        case AV_PIX_FMT_RGBA:
        case AV_PIX_FMT_BGRA:
        case AV_PIX_FMT_ARGB:
        case AV_PIX_FMT_ABGR:
        case AV_PIX_FMT_RGB565:
        case AV_PIX_FMT_BGR565:
            ALOG_I("SkyEGL2Renderer", "Using dedicated RGBA renderer for RGB format");
            return std::make_unique<SkyEGL2RendererRGBAImp>(format);

        default:
            ALOG_W("SkyEGL2Renderer", "Unsupported pixel format: %d", format);
            // 尝试回退到 YUV420P 格式
            ALOG_I("SkyEGL2Renderer", "Falling back to YUV420P renderer");
            return std::make_unique<SkyEGL2RendererYUV420pImp>(AV_PIX_FMT_YUV420P);
    }
}










