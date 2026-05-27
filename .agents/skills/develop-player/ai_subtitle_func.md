# SkyPlayer AI 字幕功能

## 概述

SkyPlayer 集成了 Whisper AI 语音识别，实现实时字幕生成。采用**独立解码流 + 异步处理**架构，在不影响主音频播放的情况下进行语音识别，并通过 PTS 时间戳实现精确的音画同步。

## 架构设计

```
┌─────────────────────────────────────────────────────────────────┐
│                    Java/Kotlin Layer                            │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ SkyVideoActivity                                         │   │
│  │  - 预缓冲 UI 管理                                         │   │
│  │  - PTS 轮询同步 (100ms)                                   │   │
│  │  - 字幕显示控制                                           │   │
│  └─────────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ SkyMediaPlayer                                           │   │
│  │  - _subtitleQueue: List<SubtitleData>                    │   │
│  │  - getCurrentSubtitle(): 根据播放位置匹配字幕             │   │
│  │  - OnSubtitleWithPtsListener                             │   │
│  │  - OnPrebufferCompleteListener                           │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                              │ JNI
┌─────────────────────────────▼───────────────────────────────────┐
│                    Native Layer                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ skymediaplayer.cpp                                       │   │
│  │  - sky_post_whisper_subtitle_with_pts()                  │   │
│  │  - sky_post_whisper_prebuffer_complete()                 │   │
│  │  - SKY_MSG_WHISPER_SUBTITLE 消息处理                      │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────▼───────────────────────────────────┐
│                    FFplay Layer (ffplay.c)                       │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ 独立 Whisper 解码流 (超前解码)                            │   │
│  │  - whisper_read_thread: 独立读取媒体文件                  │   │
│  │  - whisper_decode_thread: 独立解码音频                    │   │
│  │  - 始终超前播放位置 5-15 秒                               │   │
│  └─────────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ Whisper 处理线程                                         │   │
│  │  - whisper_thread: 从队列取帧 → Whisper 滤镜推理          │   │
│  │  - 提取字幕元数据 + PTS 时间戳                            │   │
│  │  - 发送字幕到 Native 层                                   │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                              │
┌─────────────────────────────▼───────────────────────────────────┐
│                    FFmpeg Whisper Filter                         │
│  - whisper=model=...:language=...:queue=3s:use_gpu=1            │
└─────────────────────────────────────────────────────────────────┘
```

## 核心设计特点

1. **独立解码流**：Whisper 使用独立的读取线程和解码线程，与主播放解码完全分离
2. **超前解码**：Whisper 解码流始终超前播放位置 5-15 秒，确保字幕提前生成
3. **PTS 时间戳同步**：字幕携带精确的开始/结束时间戳，UI 层通过轮询匹配显示
4. **预缓冲机制**：开启 Whisper 时暂停播放，等待首条字幕生成后再恢复
5. **队列缓冲**：字幕存入队列，根据播放位置实时匹配显示

## 字幕同步机制

### 时间戳流转

```
[Native 层] Whisper 滤镜输出
    ↓ start_time, end_time (秒, double)
[Native 层] sky_post_whisper_subtitle_with_pts()
    ↓ 转换为毫秒 (startTimeMs = start_time * 1000)
[JNI 层] postEventToJava()
    ↓ [text, startTimeMs, endTimeMs] 数组
[Java 层] SkyMediaPlayer._subtitleQueue
    ↓ SubtitleData(text, startTimeMs, endTimeMs)
[Java 层] getCurrentSubtitle()
    ↓ 匹配条件: currentPos >= startTimeMs && currentPos < endTimeMs
[UI 层] 显示字幕
```

### PTS 轮询同步 (SkyVideoActivity)

```kotlin
// 每 100ms 检查一次
subtitleSyncRunnable = object : Runnable {
    override fun run() {
        val subtitle = player.getCurrentSubtitle()
        if (subtitle != null && subtitle.text != currentSubtitleText) {
            showSubtitle(subtitle.text)
        } else if (subtitle == null) {
            hideSubtitle()
        }
        subtitleHandler.postDelayed(this, 100)
    }
}
```

### 字幕匹配逻辑 (SkyMediaPlayer)

```kotlin
fun getCurrentSubtitle(): SubtitleData? {
    val currentPos = getCurrentPosition()  // 毫秒
    synchronized(_subtitleQueue) {
        return _subtitleQueue.find { subtitle ->
            currentPos >= subtitle.startTimeMs && currentPos < subtitle.endTimeMs
        }
    }
}
```

## 消息定义

**文件位置**: `skymediaplayer/src/main/cpp/include/sky_messages.h`

