# SkyPlayer v1.1.0 - 在线视频播放功能更新

**[SkyPlayer：移动端 FFmpeg 播放器深度实践](https://juejin.cn/post/7576859345935417398)**

**项目地址**: [https://github.com/zhiwei-wu/SkyMediaPlayer](https://github.com/zhiwei-wu/SkyMediaPlayer)

---

## 📋 功能概述

本次更新为 SkyPlayer 添加了完整的在线视频播放能力，支持 HTTP/HTTPS 协议和 HLS (m3u8) 直播流，使播放器从本地播放器升级为支持网络流媒体的全功能播放器。

![在线视频播放演示](sky_online.jpg)

---

## 📊 技术特性

### 支持的协议
- ✅ **HTTP/HTTPS**: 标准在线视频点播
- ✅ **HLS (m3u8)**: 自适应码率直播流

### 核心能力
- **自适应缓冲**: 根据网络带宽动态调整缓冲策略
- **智能丢帧**: 网络波动时优先保证播放流畅度
- **HTTPS 加密**: 集成 OpenSSL 实现安全传输
- **HLS 自适应**: 自动根据网络状况切换清晰度

### 性能优化
- **流式处理**: 边下载边播放，减少内存占用
- **多线程架构**: 独立的网络读取、解码、渲染线程
- **资源管理**: 缓冲区复用，及时释放网络资源

---

## 🎯 核心变更

### 1. FFmpeg 网络支持

#### 编译配置升级
```bash
--enable-network          # 启用网络模块
--enable-openssl          # 集成 OpenSSL
--enable-protocol=http,https,tcp,udp,rtp,rtsp,hls  # 网络协议
--enable-demuxer=hls,mpegts  # HLS 解封装器
```

**关键特性**:
- 支持 HTTP/HTTPS 协议的在线视频
- 支持 HLS (m3u8) 自适应码率直播流
- OpenSSL 提供 TLS 1.2/1.3 加密传输

---

### 2. Native 层增强

#### 播放器核心 (`skymediaplayer.cpp`)

**数据源统一管理**
```cpp
void SkyPlayer::setDataSource(const char* path) {
    std::lock_guard<std::mutex> lock(mtx);
    
    // 释放之前的内存
    if (data_source_) {
        delete[] data_source_;
        data_source_ = nullptr;
    }
    
    if (path) {
        // 统一处理本地路径和网络 URL
        // 支持: /sdcard/video.mp4, http://..., https://..., .m3u8
        size_t len = strlen(path) + 1;
        data_source_ = new char[len];
        strcpy(data_source_, path);
        setPlayerState(STATE_INITIALIZED);
    }
}
```

**技术要点**:
- 统一处理本地文件路径和网络 URL
- 线程安全的内存管理
- FFmpeg 自动识别协议类型

#### FFplay 核心适配 (`ffplay.c`)

**网络流优化配置**
```c
static int infinite_buffer = -1;  // 自适应缓冲
static int framedrop = 1;         // 启用丢帧策略
```

**解码器状态管理**
```c
if (old_serial != d->pkt_serial) {
    // Seek 或网络重连时重置解码器
    avcodec_flush_buffers(d->avctx);
    d->finished = 0;
    d->next_pts = d->start_pts;
    d->next_pts_tb = d->start_pts_tb;
}
```

**技术要点**:
- 智能缓冲：根据网络状况动态调整
- 丢帧策略：网络波动时保证流畅度
- 解码器重置：Seek 时正确处理状态

---

### 3. Android 层适配

#### 网络权限配置 (`AndroidManifest.xml`)
```xml
<!-- 网络访问权限 -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- 支持 HTTP 明文传输 -->
<application android:usesCleartextTraffic="true">
```

#### Demo 应用增强 (`MainActivity.kt`)
```kotlin
// 新增在线视频播放入口
binding.btnOnlineVideo.setOnClickListener {
    val url = binding.etVideoUrl.text.toString()
    if (url.isNotEmpty()) {
        playOnlineVideo(url)
    }
}

private fun playOnlineVideo(url: String) {
    player.setDataSource(url)
    player.setSurface(binding.surfaceView.holder.surface)
    player.prepareAsync()
}
```

**UI 改进**:
- 添加 URL 输入框
- 在线视频播放按钮
- 播放进度显示
- 错误提示优化

---

## 🚀 使用方式

### 基本用法

```kotlin
val player = SkyMediaPlayer()

// 播放 HTTP/HTTPS 视频
player.setDataSource("https://example.com/video.mp4")

// 播放 HLS 直播流
player.setDataSource("https://example.com/live/stream.m3u8")

// 设置渲染 Surface
player.setSurface(surfaceView.holder.surface)

// 设置准备完成监听
player.setOnPreparedListener {
    player.start()
}

// 异步准备
player.prepareAsync()
```

### 错误处理

```kotlin
player.setOnErrorListener { _, what, extra ->
    when (what) {
        MEDIA_ERROR_IO -> {
            // 网络连接失败
            showToast("网络连接失败，请检查网络")
        }
        MEDIA_ERROR_TIMED_OUT -> {
            // 连接超时
            showToast("连接超时，请重试")
        }
        else -> {
            showToast("播放错误: $what")
        }
    }
    true // 返回 true 表示已处理错误
}
```

### 播放控制

```kotlin
// 播放
player.start()

// 暂停
player.pause()

// 跳转（毫秒）
player.seekTo(30000)

// 获取播放位置
val position = player.currentPosition

// 获取总时长
val duration = player.duration

// 释放资源
player.release()
```

---

## 🔧 技术实现细节

### FFmpeg 协议自动识别

```cpp
// FFmpeg 根据 URL 前缀自动选择协议
VideoState* stream_open(const char *filename, const AVInputFormat *iformat) {
    // 支持的 URL 格式：
    // - 本地路径: /sdcard/video.mp4
    // - HTTP URL: http://example.com/video.mp4
    // - HTTPS URL: https://example.com/video.mp4
    // - HLS URL: https://example.com/live/stream.m3u8
    
    avformat_open_input(&ic, filename, iformat, &format_opts);
}
```

### HLS 自适应码率

FFmpeg HLS 解封装器自动处理：
- 解析 m3u8 播放列表
- 根据网络状况选择合适码率
- 无缝切换不同清晰度
- 处理分段下载和拼接

### 网络缓冲策略

- **自适应缓冲**: 根据网络带宽动态调整缓冲区大小
- **预加载机制**: 提前加载下一个分段
- **智能丢帧**: 网络波动时优先保证流畅度而非画质