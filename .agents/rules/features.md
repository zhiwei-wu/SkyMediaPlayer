---
alwaysApply: true
---

# SkyPlayer 主要功能模块

SkyPlayer 是一个基于 FFmpeg 8.0 官方 ffplay 深度改造的 Android 音视频播放器，提供从解封装、解码、同步到渲染的完整实现。

## 核心功能

### 1. 播放器核心

播放器核心是 SkyPlayer 的基础，提供标准的媒体播放 API。

**相关代码目录**:
- `skymediaplayer/src/main/java/imt/zw/skymediaplayer/player/`: Java/Kotlin 播放器接口和实现
- `skymediaplayer/src/main/cpp/player/skymediaplayer.cpp`: Native 播放器核心
- `skymediaplayer/src/main/cpp/ffplay/ffplay.c`: FFmpeg ffplay 引擎

**主要特点**:
- 兼容 Android MediaPlayer API
- 支持本地文件和网络流媒体
- 异步准备和播放控制
- 完整的播放状态管理
- 精确的 Seek 功能

**核心接口**:
```kotlin
interface IMediaPlayer {
    fun setDataSource(path: String)
    fun prepareAsync()
    fun start()
    fun pause()
    fun stop()
    fun seekTo(msec: Long)
    fun release()
}
```

### 2. 视频渲染

视频渲染模块使用 OpenGL ES 2.0 实现硬件加速渲染，支持多种像素格式。

**相关代码目录**:
- `skymediaplayer/src/main/cpp/player/skyrenderer.cpp`: 渲染器管理
- `skymediaplayer/src/main/cpp/player/sky_egl2_renderer_*.cpp`: 5 种像素格式渲染器

**支持的像素格式**:
- **YUV420P**: 最常见的视频格式
- **YUV422P**: 高质量视频格式
- **NV12**: Android 硬件解码常用格式
- **NV21**: Android Camera 常用格式
- **RGBA**: 通用图像格式

**主要特点**:
- OpenGL ES 2.0 硬件加速
- Shader 实现 YUV → RGB 色彩空间转换
- 10-100x 比 CPU 渲染更快
- 支持 4K+ 分辨率
- 低功耗

### 3. 音频输出

音频输出模块使用 OpenSL ES 实现超低延迟音频播放。

**相关代码目录**:
- `skymediaplayer/src/main/cpp/player/skyaudio.cpp`: OpenSL ES 音频输出
- `skymediaplayer/src/main/java/imt/zw/skymediaplayer/audio/`: 音频焦点管理

**主要特点**:
- OpenSL ES 低延迟（< 20ms）
- 实时线程优先级
- 多缓冲区设计
- 音频焦点管理

**支持的音频格式**:
- AAC、MP3、Opus、Vorbis
- 采样率：8kHz - 192kHz
- 声道：单声道/立体声

### 4. JNI 桥接层

JNI 层实现 Java/Kotlin 与 Native 代码的通信。

**相关代码目录**:
- `skymediaplayer/src/main/cpp/skymediaplayer_jni.cpp`: JNI 桥接实现

**主要特点**:
- 12 个 Native 方法注册
- TLS 管理 JNIEnv
- 自动 attach/detach 线程
- 弱全局引用防止内存泄漏
- 线程安全设计

**JNI 方法列表**:
```cpp
_native_setup()
_setDataSource(String)
_prepareAsync()
_start()
_pause()
_stop()
_seekTo(long)
_release()
// ... 更多方法
```

### 5. 消息队列

消息队列实现异步事件处理和状态通知。

**相关代码目录**:
- `skymediaplayer/src/main/cpp/player/sky_msg_queue.cpp`: 消息队列实现
- `skymediaplayer/src/main/cpp/include/sky_messages.h`: 消息定义

**主要特点**:
- 异步事件处理
- 解耦播放控制和状态通知
- 线程安全的事件传递
- 支持多种消息类型

**消息类型**:
- `MEDIA_PREPARED`: 准备完成
- `MEDIA_PLAYBACK_COMPLETE`: 播放完成
- `MEDIA_BUFFERING_UPDATE`: 缓冲更新
- `MEDIA_SEEK_COMPLETE`: Seek 完成
- `MEDIA_ERROR`: 错误通知

## 网络支持

### 支持的协议
- **HTTP/HTTPS**: 标准在线视频点播
- **HLS (m3u8)**: 自适应码率直播流
- **本地文件**: 支持本地存储的所有格式

### 技术特性
- OpenSSL 集成支持 HTTPS 加密传输
- HLS 自适应码率切换
- 智能网络缓冲策略
- 断点续播支持

## 支持的格式

### 视频容器
- MP4、AVI、MKV、WebM、MOV

### 视频编码
- H.264、H.265/HEVC、MPEG-4、MPEG-2、VP8、VP9

### 音频编码
- AAC、MP3、Opus、Vorbis

## UI 组件

### SkyVideoView

完整的视频播放视图组件。

**相关代码目录**:
- `skymediaplayer/src/main/java/imt/zw/skymediaplayer/widget/SkyVideoView.kt`

**主要特点**:
- 封装播放器和渲染视图
- 自动处理 Surface 生命周期
- 支持多种缩放模式

### SurfaceRenderView

Surface 渲染视图。

**相关代码目录**:
- `skymediaplayer/src/main/java/imt/zw/skymediaplayer/widget/SurfaceRenderView.kt`

**主要特点**:
- 管理 SurfaceHolder
- 处理 Surface 创建和销毁
- 视频尺寸适配

## 示例应用

app 模块提供完整的使用示例。

**相关代码目录**:
- `app/src/main/java/imt/skymediaplayer/demo/`

**功能演示**:
- 基本播放功能
- 播放控制（播放、暂停、停止、Seek）
- 播放状态显示
- 错误处理
- 在线视频播放
- HLS 直播流支持

## 开发路线

### ✅ 已完成
- 核心播放引擎（基于 ffplay）
- 5 种像素格式硬件渲染
- OpenSL ES 低延迟音频
- JNI 线程安全框架
- 本地文件播放
- 在线视频播放（HTTP/HTTPS）
- HLS 直播流支持（m3u8）
- OpenSSL 集成

### 🚧 进行中
- RTMP 直播流支持
- 字幕支持
- 播放列表管理
- 网络状态监控和自适应

### 📋 计划中
- 硬件解码（MediaCodec）
- 倍速播放
- 截图功能
- 视频录制
- RTSP 流支持
