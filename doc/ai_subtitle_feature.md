# SkyPlayer AI 字幕功能开发文档

## 📋 功能概述

本次开发为 SkyPlayer 的 AI 字幕功能添加了以下增强特性：

1. **处理间隔控制**：通过 SeekBar 拖动控制 Whisper 推理时间，范围 3-20 秒，默认 10 秒
2. **调试信息开关**：可选择是否显示字幕时间调试信息
3. **字幕输出控制优化**：实现时间窗口同步算法，确保字幕按时展示

---

## 🎯 核心变更

### 1. SubtitleSettings 数据类

**文件**: `skymediaplayer/src/main/java/imt/zw/skymediaplayer/widget/control/SubtitleSettings.kt`

新增字段：
```kotlin
data class SubtitleSettings(
    val enabled: Boolean = false,
    val inferenceDevice: InferenceDevice = InferenceDevice.CPU,
    val targetLanguage: TargetLanguage = TargetLanguage.ORIGINAL,
    val processingInterval: Int = 10,  // 新增：处理间隔（秒）
    val debugMode: Boolean = false      // 新增：调试模式开关
) {
    companion object {
        val DEFAULT = SubtitleSettings()
        const val MIN_PROCESSING_INTERVAL = 3
        const val MAX_PROCESSING_INTERVAL = 20
        const val DEFAULT_PROCESSING_INTERVAL = 10
    }
}
```

---

### 2. SkySubtitleSettingsPanel UI

**文件**: `skymediaplayer/src/main/java/imt/zw/skymediaplayer/widget/control/SkySubtitleSettingsPanel.kt`

新增 UI 组件：
- **处理间隔 SeekBar**：拖动调整 Whisper 处理间隔（3-20 秒）
- **调试模式 Switch**：开启后显示时间调试信息

```kotlin
// 处理间隔 SeekBar
private lateinit var intervalSeekBar: SeekBar
private lateinit var intervalValueText: TextView

// 调试模式开关
private lateinit var debugSwitch: Switch
```

---

### 3. SkyMediaPlayer 动态 queue 参数

**文件**: `skymediaplayer/src/main/java/imt/zw/skymediaplayer/player/SkyMediaPlayer.kt`

`setWhisperEnabled` 方法新增 `queueSeconds` 参数：

```kotlin
fun setWhisperEnabled(
    enabled: Boolean,
    modelPath: String? = null,
    language: String = "zh",
    queueSeconds: Int = 10  // 新增：处理间隔参数
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

### 4. SkyVideoView 透传参数

**文件**: `skymediaplayer/src/main/java/imt/zw/skymediaplayer/widget/SkyVideoView.kt`

```kotlin
fun setWhisperEnabled(
    enabled: Boolean,
    modelPath: String? = null,
    language: String = "zh",
    queueSeconds: Int = 10  // 新增：透传处理间隔参数
): Int
```

---

### 5. SkyVideoActivity 字幕输出控制

**文件**: `app/src/main/java/imt/skymediaplayer/demo/SkyVideoActivity.kt`

#### 5.1 字幕等待队列

使用队列保存所有超前的字幕，避免字幕丢失：

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

#### 5.2 字幕输出控制逻辑

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

#### 5.3 字幕队列检查器

```kotlin
private fun startSubtitleQueueChecker(player: IMediaPlayer) {
    subtitleCheckRunnable = object : Runnable {
        override fun run() {
            // 遍历队列，处理到期的字幕
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

#### 5.4 调试模式显示

```kotlin
private fun displaySubtitle(text: String, startTimeMs: Long, currentPosMs: Long) {
    val displayText = if (isSubtitleDebugMode) {
        // 调试模式：显示时间信息
        // 格式：[字幕时间 | 播放时间 | 延迟] 字幕内容
        String.format("[%.1fs | %.1fs | %.1fs] %s",
            startTimeSec, currentPosSec, delaySec, text)
    } else {
        // 正常模式：只显示字幕
        text
    }
    mSkyVideoView.setSubtitleText(displayText)
}
```

---

## 📊 技术特性

### 时间窗口同步算法

| 条件 | 判断公式 | 处理方式 |
|------|----------|----------|
| 立即展示 | `currentPos ∈ [startTime - interval/2, startTime + interval/2]` | 直接显示 |
| 丢弃 | `startTime + interval < currentPos` | 字幕太旧，丢弃 |
| 等待 | `startTime > currentPos + interval/2` | 加入队列，等待展示 |

### 关键改进

- **字幕不会丢失**：所有超前的字幕都会被保存到队列中
- **按时间排序**：队列按 `startTimeMs` 排序，确保先到期的字幕先展示
- **自动清理**：过期字幕会被自动丢弃，队列为空时检查器自动停止
- **资源管理**：停止字幕同步时会清空队列和停止检查器

---

## 🔧 使用方式

### 设置处理间隔

```kotlin
// 通过 SubtitleSettings 设置
val settings = SubtitleSettings(
    enabled = true,
    processingInterval = 15,  // 15 秒处理间隔
    debugMode = true          // 开启调试模式
)
skyVideoView.setSubtitleSettings(settings)
```

### 调试模式输出格式

开启调试模式后，字幕显示格式为：
```
[33.0s | 30.5s | 2.5s] Hello, this is the subtitle text.
```
- `33.0s`: 字幕时间戳
- `30.5s`: 当前播放位置
- `2.5s`: 延迟（字幕时间 - 播放时间）

---

## 📁 修改文件清单

| 文件 | 修改内容 |
|------|----------|
| `SubtitleSettings.kt` | 添加 `processingInterval` 和 `debugMode` 字段 |
| `SkySubtitleSettingsPanel.kt` | 添加处理间隔 SeekBar 和调试开关 UI |
| `SkyMediaPlayer.kt` | `setWhisperEnabled` 添加 `queueSeconds` 参数 |
| `SkyVideoView.kt` | 透传 `queueSeconds` 参数 |
| `SkyVideoActivity.kt` | 实现字幕队列和时间窗口同步算法 |

---

## 🚀 后续优化方向

1. **Seek 处理**：Seek 时清空字幕队列，避免显示错误时间的字幕
2. **暂停处理**：暂停时停止字幕检查器，恢复时重新启动
3. **网络延迟补偿**：根据网络状况动态调整时间窗口
4. **字幕缓存**：缓存已显示的字幕，支持回看