```cpp
#define SKY_MSG_WHISPER_SUBTITLE           30001  // 字幕消息
#define SKY_MSG_WHISPER_PREBUFFER_COMPLETE 30002  // 预缓冲完成消息
```

## 关键文件和函数

### Java/Kotlin 层

| 文件 | 功能 |
|------|------|
| `SkyMediaPlayer.kt` | 字幕队列管理、监听器接口、getCurrentSubtitle() |
| `SkyVideoActivity.kt` | 预缓冲 UI、PTS 轮询同步、字幕显示 |
| `SkyVideoView.kt` | 播放器视图封装（注意：不再使用实时回调更新字幕） |

### Native 层

| 文件 | 功能 |
|------|------|
| `skymediaplayer.cpp` | sky_post_whisper_subtitle_with_pts()、消息处理 |
| `skymediaplayer_interface.h` | 接口声明 |

### FFplay 层

| 函数 | 位置 | 功能 |
|------|------|------|
| `set_audio_filters()` | 行 4770-4820 | 设置音频滤镜，检测 whisper 滤镜时直接启动线程 |
| `start_whisper_thread()` | 行 2540-2560 | 启动 Whisper 处理线程 |
| `whisper_thread()` | 行 2350-2530 | Whisper 处理主循环，提取字幕 |
| `start_whisper_decode_stream()` | 行 2230-2300 | 启动独立解码流 |
| `whisper_read_thread()` | 行 2000-2190 | 独立读取线程，超前读取音频包 |
| `stop_whisper_decode_stream()` | 行 2200-2230 | 停止独立解码流 |
| `whisper_stream_seek()` | 行 2310-2340 | Whisper 解码流 Seek 同步 |

## VideoState 中的 Whisper 字段

```c
struct VideoState {
    // ========== 异步 Whisper 处理 ==========
    SDL_Thread *whisper_tid;              // Whisper 处理线程
    int whisper_abort;                    // 线程退出标志
    AVFilterGraph *whisper_agraph;        // Whisper 专用滤镜图
    AVFilterContext *whisper_in_filter;   // 输入滤镜
    AVFilterContext *whisper_out_filter;  // 输出滤镜
    AVFifo *whisper_frame_queue;          // 音频帧队列
    SDL_Mutex *whisper_mutex;             // 互斥锁
    SDL_Condition *whisper_cond;          // 条件变量
    struct AudioParams whisper_filter_src; // 滤镜源参数
    
    // ========== 独立 Whisper 解码流 ==========
    SDL_Thread *whisper_read_tid;         // 独立读取线程
    SDL_Thread *whisper_decode_tid;       // 独立解码线程
    AVFormatContext *whisper_ic;          // 独立的格式上下文
    AVCodecContext *whisper_avctx;        // 独立的解码器上下文
    PacketQueue whisper_audioq;           // 独立的音频包队列
    SDL_Condition *whisper_read_cond;     // 读取线程条件变量
    
    int whisper_audio_stream;             // 音频流索引
    int64_t whisper_decode_pts;           // 当前解码位置
    double whisper_lead_time;             // 目标超前时间 (10s)
    double whisper_min_lead_time;         // 最小超前时间 (5s)
    double whisper_max_lead_time;         // 最大超前时间 (15s)
    
    int whisper_seek_req;                 // Seek 请求标志
    int64_t whisper_seek_pos;             // Seek 目标位置
    int whisper_seek_flags;               // Seek 标志
    
    int whisper_read_abort;               // 读取线程退出标志
    int whisper_decode_abort;             // 解码线程退出标志
    int whisper_eof;                      // 文件结束标志
    int whisper_enabled;                  // Whisper 启用标志
};
```

## 开启 Whisper 流程

```
1. 用户点击 AI 字幕开关
   ↓
2. SkyVideoActivity.onSubtitleToggle(true)
   ↓
3. 暂停视频播放 (mSkyVideoView.pause())
   ↓
4. 显示预缓冲 UI (showPrebufferUI())
   ↓
5. 设置预缓冲完成监听器
   ↓
6. 调用 setWhisperEnabled(true, modelPath, "en")
   ↓
7. [Native] set_audio_filters() 设置 whisper 滤镜
   ↓
8. [Native] 检测到 whisper 滤镜，直接调用 start_whisper_thread()
   ↓
9. [Native] whisper_thread() 启动 → start_whisper_decode_stream()
   ↓
10. [Native] 独立解码流开始超前解码音频
    ↓
11. [Native] Whisper 滤镜处理音频 → 生成字幕
    ↓
12. [Native] sky_post_whisper_subtitle_with_pts() 发送字幕
    ↓
13. [Native] 首条字幕生成后，发送 SKY_MSG_WHISPER_PREBUFFER_COMPLETE
    ↓
14. [Java] OnPrebufferCompleteListener.onPrebufferComplete()
    ↓
15. 隐藏预缓冲 UI，启动 PTS 轮询同步，恢复播放
```

