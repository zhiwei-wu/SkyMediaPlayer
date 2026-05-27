# SkyPlayer 视频解码播放流程

## 概述

SkyPlayer 的视频解码播放采用**生产者-消费者模型**，通过多线程实现高效的视频处理流水线。

## 核心线程

```
read_thread (读取线程)
    ↓ av_read_frame()
packet_queue_put() → videoq (视频包队列)
    ↓
video_thread (视频解码线程)
    ↓ decoder_decode_frame()
    ↓ queue_picture()
frame_queue_push() → pictq (视频帧队列)
    ↓
refresh_thread (刷新线程)
    ↓ video_refresh()
    ↓ sky_video_image_display()
SkyEGL2Renderer::displayImage() (OpenGL ES 渲染)
```

## 视频解码线程

**文件位置**: `skymediaplayer/src/main/cpp/ffplay/ffplay.c`（第 2837-2895 行）

### video_thread 工作流程

```c
static int video_thread(void *arg) {
    VideoState *is = arg;
    AVFrame *frame = av_frame_alloc();
    
    for (;;) {
        // 1. 从解码器获取视频帧
        ret = get_video_frame(is, frame);
        if (ret < 0) goto the_end;
        if (!ret) continue;
        
        // 2. 检测帧参数变化，重新配置滤镜图
        if (last_w != frame->width || last_h != frame->height || 
            last_format != frame->format || last_serial != is->viddec.pkt_serial) {
            configure_video_filters(graph, is, vfilters_list, frame);
            last_w = frame->width;
            last_h = frame->height;
            last_format = frame->format;
            last_serial = is->viddec.pkt_serial;
        }
        
        // 3. 将帧送入滤镜图处理
        ret = av_buffersrc_add_frame(filt_in, frame);
        
        // 4. 从滤镜图获取处理后的帧
        while ((ret = av_buffersink_get_frame_flags(filt_out, frame, 0)) >= 0) {
            // 5. 计算 PTS 和时长
            tb = av_buffersink_get_time_base(filt_out);
            pts = (frame->pts == AV_NOPTS_VALUE) ? NAN : frame->pts * av_q2d(tb);
            duration = (frame_rate.num && frame_rate.den) ? 
                       av_q2d((AVRational){frame_rate.den, frame_rate.num}) : 0;
            
            // 6. 将帧放入显示队列
            ret = queue_picture(is, frame, pts, duration, 
                               fd ? fd->pkt_pos : -1, is->viddec.pkt_serial);
            av_frame_unref(frame);
        }
    }
}
```

### get_video_frame 函数

**文件位置**: `ffplay.c`（第 1665-1695 行）

```c
static int get_video_frame(VideoState *is, AVFrame *frame) {
    // 1. 从解码器解码帧
    got_picture = decoder_decode_frame(&is->viddec, frame, NULL);
    
    if (got_picture) {
        // 2. 计算 PTS
        if (frame->pts != AV_NOPTS_VALUE)
            dpts = av_q2d(is->video_st->time_base) * frame->pts;
        
        // 3. 推测采样宽高比
        frame->sample_aspect_ratio = av_guess_sample_aspect_ratio(
            is->ic, is->video_st, frame);
        
        // 4. 帧丢弃策略：如果视频落后太多，丢弃帧
        if (framedrop > 0 || (framedrop && 
            get_master_sync_type(is) != AV_SYNC_VIDEO_MASTER)) {
            double diff = dpts - get_master_clock(is);
            if (!isnan(diff) && fabs(diff) < AV_NOSYNC_THRESHOLD &&
                diff - is->frame_last_filter_delay < 0 &&
                is->viddec.pkt_serial == is->vidclk.serial &&
                is->videoq.nb_packets) {
                is->frame_drops_early++;
                av_frame_unref(frame);
                got_picture = 0;
            }
        }
    }
    return got_picture;
}
```

## 帧队列管理（FrameQueue）

**文件位置**: `ffplay.c`（第 418-530 行）

### FrameQueue 结构

