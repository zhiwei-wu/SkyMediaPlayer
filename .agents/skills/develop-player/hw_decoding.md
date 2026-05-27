# 硬件解码（Hardware Decoding）

## 概述

SkyPlayer 硬件解码模块基于平台原生 API 实现视频硬件加速解码，采用跨平台抽象层设计，支持三级回退策略。

## 架构设计

```
┌─────────────────────────────────────────────┐
│              Java/Kotlin Layer              │
│  SkyMediaPlayer.setDecoderMode(DecoderMode) │
└─────────────────┬───────────────────────────┘
                  │ JNI (_setDecoderMode)
┌─────────────────▼───────────────────────────┐
│           SkyPlayer / SkyDecoderHandler      │
│         管理硬解生命周期 + 三级回退策略        │
└─────────────────┬───────────────────────────┘
                  │
┌─────────────────▼───────────────────────────┐
│         SkyHWDecoder (跨平台抽象基类)         │
│  configure / start / sendPacket / receiveFrame│
└──────┬──────────────────────────┬────────────┘
       │                          │
┌──────▼──────────┐    ┌─────────▼────────────┐
│ SkyMediaCodec   │    │ SkyVideoToolbox      │
│ Decoder         │    │ Decoder (iOS 未来)    │
│ (Android NDK)   │    │                      │
└─────────────────┘    └──────────────────────┘
```

## 解码模式

| 模式 | 枚举值 | 说明 |
|------|--------|------|
| HW_SURFACE | 0 | 硬解 + Surface 直渲染（零拷贝，性能最优） |
| HW_BUFFER | 1 | 硬解 + Buffer 输出（取出 NV12 帧，支持后处理） |
| SOFTWARE | 2 | FFmpeg 纯软解 |
| AUTO | 3 | 自动选择，三级回退：HW_SURFACE → HW_BUFFER → SOFTWARE |

## 三级回退策略

```
AUTO 模式启动
    │
    ▼
尝试 HW_SURFACE（Surface 直渲）
    │
    ├── 成功 → 使用 Surface 直渲模式
    │
    ▼ 失败
尝试 HW_BUFFER（Buffer 输出）
    │
    ├── 成功 → 使用 Buffer 输出模式
    │
    ▼ 失败
回退到 SOFTWARE（FFmpeg 软解）
```

## 关键文件

### 类型定义
- `skymediaplayer/src/main/cpp/include/sky_decoder_types.h` — DecoderMode 枚举（C++ enum class + C 兼容宏）

### 跨平台抽象层
- `skymediaplayer/src/main/cpp/player/sky_hw_decoder.h` — SkyHWDecoder 抽象基类
- `skymediaplayer/src/main/cpp/player/sky_hw_decoder.cpp` — 工厂方法 `SkyHWDecoder::create()`

### Android 平台实现
- `skymediaplayer/src/main/cpp/player/sky_mediacodec_decoder.h` — SkyMediaCodecDecoder 声明
- `skymediaplayer/src/main/cpp/player/sky_mediacodec_decoder.cpp` — NDK AMediaCodec 完整实现

### 播放器集成
- `skymediaplayer/src/main/cpp/player/skymediaplayer.h` — SkyDecoderHandler 类声明
- `skymediaplayer/src/main/cpp/player/skymediaplayer.cpp` — SkyDecoderHandler 实现 + C 接口函数
- `skymediaplayer/src/main/cpp/include/skymediaplayer_interface.h` — C 接口声明（供 ffplay.c 调用）

### FFplay 集成
- `skymediaplayer/src/main/cpp/ffplay/ffplay.h` — VideoState 新增 `hw_decoder_active`、`hw_surface_mode` 字段
- `skymediaplayer/src/main/cpp/ffplay/ffplay.c` — `stream_component_open` 硬解初始化 + `video_thread_hw` 硬解视频线程

### JNI + Java 层
- `skymediaplayer/src/main/cpp/skymediaplayer_jni.cpp` — `_setDecoderMode(int)` JNI 方法
- `skymediaplayer/src/main/java/imt/zw/skymediaplayer/player/SkyMediaPlayer.kt` — `DecoderMode` 枚举 + `setDecoderMode()` 方法

### 构建配置
- `skymediaplayer/src/main/cpp/CMakeLists.txt` — 新增源文件 + 链接 `mediandk` 库

## 使用方式

### Java/Kotlin 层调用

```kotlin
val player = SkyMediaPlayer()

// 设置解码模式（必须在 prepareAsync 之前调用）
player.setDecoderMode(SkyMediaPlayer.DecoderMode.AUTO)      // 自动三级回退（默认）
player.setDecoderMode(SkyMediaPlayer.DecoderMode.HW_SURFACE) // 强制 Surface 直渲
player.setDecoderMode(SkyMediaPlayer.DecoderMode.HW_BUFFER)  // 强制 Buffer 输出
player.setDecoderMode(SkyMediaPlayer.DecoderMode.SOFTWARE)   // 强制软解

player.setDataSource(context, videoPath)
player.setDisplay(surfaceHolder)
player.prepareAsync()
```

## 数据流

### Surface 直渲模式
```
PacketQueue → sky_hw_decoder_send_packet → MediaCodec 解码
    → releaseOutputBuffer(render=true) → Surface 直接显示
    → 更新 vidclk 时钟（音画同步）
```

### Buffer 输出模式
```
PacketQueue → sky_hw_decoder_send_packet → MediaCodec 解码
    → getOutputBuffer → 根据 colorFormat 动态检测像素格式
    → 复制 YUV 数据到 AVFrame → 视频滤镜 → queue_picture → OpenGL/Vulkan 渲染
```

#### Buffer 模式像素格式支持

| MediaCodec colorFormat | 值 | AVPixelFormat | 内存布局 |
|---|---|---|---|
| COLOR_FormatYUV420Planar | 19 | AV_PIX_FMT_YUV420P | Y + U + V 三平面 |
| COLOR_FormatYUV420PackedPlanar | 20 | AV_PIX_FMT_YUV420P | 同上 |
| COLOR_FormatYUV420SemiPlanar | 21 | AV_PIX_FMT_NV12 | Y + UV 交错 |
| COLOR_FormatYUV420PackedSemiPlanar | 39 | AV_PIX_FMT_NV12 | 同上 |
| COLOR_FormatNV21 | 23 | AV_PIX_FMT_NV21 | Y + VU 交错 |
| COLOR_FormatYUV420Flexible | 0x7F420888 | AV_PIX_FMT_NV12 | 灵活格式，默认 NV12 |

> 动态检测逻辑在 `SkyMediaCodecDecoder::fillFrameFromBuffer` 中实现，
> 通过 `AMediaCodec_getOutputFormat` 获取实际 colorFormat 后映射到对应的 AVPixelFormat。

### 软解模式（原始路径）
```
PacketQueue → avcodec_send_packet → FFmpeg 软解码
    → avcodec_receive_frame → 视频滤镜 → queue_picture → 渲染
```

## 跨平台扩展

添加新平台支持只需：
1. 创建 `SkyXxxDecoder` 继承 `SkyHWDecoder`
2. 实现所有纯虚函数
3. 在 `SkyHWDecoder::create()` 中添加平台条件编译分支

示例（iOS VideoToolbox）：
```cpp
// sky_hw_decoder.cpp
std::unique_ptr<SkyHWDecoder> SkyHWDecoder::create() {
#if defined(__ANDROID__)
    return std::make_unique<SkyMediaCodecDecoder>();
#elif defined(__APPLE__)
    return std::make_unique<SkyVideoToolboxDecoder>();
#else
    return nullptr;
#endif
}
```
