---
alwaysApply: true
---

# SkyPlayer 项目结构

SkyPlayer 是一个基于 FFmpeg 8.0 官方 ffplay 深度改造的 Android 音视频播放器，采用清晰的四层架构设计，从解封装、解码、同步到渲染的完整实现。

## 项目架构

```
┌─────────────────────────────────────┐
│         Java/Kotlin Layer           │
│    (SkyMediaPlayer, IMediaPlayer)   │
└─────────────────┬───────────────────┘
                  │ JNI (12 个方法)
┌─────────────────▼───────────────────┐
│           JNI Layer                 │
│    (skymediaplayer_jni.cpp)         │
└─────────────────┬───────────────────┘
                  │
┌─────────────────▼───────────────────┐
│         Native Layer                │
│  播放器核心 + 渲染器 + 音频输出       │
└─────────────────┬───────────────────┘
                  │
┌─────────────────▼───────────────────┐
│         FFmpeg Layer                │
│    ffplay 核心 + 命令行工具          │
└─────────────────────────────────────┘
```

## 主要目录结构

### 根目录
- `app/`: 示例应用模块
- `skymediaplayer/`: 核心播放器库模块（AAR）
- `doc/`: 项目文档
- `gradle/`: Gradle 配置
- 如果有新clone的仓库，clone到 /Users/uc/code/zhiwei-wu/ 目录下

### app 模块（示例应用）
- `app/src/main/java/imt/skymediaplayer/demo/`
  - `MainActivity.kt`: 主界面 Activity
  - `SkyVideoActivity.kt`: 视频播放 Activity

### skymediaplayer 模块（核心播放器库）

#### Java/Kotlin 层
- `skymediaplayer/src/main/java/imt/zw/skymediaplayer/`
  - `player/`: 播放器核心接口和实现
    - `IMediaPlayer.kt`: 播放器接口定义
    - `SkyMediaPlayer.kt`: 播放器实现类
  - `audio/`: 音频相关工具
    - `AudioFocusManager.kt`: 音频焦点管理
  - `widget/`: UI 组件
    - `SkyVideoView.kt`: 视频播放视图
    - `SurfaceRenderView.kt`: Surface 渲染视图
    - `VideoSizeCalculator.kt`: 视频尺寸计算
  - `utils/`: 工具类

#### Native 层
- `skymediaplayer/src/main/cpp/`
  - `skymediaplayer_jni.cpp`: JNI 桥接层（12 个方法）
  - `player/`: 播放器核心
    - `skymediaplayer.h/cpp`: 播放器封装
    - `skyaudio.h/cpp`: 音频输出（OpenSL ES）
    - `sky_msg_queue.h/cpp`: 消息队列
    - `renderer/`: 渲染器模块
      - `skyrenderer.h/cpp`: 渲染器管理（工厂模式）
      - `sky_egl2_renderer_*.h/cpp`: 5 种像素格式 OpenGL ES 渲染器
      - `sky_vk_renderer.h/cpp`: Vulkan 渲染器
      - `sky_vk_shaders.h`: 预编译 SPIR-V Shader
    - `decoder/`: 解码器模块
      - `sky_hw_decoder.h/cpp`: 硬件解码器抽象基类
      - `sky_mediacodec_decoder.h/cpp`: Android MediaCodec 解码器实现
  - `ffplay/`: FFmpeg ffplay 核心
    - `ffplay.c`: ffplay 核心引擎（128KB）
    - `cmdutils.c/h`: 命令行工具
    - `opt_common.c/h`: 选项处理
  - `ffmpeg/include/`: FFmpeg 库头文件
  - `sdl/include/`: SDL 适配层
  - `include/`: 公共头文件

## 关键文件

- [build.gradle.kts](mdc:build.gradle.kts): 根级构建配置
- [settings.gradle.kts](mdc:settings.gradle.kts): 项目设置
- [app/build.gradle.kts](mdc:app/build.gradle.kts): 示例应用构建配置
- [skymediaplayer/build.gradle.kts](mdc:skymediaplayer/build.gradle.kts): 核心库构建配置
- [skymediaplayer/CMakeLists.txt](mdc:skymediaplayer/CMakeLists.txt): CMake 构建配置
- [gradle/libs.versions.toml](mdc:gradle/libs.versions.toml): 依赖版本管理
- [README.md](mdc:README.md): 项目说明文档

## 构建和运行

### 环境要求
- Android Studio
- NDK（支持 CMake 3.22.1）
- Gradle 8.8.0+
- JDK 11+

### 构建命令
```bash
# 构建项目
./gradlew build

# 构建示例应用
./gradlew :app:assembleDebug

# 构建核心库
./gradlew :skymediaplayer:assembleRelease

# 发布到 Maven Local
./gradlew :skymediaplayer:publishReleasePublicationToMavenLocal
```

### 支持的架构
- arm64-v8a（仅支持 64 位 ARM）

## 技术栈

- **语言**: Kotlin 1.9.24 + Java 11 + C/C++
- **构建工具**: Gradle (Kotlin DSL) + CMake 3.22.1
- **多媒体引擎**: FFmpeg 8.0
- **视频渲染**: OpenGL ES 2.0
- **音频输出**: OpenSL ES
- **SDK**: compileSdk 35, minSdk 30, targetSdk 35