```c
typedef struct FrameQueue {
    Frame queue[FRAME_QUEUE_SIZE];  // 环形队列
    int rindex;                      // 读取索引
    int windex;                      // 写入索引
    int size;                        // 当前帧数
    int max_size;                    // 最大容量
    int keep_last;                   // 是否保留最后一帧
    int rindex_shown;                // 已显示的帧数
    SDL_mutex *mutex;                // 互斥锁
    SDL_Condition *cond;             // 条件变量
    PacketQueue *pktq;               // 关联的包队列
} FrameQueue;
```

### 关键操作函数

**frame_queue_peek_writable**（获取可写入位置）：
```c
static Frame *frame_queue_peek_writable(FrameQueue *f) {
    // 等待队列有空间
    SDL_LockMutex(f->mutex);
    while (f->size >= f->max_size && !f->pktq->abort_request) {
        SDL_WaitCondition(f->cond, f->mutex);
    }
    SDL_UnlockMutex(f->mutex);
    
    // 返回可写入位置
    return &f->queue[f->windex];
}
```

**frame_queue_push**（推入帧）：
```c
static void frame_queue_push(FrameQueue *f) {
    // 移动写入索引
    if (++f->windex == f->max_size)
        f->windex = 0;
    
    SDL_LockMutex(f->mutex);
    f->size++;
    SDL_SignalCondition(f->cond);  // 通知等待的线程
    SDL_UnlockMutex(f->mutex);
}
```

**queue_picture**（将解码帧放入显示队列）：
```c
static int queue_picture(VideoState *is, AVFrame *src_frame, double pts, 
                         double duration, int64_t pos, int serial) {
    // 1. 获取可写入位置
    vp = frame_queue_peek_writable(&is->pictq);
    
    // 2. 设置帧信息
    vp->sar = src_frame->sample_aspect_ratio;
    vp->uploaded = 0;  // 标记未上传到 GPU
    vp->width = src_frame->width;
    vp->height = src_frame->height;
    vp->format = src_frame->format;
    vp->pts = pts;
    vp->duration = duration;
    vp->pos = pos;
    vp->serial = serial;
    
    // 3. 移动帧数据（零拷贝）
    av_frame_move_ref(vp->frame, src_frame);
    
    // 4. 推入队列
    frame_queue_push(&is->pictq);
    
    return 0;
}
```

## 视频渲染（OpenGL ES 2.0）

**文件位置**: `skymediaplayer/src/main/cpp/player/`

### 渲染器架构

```
SkyRenderer (基类)
    ↓
SkyEGL2Renderer (EGL 上下文管理)
    ↓
SkyEGL2RendererImp (渲染器实现基类)
    ↓
具体渲染器实现：
    - SkyEGL2RendererYUV420pImp  (最常见)
    - SkyEGL2RendererNV12Imp     (Android 硬解常用)
    - SkyEGL2RendererNV21Imp     (Android Camera)
    - SkyEGL2RendererYUV422pImp  (高质量视频)
    - SkyEGL2RendererRGBAImp     (通用图像)
```

### displayImage 流程

**文件位置**: `skyrenderer.cpp`（第 20-50 行）

```cpp
bool SkyEGL2Renderer::displayImage(EGLNativeWindowType window, AVFrame *frame) {
    // 1. 创建 EGL 上下文并设为当前
    if (!makeCurrent(window)) {
        return false;
    }
    
    // 2. 准备渲染器（根据帧格式选择渲染器）
    if (!prepareRenderer(frame)) {
        return false;
    }
    
    // 3. 渲染图像
    EGLBoolean ret = rendererImp_->renderImage(frame);
    
    // 4. 交换缓冲区
    eglSwapBuffers(display_, surface_);
    
    return true;
}
```

### makeCurrent 流程