## 关键设计决策

### 1. 为什么使用 PTS 轮询而不是实时回调？

由于 Whisper 独立解码流是**超前解码**的，字幕生成时间早于实际播放时间。如果使用实时回调直接显示字幕，会导致字幕提前显示，与画面不同步。

**解决方案**：
- 字幕生成后存入队列，携带 PTS 时间戳
- UI 层每 100ms 轮询，根据当前播放位置匹配字幕
- 只有当 `currentPos >= startTimeMs && currentPos < endTimeMs` 时才显示

### 2. 为什么在 set_audio_filters() 中直接启动 Whisper 线程？

**问题**：原来的设计是在 `audio_thread` 中检测 `audio_filter_changed` 标志后启动 Whisper 线程。但当视频暂停时，`audio_thread` 中的 `decoder_decode_frame` 会阻塞等待，导致 Whisper 线程永远不会被启动。

**解决方案**：在 `set_audio_filters()` 函数中，如果检测到是 whisper 滤镜，直接调用 `start_whisper_thread()`，确保即使视频暂停也能立即启动 Whisper。

```c
// set_audio_filters() 中的关键代码
if (filters && contains_whisper_filter(filters)) {
    if (!is->whisper_tid) {
        start_whisper_thread(is);
    }
}
```

### 3. 独立解码流的超前量控制

```c
is->whisper_lead_time = 10.0;      // 目标超前 10 秒
is->whisper_min_lead_time = 5.0;   // 最小超前 5 秒
is->whisper_max_lead_time = 15.0;  // 最大超前 15 秒
```

- 当超前量 > 15 秒时，暂停读取，等待播放追上
- 当超前量 < 5 秒时，加速读取
- 目标保持 10 秒的超前量

### 4. 跳帧策略

当 Whisper 处理队列积压过多时，会跳过旧帧，只处理最新的音频：

```c
const size_t SKIP_THRESHOLD = 130;  // 约 3 秒的音频帧

if (queue_size > SKIP_THRESHOLD) {
    size_t frames_to_skip = queue_size - SKIP_THRESHOLD;
    // 跳过旧帧...
}
```

## 使用示例

### 启用 Whisper 字幕

```kotlin
// 获取模型路径
val modelPath = app.getWhisperModelPath()

// 暂停视频
mSkyVideoView.pause()

// 显示预缓冲 UI
showPrebufferUI()

// 设置预缓冲完成监听器
player.setOnPrebufferCompleteListener { mp, count ->
    hidePrebufferUI()
    startSubtitleSync()
    player.start()
}

// 启用 Whisper
mSkyVideoView.setWhisperEnabled(true, modelPath, "en")
```

### 禁用 Whisper 字幕

```kotlin
stopSubtitleSync()
hideSubtitle()
mSkyVideoView.setWhisperEnabled(false)
```

## 注意事项

1. **SkyVideoView 不再使用实时回调**：字幕显示由 `SkyVideoActivity` 的 PTS 轮询机制控制
2. **预缓冲期间视频暂停**：确保首条字幕生成后再恢复播放
3. **Seek 同步**：主播放器 Seek 时需要同步 Whisper 解码流位置
4. **资源释放**：关闭 Whisper 时需要停止独立解码流和处理线程

---

## 字幕设置面板

### SubtitleSettings 数据类

**文件**: `skymediaplayer/src/main/java/imt/zw/skymediaplayer/widget/control/SubtitleSettings.kt`

```kotlin
data class SubtitleSettings(
    val enabled: Boolean = false,
    val inferenceDevice: InferenceDevice = InferenceDevice.CPU,
    val targetLanguage: TargetLanguage = TargetLanguage.ORIGINAL,
    val processingInterval: Int = 10,  // 处理间隔（秒），范围 3-20
    val debugMode: Boolean = false      // 调试模式开关
) {
    companion object {
        val DEFAULT = SubtitleSettings()
        const val MIN_PROCESSING_INTERVAL = 3
        const val MAX_PROCESSING_INTERVAL = 20
        const val DEFAULT_PROCESSING_INTERVAL = 10
    }
}
```

### SkySubtitleSettingsPanel UI

**文件**: `skymediaplayer/src/main/java/imt/zw/skymediaplayer/widget/control/SkySubtitleSettingsPanel.kt`

新增 UI 组件：
- **处理间隔 SeekBar**：拖动调整 Whisper 处理间隔（3-20 秒）
- **调试模式 Switch**：开启后显示时间调试信息

