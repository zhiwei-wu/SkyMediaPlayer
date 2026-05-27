# SkyPlayer 播放器整体架构

## 架构概览

SkyPlayer 采用**四层架构**设计，从上到下依次为：

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

## 各层详解

### 1. Java/Kotlin 层

**文件位置**: `skymediaplayer/src/main/java/imt/zw/skymediaplayer/player/`

**核心类**：
- `IMediaPlayer.kt`（80 行）：播放器接口定义，兼容 Android MediaPlayer API
- `SkyMediaPlayer.kt`（565 行）：播放器实现类

**职责**：
- 提供标准的 Android MediaPlayer API 接口
- 管理播放器生命周期和状态（9 种状态）
- 处理用户交互和事件回调
- 通过 Handler 实现 UI 线程事件分发

**核心接口**：
```kotlin
interface IMediaPlayer {
    fun setDataSource(path: String)
    fun prepareAsync()
    fun start()
    fun pause()
    fun stop()
    fun seekTo(msec: Long)
    fun release()
    
    // 8 个监听器接口
    fun setOnPreparedListener(listener: OnPreparedListener?)
    fun setOnErrorListener(listener: OnErrorListener?)
    fun setOnCompletionListener(listener: OnCompletionListener?)
    // ...
}
```

**事件处理机制**：
```kotlin
// Native 层回调入口
@JvmStatic
private fun postEventFromNative(player: SkyMediaPlayer?, what: Int, arg1: Int, arg2: Int, obj: Any?) {
    player?._eventHandler?.sendMessage(Message.obtain(player._eventHandler, what, arg1, arg2, obj))
}

// 事件常量（与 Native 层对应）
private const val MEDIA_PREPARED = 1
private const val MEDIA_PLAYBACK_COMPLETE = 2
private const val MEDIA_ERROR = 100
private const val MEDIA_INFO = 200
```

### 2. JNI 层

**文件位置**: `skymediaplayer/src/main/cpp/skymediaplayer_jni.cpp`（390 行）

**职责**：
- 桥接 Java 和 Native 代码
- 管理线程安全的 JNI 引用
- 使用 TLS 自动管理 JNIEnv 的 attach/detach
- 缓存 Java 方法 ID 提高性能

**12 个 JNI 方法**：
```cpp
static JNINativeMethod methods[] = {
    {"_native_setup", "()V", (void *) sky_mediaPlayer_native_setup},
    {"_setDataSource", "(Ljava/lang/String;)V", (void *) sky_mediaPlayer_setDataSource},
    {"_prepareAsync", "()V", (void *) sky_mediaPlayer_prepareAsync},
    {"_start", "()V", (void *) sky_mediaPlayer_start},
    {"_pause", "()V", (void *) sky_mediaPlayer_pause},
    {"_stop", "()V", (void *) sky_mediaPlayer_stop},
    {"_seekTo", "(J)V", (void *) sky_mediaPlayer_seekTo},
    {"_release", "()V", (void *) sky_mediaPlayer_release},
    {"_setSurface", "(Landroid/view/Surface;)V", (void *) sky_mediaPlayer_setSurface},
    {"_setAudioFilter", "(Ljava/lang/String;)I", (void *) sky_mediaPlayer_setAudioFilter},
    {"_setWhisperPrebufferMode", "(Z)Z", (void *) sky_mediaPlayer_setWhisperPrebufferMode},
    {"_getCurrentPosition", "()J", (void *) sky_mediaPlayer_getCurrentPosition},
};
```

**线程安全设计**：
```cpp
// 线程本地存储 key
static pthread_key_t g_env_key;

// 获取 JNIEnv（自动 attach）
JNIEnv* getJNIEnv() {
    auto* env = static_cast<JNIEnv*>(pthread_getspecific(g_env_key));
    if (!env) {
        g_jvm->AttachCurrentThread(&env, nullptr);
        pthread_setspecific(g_env_key, env);
    }
    return env;
}

// 线程退出时自动 detach
static void thread_destructor(void* env) {
    if (env) {
        g_jvm->DetachCurrentThread();
    }
}
```

