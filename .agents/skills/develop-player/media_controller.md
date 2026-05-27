# SkyPlayer 播控 UI 架构

## 概述

SkyPlayer 的播控 UI 采用**分层设计**，将视频渲染、播放控制、尺寸适配等功能解耦。

## 架构层次

```
┌─────────────────────────────────────┐
│      SkyVideoActivity (业务层)       │
│  - 生命周期管理                       │
│  - 全屏沉浸式模式                     │
│  - Whisper 字幕控制                  │
└─────────────────┬───────────────────┘
                  │
┌─────────────────▼───────────────────┐
│      SkyVideoView (展示层)           │
│  - 封装播放器和 UI 组件               │
│  - 实现 MediaPlayerControl 接口      │
│  - 音频焦点管理                       │
│  - Seek 优化                         │
└─────────────────┬───────────────────┘
                  │
┌─────────────────▼───────────────────┐
│    SurfaceRenderView (渲染层)        │
│  - Surface 生命周期管理               │
│  - 视频尺寸适配                       │
└─────────────────┬───────────────────┘
                  │
┌─────────────────▼───────────────────┐
│   VideoSizeCalculator (计算层)       │
│  - 尺寸计算算法                       │
│  - 缩放模式实现                       │
└─────────────────────────────────────┘
```

## 核心组件

### 1. SkyVideoView

**文件位置**: `skymediaplayer/src/main/java/imt/zw/skymediaplayer/widget/SkyVideoView.kt`（603 行）

**职责**：
- 播放器管理：创建和管理 `SkyMediaPlayer` 实例
- Surface 管理：通过 `SurfaceRenderView` 管理 Surface 生命周期
- 播控桥接：实现 `MediaController.MediaPlayerControl` 接口
- 事件处理：注册并处理播放器的各种监听器
- 音频焦点管理：通过 `AudioFocusManager` 管理音频焦点
- Seek 优化：实现延迟 Seek 机制（50ms 防抖）
- Whisper AI 字幕：支持 AI 字幕功能

**核心代码**：

```kotlin
class SkyVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), MediaController.MediaPlayerControl {

    private var _mediaPlayer: SkyMediaPlayer? = null
    private var _surfaceRenderView: SurfaceRenderView? = null
    private var _mediaController: MediaController? = null
    private var _audioFocusManager: AudioFocusManager? = null
    
    // Seek 优化
    private val _seekHandler = Handler(Looper.getMainLooper())
    private var _pendingSeekRunnable: Runnable? = null
    private var _seekTargetPosition: Int = 0
    private var _isSeekInProgress: Boolean = false
    
    // 播控 → 播放器路径
    override fun start() {
        val audioFocusResult = _audioFocusManager?.requestAudioFocus()
        if (audioFocusResult == true) {
            _mediaPlayer?.start()
        }
    }
    
    override fun pause() {
        _mediaPlayer?.pause()
    }
    
    // Seek 优化：延迟 50ms 执行
    override fun seekTo(position: Int) {
        _pendingSeekRunnable?.let { _seekHandler.removeCallbacks(it) }
        _isSeekInProgress = true
        _seekTargetPosition = position
        
        _pendingSeekRunnable = Runnable {
            _mediaPlayer?.seekTo(_seekTargetPosition.toLong())
        }
        _seekHandler.postDelayed(_pendingSeekRunnable!!, 50)
    }
    
    override fun getDuration(): Int = _mediaPlayer?.duration?.toInt() ?: 0
    override fun getCurrentPosition(): Int = _mediaPlayer?.currentPosition?.toInt() ?: 0
    override fun isPlaying(): Boolean = _mediaPlayer?.isPlaying ?: false
}
```

**事件监听器**：

```kotlin
// 准备完成监听
private val _onPreparedListener = IMediaPlayer.OnPreparedListener { mp ->
    _isPrepared = true
    _onPreparedListener?.onPrepared(mp)
    
    // 自动开始播放
    if (_autoStart) {
        start()
    }
}

// 视频尺寸变化监听
private val _onVideoSizeChangedListener = IMediaPlayer.OnVideoSizeChangedListener { 
    mp, width, height, sarNum, sarDen ->
    _surfaceRenderView?.setVideoSize(width, height, sarNum, sarDen)
}

// 播放完成监听
private val _onCompletionListener = IMediaPlayer.OnCompletionListener { mp ->
    _onCompletionListener?.onCompletion(mp)
}

// 错误监听
private val _onErrorListener = IMediaPlayer.OnErrorListener { mp, what, extra ->
    _onErrorListener?.onError(mp, what, extra) ?: true
}
```

### 2. SurfaceRenderView

**文件位置**: `skymediaplayer/src/main/java/imt/zw/skymediaplayer/widget/SurfaceRenderView.kt`（163 行）