### 动态 queue 参数

**SkyMediaPlayer.kt** 的 `setWhisperEnabled` 方法支持动态设置 queue 参数：

```kotlin
fun setWhisperEnabled(
    enabled: Boolean,
    modelPath: String? = null,
    language: String = "zh",
    queueSeconds: Int = 10  // 处理间隔参数
): Int {
    if (enabled) {
        val validQueueSeconds = queueSeconds.coerceIn(3, 20)
        val filter = "whisper=model=$modelPath:language=$language:queue=${validQueueSeconds}s:use_gpu=1"
        return setAudioFilter(filter)
    } else {
        return setAudioFilter(null)
    }
}
```

---

## 字幕输出控制（时间窗口同步）

### 设计目标

实现精确的字幕时间同步，确保字幕在正确的时间展示，避免过早或过晚显示。

### 时间窗口算法

```
当收到字幕回调时 (onSubtitle):
├── 获取当前播放位置 currentPosMs
├── 计算时间窗口:
│   ├── windowStart = startTimeMs - halfIntervalMs
│   ├── windowEnd = startTimeMs + halfIntervalMs
│   └── discardThreshold = startTimeMs + intervalMs
│
└── 判断条件:
    ├── 1. 丢弃条件: discardThreshold < currentPosMs
    │   └── 字幕太旧，直接丢弃
    │
    ├── 2. 立即展示: currentPosMs in [windowStart, windowEnd]
    │   └── 主时钟在时间窗口内，立即显示
    │
    ├── 3. 等待条件: startTimeMs > currentPosMs + halfIntervalMs
    │   ├── 将字幕加入等待队列 pendingSubtitleQueue
    │   ├── 按 startTimeMs 排序队列
    │   └── 启动字幕队列检查器（每 100ms 检查一次）
    │
    └── 4. 其他情况: 直接显示
```

### 时间窗口判断规则

| 条件 | 判断公式 | 处理方式 |
|------|----------|----------|
| 立即展示 | `currentPos ∈ [startTime - interval/2, startTime + interval/2]` | 直接显示 |
| 丢弃 | `startTime + interval < currentPos` | 字幕太旧，丢弃 |
| 等待 | `startTime > currentPos + interval/2` | 加入队列，等待展示 |

### 字幕等待队列

**文件**: `app/src/main/java/imt/skymediaplayer/demo/SkyVideoActivity.kt`

```kotlin
// 待展示的字幕队列（按 startTimeMs 排序）
private data class PendingSubtitle(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long
)
private val pendingSubtitleQueue = mutableListOf<PendingSubtitle>()
private var subtitleCheckRunnable: Runnable? = null
private val SUBTITLE_CHECK_INTERVAL_MS = 100L  // 每 100ms 检查一次
```

### 字幕队列检查器

```kotlin
private fun startSubtitleQueueChecker(player: IMediaPlayer) {
    subtitleCheckRunnable = object : Runnable {
        override fun run() {
            synchronized(pendingSubtitleQueue) {
                val iterator = pendingSubtitleQueue.iterator()
                while (iterator.hasNext()) {
                    val subtitle = iterator.next()
                    when {
                        // 字幕已过期，丢弃
                        discardThreshold < currentPosMs -> iterator.remove()
                        // 字幕到达展示时间窗口，展示并移除
                        currentPosMs in windowStart..windowEnd -> {
                            displaySubtitle(subtitle.text, subtitle.startTimeMs, currentPosMs)
                            iterator.remove()
                        }
                    }
                }
            }
            // 继续下一次检查
            subtitleHandler.postDelayed(this, SUBTITLE_CHECK_INTERVAL_MS)
        }
    }
}
```

### 调试模式

开启调试模式后，字幕显示格式为：
```
[33.0s | 30.5s | 2.5s] Hello, this is the subtitle text.
```
- `33.0s`: 字幕时间戳
- `30.5s`: 当前播放位置
- `2.5s`: 延迟（字幕时间 - 播放时间）

```kotlin
private fun displaySubtitle(text: String, startTimeMs: Long, currentPosMs: Long) {
    val displayText = if (isSubtitleDebugMode) {
        String.format("[%.1fs | %.1fs | %.1fs] %s",
            startTimeSec, currentPosSec, delaySec, text)
    } else {
        text
    }
    mSkyVideoView.setSubtitleText(displayText)
}
```

### 关键改进

- **字幕不会丢失**：所有超前的字幕都会被保存到队列中
- **按时间排序**：队列按 `startTimeMs` 排序，确保先到期的字幕先展示
- **自动清理**：过期字幕会被自动丢弃，队列为空时检查器自动停止
- **资源管理**：停止字幕同步时会清空队列和停止检查器