### 3. Native 层

**文件位置**: `skymediaplayer/src/main/cpp/player/`

**核心文件**：
- `skymediaplayer.h`（307 行）/ `skymediaplayer.cpp`（798 行）：播放器核心
- `skyrenderer.h/cpp`：渲染器管理
- `skyaudio.h/cpp`：音频输出（OpenSL ES）
- `sky_msg_queue.h/cpp`：消息队列

**SkyPlayer 类职责**：
- 状态管理：9 种播放状态（`PlayerState` 枚举）
- 资源管理：`SkyVideoOutHandler`、`SkyAudioOutHandler`
- 消息队列：`SkyMessageQueue` + `handleMessage()` 回调
- JNI 通信：`SkyMediaPlayerMethod` 缓存 Java 方法 ID
- FFmpeg 交互：`VideoState* is` 指向 ffplay 核心结构

**状态转换**：
```cpp
enum PlayerState {
    STATE_IDLE,
    STATE_INITIALIZED,
    STATE_PREPARING,
    STATE_PREPARED,
    STATE_STARTED,
    STATE_PAUSED,
    STATE_STOPPED,
    STATE_COMPLETED,
    STATE_ERROR
};

void SkyPlayer::setPlayerState(PlayerState state) {
    bool stateChanged = playerState != state;
    playerState = state;
    if (stateChanged) {
        onPlaybackStateChanged(state);
    }
}
```

### 4. FFmpeg 层

**文件位置**: `skymediaplayer/src/main/cpp/ffplay/ffplay.c`（128KB+）

**职责**：
- 基于官方 ffplay 深度改造
- 实现解封装、解码、音画同步
- 提供流媒体播放核心能力

**核心结构体 VideoState**：
```c
typedef struct VideoState {
    // 解码器
    Decoder auddec;     // 音频解码器
    Decoder viddec;     // 视频解码器
    Decoder subdec;     // 字幕解码器
    
    // 帧队列
    FrameQueue pictq;   // 视频帧队列
    FrameQueue sampq;   // 音频帧队列
    FrameQueue subpq;   // 字幕帧队列
    
    // 时钟
    Clock audclk;       // 音频时钟
    Clock vidclk;       // 视频时钟
    Clock extclk;       // 外部时钟
    
    // SkyPlayer 回调
    void *skyPlayer;    // 指向 Native 层 SkyPlayer
} VideoState;
```

## 消息队列机制

### 消息定义

**文件位置**: `skymediaplayer/src/main/cpp/include/sky_messages.h`（71 行）

```cpp
// 基础控制消息
#define SKY_MSG_FLUSH                   0

// 错误和状态消息
#define SKY_MSG_ERROR                   100
#define SKY_MSG_PREPARED                200
#define SKY_MSG_COMPLETED               300

// 视频相关消息
#define SKY_MSG_VIDEO_SIZE_CHANGED      400
#define SKY_MSG_VIDEO_DECODED_START     406
#define SKY_MSG_OPEN_INPUT              407
#define SKY_MSG_COMPONENT_OPEN          409

// 缓冲相关消息
#define SKY_MSG_BUFFERING_START         500
#define SKY_MSG_BUFFERING_UPDATE        502

// Seek 和播放消息
#define SKY_MSG_SEEK_COMPLETE           600
#define SKY_MSG_PLAYBACK_STATE_CHANGED  700

// Whisper 字幕消息
#define SKY_MSG_WHISPER_SUBTITLE        30001
#define SKY_MSG_WHISPER_PREBUFFER_COMPLETE 30002
```

### 消息流转

```
FFmpeg 层 (产生事件)
    ↓ 回调
Native 层 (SkyMessageQueue)
    ↓ 消息传递
JNI 层 (postEventToJava)
    ↓ 静态方法
Java 层 (Handler + MediaEventHandler)
    ↓ 回调
应用层 (监听器接口)
```