```cpp
EGLBoolean SkyEGL2Renderer::makeCurrent(EGLNativeWindowType window) {
    // 1. 获取 EGL 显示
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    
    // 2. 初始化 EGL
    eglInitialize(display, &major, &minor);
    
    // 3. 选择配置（OpenGL ES 2.0）
    EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_NONE
    };
    eglChooseConfig(display, configAttribs, &config, 1, &numConfigs);
    
    // 4. 设置 Native Window 缓冲区几何
    ANativeWindow_setBuffersGeometry(window, width, height, native_visual_id);
    
    // 5. 创建 EGL Surface
    EGLSurface surface = eglCreateWindowSurface(display, config, window, nullptr);
    
    // 6. 创建 EGL 上下文（OpenGL ES 2.0）
    EGLint contextAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);
    
    // 7. 设为当前上下文
    eglMakeCurrent(display, surface, surface, context);
    
    return EGL_TRUE;
}
```

### 渲染器工厂

**文件位置**: `skyrenderer.cpp`（第 507-548 行）

```cpp
inline std::unique_ptr<SkyEGL2RendererImp> createRenderImpFactory(AVPixelFormat format) {
    switch (format) {
        case AV_PIX_FMT_YUV420P:
            return std::make_unique<SkyEGL2RendererYUV420pImp>(format);
        case AV_PIX_FMT_NV12:
            return std::make_unique<SkyEGL2RendererNV12Imp>(format);
        case AV_PIX_FMT_NV21:
            return std::make_unique<SkyEGL2RendererNV21Imp>(format);
        case AV_PIX_FMT_YUV422P:
            return std::make_unique<SkyEGL2RendererYUV422pImp>(format);
        case AV_PIX_FMT_RGB24:
        case AV_PIX_FMT_RGBA:
        case AV_PIX_FMT_BGRA:
            return std::make_unique<SkyEGL2RendererRGBAImp>(format);
        default:
            // 回退到 YUV420P
            return std::make_unique<SkyEGL2RendererYUV420pImp>(AV_PIX_FMT_YUV420P);
    }
}
```

## 像素格式转换（YUV → RGB）

### YUV420P 渲染器

**文件位置**: `sky_egl2_renderer_yuv420p.h/cpp`

**Fragment Shader**：
```glsl
precision highp float;
varying   highp vec2 vv2_Texcoord;
uniform         mat3 um3_ColorConversion;  // YUV→RGB 转换矩阵
uniform   lowp  sampler2D us2_SamplerX;    // Y 平面
uniform   lowp  sampler2D us2_SamplerY;    // U 平面
uniform   lowp  sampler2D us2_SamplerZ;    // V 平面

void main() {
    mediump vec3 yuv;
    lowp    vec3 rgb;
    
    // 1. 从纹理采样 YUV 值（偏移处理）
    yuv.x = (texture2D(us2_SamplerX, vv2_Texcoord).r - (16.0 / 255.0));
    yuv.y = (texture2D(us2_SamplerY, vv2_Texcoord).r - 0.5);
    yuv.z = (texture2D(us2_SamplerZ, vv2_Texcoord).r - 0.5);
    
    // 2. 矩阵转换 YUV → RGB
    rgb = um3_ColorConversion * yuv;
    
    gl_FragColor = vec4(rgb, 1);
}
```

**YUV→RGB 转换矩阵（BT.601 标准）**：
```cpp
static const GLfloat colorConversion[] = {
    1.164f,  1.164f, 1.164f,   // Y 系数
    0.0f,   -0.392f, 2.017f,   // U 系数
    1.596f, -0.813f, 0.0f,     // V 系数
};
glUniformMatrix3fv(um3_color_conversion, 1, GL_FALSE, colorConversion);
```

**uploadTexture 流程**：
```cpp
GLboolean SkyEGL2RendererYUV420pImp::uploadTexture(AVFrame *avFrame) {
    // YUV420P 有 3 个平面：Y（全分辨率）、U（1/4）、V（1/4）
    const std::array<GLsizei, 3> widths = {
        avFrame->linesize[0],    // Y 宽度
        avFrame->linesize[1],    // U 宽度
        avFrame->linesize[2]     // V 宽度
    };
    const std::array<GLsizei, 3> heights = {
        avFrame->height,         // Y 高度
        avFrame->height / 2,     // U 高度
        avFrame->height / 2      // V 高度
    };
    const std::array<GLubyte*, 3> pixels = {
        avFrame->data[0],        // Y 数据
        avFrame->data[1],        // U 数据
        avFrame->data[2]         // V 数据
    };
    
    // 分别上传 3 个平面到 3 个纹理
    for (size_t i = 0; i < 3; ++i) {
        glBindTexture(GL_TEXTURE_2D, plane_textures[i]);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE,
                    widths[i], heights[i], 0,
                    GL_LUMINANCE, GL_UNSIGNED_BYTE, pixels[i]);
    }
    
    return GL_TRUE;
}
```