**职责**：
- 管理 SurfaceHolder
- 处理 Surface 创建和销毁
- 视频尺寸适配

**Surface 生命周期管理**：

```kotlin
class SurfaceRenderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr) {

    private var _surfaceHolder: SurfaceHolder? = null
    private var _format: Int = 0
    private var _width: Int = 0
    private var _height: Int = 0
    
    private val _sizeCalculator = VideoSizeCalculator()
    private var _videoSize = VideoSizeCalculator.VideoSize(0, 0, 1, 1)
    private var _scaleType = VideoSizeCalculator.ScaleType.AR_ASPECT_FIT_CENTER
    
    init {
        holder.addCallback(SurfaceCallback())
    }
    
    private inner class SurfaceCallback : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            _surfaceHolder = holder
            _surfaceCallback.surfaceCreated(holder)
        }
        
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            _format = format
            _width = width
            _height = height
            _surfaceCallback.surfaceChanged(holder, format, width, height)
        }
        
        override fun surfaceDestroyed(surfaceHolder: SurfaceHolder) {
            _surfaceHolder = null
            _format = 0
            _width = 0
            _height = 0
            _surfaceCallback.surfaceDestroyed(surfaceHolder)
        }
    }
    
    // 设置视频尺寸
    fun setVideoSize(width: Int, height: Int, sarNum: Int, sarDen: Int) {
        _videoSize = VideoSizeCalculator.VideoSize(width, height, sarNum, sarDen)
        requestLayout()
    }
    
    // 尺寸计算
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val containerWidth = MeasureSpec.getSize(widthMeasureSpec)
        val containerHeight = MeasureSpec.getSize(heightMeasureSpec)
        
        val containerSize = VideoSizeCalculator.Size(containerWidth, containerHeight)
        val calculatedSize = _sizeCalculator.calculateDisplaySize(
            videoSize = _videoSize,
            containerSize = containerSize,
            scaleType = _scaleType
        )
        
        setMeasuredDimension(calculatedSize.width, calculatedSize.height)
    }
}
```

### 3. VideoSizeCalculator

**文件位置**: `skymediaplayer/src/main/java/imt/zw/skymediaplayer/widget/VideoSizeCalculator.kt`（141 行）

**职责**：
- 根据视频原始尺寸、SAR 和容器尺寸计算最终显示尺寸
- 支持多种缩放模式

**数据结构**：

```kotlin
class VideoSizeCalculator {
    
    data class Size(val width: Int, val height: Int)
    
    data class VideoSize(
        val width: Int,
        val height: Int,
        val sarNum: Int,  // Sample Aspect Ratio 分子
        val sarDen: Int   // Sample Aspect Ratio 分母
    ) {
        fun getAspectRatio(): Float {
            val sarRatio = if (sarDen != 0) sarNum.toFloat() / sarDen.toFloat() else 1f
            return if (height != 0) (width.toFloat() / height.toFloat()) * sarRatio else 0f
        }
    }
    
    enum class ScaleType {
        AR_ASPECT_FIT_CENTER,    // 保持宽高比，完整显示（可能有黑边）
        AR_ASPECT_CENTER_CROP,   // 保持宽高比，填满屏幕（可能裁剪）
        AR_ASPECT_FILL_SCREEN    // 拉伸填满（可能变形）
    }
}
```

**缩放模式实现**：

```kotlin
fun calculateDisplaySize(
    videoSize: VideoSize,
    containerSize: Size,
    scaleType: ScaleType
): Size {
    if (videoSize.width <= 0 || videoSize.height <= 0) {
        return containerSize
    }
    
    val videoAspectRatio = videoSize.getAspectRatio()
    val containerAspectRatio = containerSize.width.toFloat() / containerSize.height.toFloat()
    
    return when (scaleType) {
        ScaleType.AR_ASPECT_FIT_CENTER -> {
            // 保持宽高比，完整显示
            if (videoAspectRatio > containerAspectRatio) {
                // 视频更宽：以容器宽度为准
                val width = containerSize.width
                val height = (containerSize.width / videoAspectRatio).toInt()
                Size(width, height)
            } else {
                // 视频更高：以容器高度为准
                val height = containerSize.height
                val width = (containerSize.height * videoAspectRatio).toInt()
                Size(width, height)
            }
        }
        
        ScaleType.AR_ASPECT_CENTER_CROP -> {
            // 保持宽高比，填满屏幕
            if (videoAspectRatio > containerAspectRatio) {
                // 视频更宽：以容器高度为准
                val height = containerSize.height
                val width = (containerSize.height * videoAspectRatio).toInt()
                Size(width, height)
            } else {
                // 视频更高：以容器宽度为准
                val width = containerSize.width
                val height = (containerSize.width / videoAspectRatio).toInt()
                Size(width, height)
            }
        }
        
        ScaleType.AR_ASPECT_FILL_SCREEN -> {
            // 拉伸填满
            containerSize
        }
    }
}
```