### 消息处理

```cpp
void SkyPlayer::handleMessage(const SkyMessage& message) {
    switch (message.what) {
        case SKY_MSG_PREPARED:
            postMediaEventToJava(MEDIA_EVENT_TYPE::MEDIA_PREPARED);
            break;
        case SKY_MSG_ERROR:
            postMediaEventToJava(MEDIA_EVENT_TYPE::MEDIA_ERROR, message.arg1, message.arg2);
            break;
        case SKY_MSG_VIDEO_SIZE_CHANGED:
            postMediaEventToJava(MEDIA_EVENT_TYPE::MEDIA_VIDEO_SIZE_CHANGED, 
                                 message.arg1, message.arg2);
            break;
        // ... 更多消息类型
    }
}
```

## 线程模型

### 多线程架构

1. **主线程（UI 线程）**：
   - Java 层运行在主线程
   - `MediaEventHandler` 在主线程处理事件回调

2. **JNI 线程管理**：
   - 使用 TLS 自动管理 JNIEnv
   - 任何线程调用 Native 方法都会自动 attach 到 JVM
   - 线程退出时自动 detach

3. **FFmpeg 线程**：
   - **读取线程**: `read_thread()` - 读取数据包
   - **视频解码线程**: `video_thread()` - 解码视频帧
   - **音频解码线程**: `audio_thread()` - 解码音频帧
   - **渲染线程**: 音画同步和渲染

4. **消息队列线程**：
   - `SkyMessageQueue` 运行在独立线程
   - 异步处理 FFmpeg 层的事件

### 线程同步

```cpp
// 互斥锁保护共享资源
std::mutex mtx;

// 原子变量防止重复清理
std::atomic<bool> isDestroyed_;

// 条件变量用于线程间通信
std::condition_variable cv;
```

## 关键调用路径

### 播放控制路径

```
Java: start()
  → _start() (JNI)
  → sky_mediaPlayer_start()
  → player->start()
  → SkyPlayer::start()
  → toggle_pause(is) (ffplay)
```

### 事件回调路径

```
FFmpeg: 产生事件（如解码完成）
  → postMessage(SKY_MSG_VIDEO_DECODED_START)
  → SkyMessageQueue
  → handleMessage()
  → postEventToJava()
  → env->CallStaticVoidMethod()
  → postEventFromNative()
  → MediaEventHandler
  → OnInfoListener
```

## 文件路径汇总

| 层级 | 文件路径 | 行数 | 职责 |
|------|----------|------|------|
| Java | `player/IMediaPlayer.kt` | 80 | 播放器接口定义 |
| Java | `player/SkyMediaPlayer.kt` | 565 | 播放器实现 |
| JNI | `skymediaplayer_jni.cpp` | 390 | JNI 桥接层 |
| Native | `player/skymediaplayer.h` | 307 | Native 头文件 |
| Native | `player/skymediaplayer.cpp` | 798 | Native 实现 |
| Native | `include/sky_messages.h` | 71 | 消息定义 |
| FFmpeg | `ffplay/ffplay.c` | 128KB+ | FFmpeg 核心 |

## 扩展开发指南

### 添加新的 JNI 方法

1. 在 `IMediaPlayer.kt` 中定义接口方法
2. 在 `SkyMediaPlayer.kt` 中声明 `external` 方法并实现
3. 在 `skymediaplayer_jni.cpp` 中实现 JNI 函数并注册
4. 在 `SkyPlayer` 类中实现具体逻辑

### 添加新的消息类型

1. 在 `sky_messages.h` 中定义消息常量
2. 在 `SkyPlayer::handleMessage()` 中处理消息
3. 在 `SkyMediaPlayer.kt` 中定义对应的 Java 常量
4. 在 `MediaEventHandler` 中处理事件