## 视频刷新控制

**文件位置**: `ffplay.c`（第 1263-1350 行）

```c
static void video_refresh(void *opaque, double *remaining_time) {
    VideoState *is = opaque;
    
    if (is->video_st) {
        retry:
        if (frame_queue_nb_remaining(&is->pictq) == 0) {
            // 没有可显示的帧
        } else {
            Frame *vp, *lastvp;
            lastvp = frame_queue_peek_last(&is->pictq);
            vp = frame_queue_peek(&is->pictq);
            
            // 序列号不匹配，跳过
            if (vp->serial != is->videoq.serial) {
                frame_queue_next(&is->pictq);
                goto retry;
            }
            
            // 计算显示延迟
            last_duration = vp_duration(is, lastvp, vp);
            delay = compute_target_delay(last_duration, is);
            
            time = av_gettime_relative() / 1000000.0;
            
            // 时间未到，等待
            if (time < is->frame_timer + delay) {
                *remaining_time = FFMIN(is->frame_timer + delay - time, *remaining_time);
                goto display;
            }
            
            is->frame_timer += delay;
            update_video_pts(is, vp->pts, vp->serial);
            
            // 帧丢弃策略（CPU 太慢时）
            if (frame_queue_nb_remaining(&is->pictq) > 1) {
                Frame *nextvp = frame_queue_peek_next(&is->pictq);
                duration = vp_duration(is, vp, nextvp);
                if (!is->step && (framedrop > 0) && 
                    time > is->frame_timer + duration) {
                    is->frame_drops_late++;
                    frame_queue_next(&is->pictq);
                    goto retry;
                }
            }
            
            frame_queue_next(&is->pictq);
            is->force_refresh = 1;
        }
    display:
        if (!display_disable && is->force_refresh && 
            is->show_mode == SHOW_MODE_VIDEO && is->pictq.rindex_shown)
            video_display(is);
    }
}
```

## 关键函数调用链

```
read_thread (读取线程)
    ↓ av_read_frame()
packet_queue_put() → videoq (视频包队列)
    ↓
video_thread (视频解码线程)
    ↓ get_video_frame()
    ↓ decoder_decode_frame()
    ↓ avcodec_send_packet() + avcodec_receive_frame()
    ↓ av_buffersrc_add_frame() (滤镜图输入)
    ↓ av_buffersink_get_frame_flags() (滤镜图输出)
    ↓ queue_picture()
    ↓ frame_queue_push() → pictq (视频帧队列)
    ↓
refresh_thread (刷新线程)
    ↓ video_refresh()
    ↓ frame_queue_peek() → 从 pictq 读取帧
    ↓ sky_video_image_display()
    ↓ sky_display_image() (JNI 调用)
    ↓ SkyEGL2Renderer::displayImage()
    ↓ SkyEGL2RendererImp::renderImage()
    ↓ uploadTexture() (上传 YUV 纹理)
    ↓ glDrawArrays() (GPU 渲染)
    ↓ eglSwapBuffers() (交换缓冲区)
```

## 扩展开发指南

### 添加新的像素格式渲染器

1. 创建新的渲染器类，继承 `SkyEGL2RendererImp`
2. 实现 `init()`、`use()`、`uploadTexture()`、`renderImage()` 方法
3. 编写对应的 Vertex Shader 和 Fragment Shader
4. 在 `createRenderImpFactory()` 中添加新格式的分支

### 添加视频滤镜

1. 在 `configure_video_filters()` 中添加滤镜配置
2. 使用 FFmpeg 的 `avfilter` API 创建滤镜图
3. 在 `video_thread()` 中处理滤镜输出