## 播放控制 UI

### 系统播控（MediaController）

```kotlin
// SkyVideoView.kt
@SuppressLint("ClickableViewAccessibility")
override fun onTouchEvent(event: MotionEvent): Boolean {
    if (_mediaController != null && event.action == MotionEvent.ACTION_DOWN) {
        if (_mediaController!!.isShowing) {
            _mediaController!!.hide()
        } else {
            _mediaController!!.show(5000)  // 5秒后自动隐藏
        }
        return true
    }
    return super.onTouchEvent(event)
}
```

### 自定义播控（SkyMediaController）

示例应用中使用自定义播控器，支持 Whisper AI 字幕开关：

```kotlin
// SkyVideoActivity.kt
mMediaController.setOnSubtitleToggleListener(object : SkyMediaController.OnSubtitleToggleListener {
    override fun onSubtitleToggle(enabled: Boolean) {
        if (enabled) {
            // 启用预缓冲模式
            player.setWhisperPrebufferMode(true)
            mSkyVideoView.setWhisperEnabled(true, modelPath, "en")
        } else {
            // 关闭 Whisper
            mSkyVideoView.setWhisperEnabled(false)
        }
    }
})
```

## Activity 生命周期管理

**文件位置**: `app/src/main/java/imt/skymediaplayer/demo/SkyVideoActivity.kt`（413 行）

### 全屏沉浸式模式

```kotlin
private fun setupFullscreenMode() {
    // 保持屏幕常亮
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    
    // 沉浸式模式
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller?.hide(WindowInsetsCompat.Type.systemBars())
    controller?.systemBarsBehavior = 
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
}
```

### 生命周期处理

```kotlin
private var wasPlayingBeforePause = false

override fun onPause() {
    super.onPause()
    // 记录播放状态
    wasPlayingBeforePause = mSkyVideoView.isPlaying
    if (wasPlayingBeforePause) {
        mSkyVideoView.pause()
    }
}

override fun onResume() {
    super.onResume()
    // 恢复播放（确保 Surface 就绪）
    if (::mSkyVideoView.isInitialized && wasPlayingBeforePause) {
        mSkyVideoView.post {
            mSkyVideoView.start()
        }
    }
}

override fun onDestroy() {
    super.onDestroy()
    mSkyVideoView.release()
}
```

## Surface 绑定时机

```kotlin
// SkyVideoView.kt
private val _surfaceCallback = object : SurfaceHolder.Callback {
    override fun surfaceCreated(holder: SurfaceHolder) {
        if (null != _mediaPlayer) {
            // 播放器已存在，直接绑定
            bindSurfaceHolder()
        } else {
            // 播放器不存在，创建并播放
            openVideo()
        }
    }
    
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // 释放渲染资源，但不释放播放器
        _mediaPlayer?.setDisplay(null)
    }
}

private fun bindSurfaceHolder() {
    _mediaPlayer?.setDisplay(_surfaceRenderView?.holder)
}

private fun openVideo() {
    _mediaPlayer = SkyMediaPlayer().apply {
        setOnPreparedListener(_onPreparedListener)
        setOnVideoSizeChangedListener(_onVideoSizeChangedListener)
        setOnCompletionListener(_onCompletionListener)
        setOnErrorListener(_onErrorListener)
        // ...
        setDataSource(_videoPath)
        setDisplay(_surfaceRenderView?.holder)
        prepareAsync()
    }
}
```

## 文件路径汇总

| 组件 | 文件路径 | 行数 | 职责 |
|------|----------|------|------|
| SkyVideoView | `widget/SkyVideoView.kt` | 603 | 视频播放视图 |
| SurfaceRenderView | `widget/SurfaceRenderView.kt` | 163 | Surface 渲染视图 |
| VideoSizeCalculator | `widget/VideoSizeCalculator.kt` | 141 | 视频尺寸计算器 |
| MainActivity | `demo/MainActivity.kt` | 269 | 主界面 |
| SkyVideoActivity | `demo/SkyVideoActivity.kt` | 413 | 视频播放 Activity |

## 扩展开发指南

### 添加新的缩放模式

1. 在 `VideoSizeCalculator.ScaleType` 中添加新枚举值
2. 在 `calculateDisplaySize()` 中实现计算逻辑
3. 在 `SurfaceRenderView` 中暴露设置方法

### 自定义播放控制器

1. 创建自定义 View 实现播控 UI
2. 实现 `MediaController.MediaPlayerControl` 接口
3. 通过 `SkyVideoView` 的方法控制播放

### 添加手势控制

1. 在 `SkyVideoView` 中添加 `GestureDetector`
2. 实现滑动调节音量、亮度、进度
3. 实现双击暂停/播放
