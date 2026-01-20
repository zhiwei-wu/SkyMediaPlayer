package imt.skymediaplayer.demo

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import imt.zw.skymediaplayer.player.IMediaPlayer
import imt.zw.skymediaplayer.player.SkyMediaPlayer
import imt.zw.skymediaplayer.widget.SkyVideoView
import imt.zw.skymediaplayer.widget.control.OnSubtitleSettingsChangeListener
import imt.zw.skymediaplayer.widget.control.SubtitleSettings

class SkyVideoActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "SkyVideoActivity"
    }

    private lateinit var mSkyVideoView: SkyVideoView
    private var wasPlayingBeforePause = false

    // 预缓冲 UI 组件
    private var prebufferOverlay: FrameLayout? = null
    private var prebufferProgress: ProgressBar? = null
    private var prebufferText: TextView? = null

    // 字幕同步状态
    private var isSubtitleSyncEnabled = false

    // 当前显示的字幕
    private var currentSubtitleText: String? = null

    // 上一次的字幕启用状态（用于判断是否需要触发开启/关闭操作）
    private var lastSubtitleEnabled: Boolean = false

    // 字幕调试模式开关
    private var isSubtitleDebugMode = false  // 默认关闭调试模式

    // 字幕处理间隔（秒）
    private var subtitleProcessingInterval = SubtitleSettings.DEFAULT_PROCESSING_INTERVAL

    // Seek 后字幕加载状态
    private var isWaitingForSubtitleAfterSeek = false

    // 字幕等待队列和 Handler
    private val subtitleHandler = Handler(Looper.getMainLooper())
    
    // 待展示的字幕队列（按 startTimeMs 排序）
    private data class PendingSubtitle(
        val text: String,
        val startTimeMs: Long,
        val endTimeMs: Long
    )
    private val pendingSubtitleQueue = mutableListOf<PendingSubtitle>()
    private var subtitleCheckRunnable: Runnable? = null
    private val SUBTITLE_CHECK_INTERVAL_MS = 100L  // 每 100ms 检查一次字幕队列

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置全沉浸式全屏
        setupFullscreenMode()

        setContentView(R.layout.activity_layout)

        // 初始化视频播放器
        mSkyVideoView = findViewById(R.id.sky_video_view)

        // 设置 AI 字幕设置变更监听
        setupSubtitleSettingsListener()

        // 设置 Seek 完成监听（用于字幕同步）
        setupSeekCompleteListener()

        // 创建预缓冲 UI
        createPrebufferUI()

        // 检查是否传递了在线视频 URL
        val videoUrl = intent.getStringExtra("video_url")
        if (videoUrl != null) {
            Log.d(TAG, "Playing online video: $videoUrl")
            mSkyVideoView.setVideoPath(videoUrl)
            Toast.makeText(this, "正在连接服务器...", Toast.LENGTH_SHORT).show()
        } else {
            val videoUriString = intent.getStringExtra("video_uri")
            if (videoUriString == null) {
                Toast.makeText(this, "未选择视频文件", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            val videoUri = Uri.parse(videoUriString)
            Log.d(TAG, "Playing local video URI: $videoUri")
            mSkyVideoView.setVideoURI(videoUri)
        }

        mSkyVideoView.start()
    }

    /**
     * 设置 Seek 完成监听器
     * Seek 完成后清空字幕队列并显示"字幕加载中..."提示
     */
    private fun setupSeekCompleteListener() {
        mSkyVideoView.setOnSeekCompleteListener(object : IMediaPlayer.OnSeekCompleteListener {
            override fun onSeekComplete(mp: IMediaPlayer) {
                if (!isSubtitleSyncEnabled) return

                Log.i(TAG, "Seek complete: clearing subtitle queue and showing loading hint")

                // 清空字幕等待队列
                synchronized(pendingSubtitleQueue) {
                    pendingSubtitleQueue.clear()
                }

                // 停止字幕队列检查器
                subtitleCheckRunnable?.let { subtitleHandler.removeCallbacks(it) }
                subtitleCheckRunnable = null

                // 显示"字幕加载中..."提示
                isWaitingForSubtitleAfterSeek = true
                runOnUiThread {
                    mSkyVideoView.setSubtitleText("字幕加载中...")
                }
            }
        })
    }

    /**
     * 设置字幕设置变更监听器
     */
    private fun setupSubtitleSettingsListener() {
        mSkyVideoView.setOnSubtitleSettingsChangeListener(object : OnSubtitleSettingsChangeListener {
            override fun onSubtitleSettingsChanged(settings: SubtitleSettings) {
                Log.i(TAG, "Subtitle settings changed: $settings")
                handleSubtitleSettingsChange(settings)
            }
        })
    }

    /**
     * 处理字幕设置变更
     * 只有当 enabled 状态发生变化时才触发开启/关闭操作
     */
    private fun handleSubtitleSettingsChange(settings: SubtitleSettings) {
        // 同步处理间隔和调试模式设置
        subtitleProcessingInterval = settings.processingInterval
        isSubtitleDebugMode = settings.debugMode
        Log.d(TAG, "Subtitle settings: interval=${subtitleProcessingInterval}s, debugMode=$isSubtitleDebugMode")

        // 只有 enabled 状态变化时才触发开启/关闭操作
        if (settings.enabled != lastSubtitleEnabled) {
            lastSubtitleEnabled = settings.enabled
            if (settings.enabled) {
                enableWhisperSubtitle(settings)
            } else {
                disableWhisperSubtitle()
            }
        } else {
            // enabled 状态未变化，只是其他设置变更（如语言、设备），仅记录日志
            Log.d(TAG, "Subtitle settings updated (enabled unchanged): $settings")
        }
    }

    /**
     * 启用 Whisper AI 字幕
     */
    private fun enableWhisperSubtitle(settings: SubtitleSettings) {
        val app = application as? SkyPlayerApplication
        val modelPath = app?.getWhisperModelPath()

        if (modelPath == null) {
            Toast.makeText(this, "模型加载中，请稍后再试", Toast.LENGTH_SHORT).show()
            mSkyVideoView.setSubtitleSettings(settings.copy(enabled = false))
            return
        }

        // 显示预缓冲 UI
        showPrebufferUI()

        // 设置预缓冲完成监听器
        mSkyVideoView.getMediaPlayer()?.let { player ->
            player.clearSubtitleQueue()

            player.setOnPrebufferCompleteListener(object : SkyMediaPlayer.OnPrebufferCompleteListener {
                override fun onPrebufferComplete(mp: IMediaPlayer, subtitleCount: Int) {
                    Log.i(TAG, "Prebuffer complete: $subtitleCount subtitles")
                    runOnUiThread {
                        hidePrebufferUI()
                        startSubtitleSync()
                        player.start()
                        Toast.makeText(this@SkyVideoActivity, "AI 字幕准备完成", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }

        // 暂停视频，等待预缓冲
        mSkyVideoView.pause()

        // 根据设置确定语言
        val language = settings.targetLanguage.code.ifEmpty {
            "en" // 默认英文识别
        }

        val result = mSkyVideoView.setWhisperEnabled(true, modelPath, language, settings.processingInterval)
        if (result != 0) {
            Toast.makeText(this, "AI 字幕开启失败", Toast.LENGTH_SHORT).show()
            mSkyVideoView.setSubtitleSettings(settings.copy(enabled = false))
            hidePrebufferUI()
            mSkyVideoView.start()
        } else {
            Log.i(TAG, "Whisper enabled with device=${settings.inferenceDevice}, language=$language")
        }
    }

    /**
     * 禁用 Whisper AI 字幕
     */
    private fun disableWhisperSubtitle() {
        stopSubtitleSync()
        mSkyVideoView.hideSubtitle()
        mSkyVideoView.setWhisperEnabled(false)
        Toast.makeText(this, "AI 字幕已关闭", Toast.LENGTH_SHORT).show()
    }

    /**
     * 创建预缓冲 UI
     */
    private fun createPrebufferUI() {
        val rootView = findViewById<FrameLayout>(R.id.sky_video_view)?.parent as? FrameLayout
            ?: return

        prebufferOverlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0x80000000.toInt())
            visibility = View.GONE
        }

        prebufferProgress = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        }

        prebufferText = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                topMargin = 150
            }
            text = "正在准备 AI 字幕..."
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
        }

        prebufferOverlay?.addView(prebufferProgress)
        prebufferOverlay?.addView(prebufferText)
        rootView.addView(prebufferOverlay)
    }

    /**
     * 显示预缓冲 UI
     */
    private fun showPrebufferUI() {
        prebufferOverlay?.visibility = View.VISIBLE
    }

    /**
     * 隐藏预缓冲 UI
     */
    private fun hidePrebufferUI() {
        prebufferOverlay?.visibility = View.GONE
    }

    /**
     * 启动字幕同步
     * 实现字幕时间窗口控制：
     * - 立即展示：主时钟在 [startTime - interval/2, startTime + interval/2]
     * - 丢弃：startTime + interval < 主时钟（字幕太旧）
     * - 等待：字幕早于主时钟，加入等待队列，等待合适时间再展示
     */
    private fun startSubtitleSync() {
        if (isSubtitleSyncEnabled) return
        isSubtitleSyncEnabled = true

        // 清空字幕队列
        synchronized(pendingSubtitleQueue) {
            pendingSubtitleQueue.clear()
        }

        // 使用带时间戳的回调方式显示字幕
        mSkyVideoView.getMediaPlayer()?.let { player ->
            player.setOnSubtitleWithPtsListener(object : SkyMediaPlayer.OnSubtitleWithPtsListener {
                override fun onSubtitle(mp: IMediaPlayer, text: String, startTimeMs: Long, endTimeMs: Long) {
                    if (!isSubtitleSyncEnabled) return

                    // Seek 后收到第一条新字幕，清除"字幕加载中..."状态
                    if (isWaitingForSubtitleAfterSeek) {
                        isWaitingForSubtitleAfterSeek = false
                        Log.i(TAG, "First subtitle after seek received, clearing loading hint")
                    }

                    // 获取当前播放位置（毫秒）
                    val currentPosMs = mp.getCurrentPosition()
                    val intervalMs = subtitleProcessingInterval * 1000L
                    val halfIntervalMs = intervalMs / 2

                    // 计算时间窗口边界
                    val windowStart = startTimeMs - halfIntervalMs
                    val windowEnd = startTimeMs + halfIntervalMs
                    val discardThreshold = startTimeMs + intervalMs

                    when {
                        // 丢弃条件：字幕太旧，startTime + interval < 主时钟
                        discardThreshold < currentPosMs -> {
                            Log.d(TAG, "Subtitle discarded (too old): startTime=${startTimeMs}ms, current=${currentPosMs}ms, threshold=${discardThreshold}ms")
                            // 不显示，直接丢弃
                        }

                        // 立即展示条件：主时钟在 [startTime - interval/2, startTime + interval/2]
                        currentPosMs in windowStart..windowEnd -> {
                            runOnUiThread {
                                displaySubtitle(text, startTimeMs, currentPosMs)
                            }
                        }

                        // 等待条件：字幕超前（startTime > currentPos + halfInterval）
                        startTimeMs > currentPosMs + halfIntervalMs -> {
                            // 将字幕加入等待队列
                            synchronized(pendingSubtitleQueue) {
                                pendingSubtitleQueue.add(PendingSubtitle(text, startTimeMs, endTimeMs))
                                // 按 startTimeMs 排序，确保先到期的字幕先展示
                                pendingSubtitleQueue.sortBy { it.startTimeMs }
                                Log.d(TAG, "Subtitle queued: startTime=${startTimeMs}ms, current=${currentPosMs}ms, queueSize=${pendingSubtitleQueue.size}")
                            }
                            // 启动字幕队列检查器
                            startSubtitleQueueChecker(mp)
                        }

                        // 其他情况：直接显示
                        else -> {
                            runOnUiThread {
                                displaySubtitle(text, startTimeMs, currentPosMs)
                            }
                        }
                    }
                }
            })
        }
    }

    /**
     * 启动字幕队列检查器
     * 定期检查等待队列中的字幕是否到达展示时间
     */
    private fun startSubtitleQueueChecker(player: IMediaPlayer) {
        // 如果检查器已经在运行，不需要重复启动
        if (subtitleCheckRunnable != null) return

        subtitleCheckRunnable = object : Runnable {
            override fun run() {
                if (!isSubtitleSyncEnabled) {
                    subtitleCheckRunnable = null
                    return
                }

                val currentPosMs = player.getCurrentPosition()
                val intervalMs = subtitleProcessingInterval * 1000L
                val halfIntervalMs = intervalMs / 2

                synchronized(pendingSubtitleQueue) {
                    // 遍历队列，处理到期的字幕
                    val iterator = pendingSubtitleQueue.iterator()
                    while (iterator.hasNext()) {
                        val subtitle = iterator.next()
                        val windowStart = subtitle.startTimeMs - halfIntervalMs
                        val windowEnd = subtitle.startTimeMs + halfIntervalMs
                        val discardThreshold = subtitle.startTimeMs + intervalMs

                        when {
                            // 字幕已过期，丢弃
                            discardThreshold < currentPosMs -> {
                                Log.d(TAG, "Queued subtitle discarded (too old): startTime=${subtitle.startTimeMs}ms, current=${currentPosMs}ms")
                                iterator.remove()
                            }
                            // 字幕到达展示时间窗口，展示并移除
                            currentPosMs in windowStart..windowEnd -> {
                                runOnUiThread {
                                    displaySubtitle(subtitle.text, subtitle.startTimeMs, currentPosMs)
                                }
                                iterator.remove()
                                Log.d(TAG, "Queued subtitle displayed: startTime=${subtitle.startTimeMs}ms, current=${currentPosMs}ms")
                            }
                            // 字幕还未到展示时间，继续等待（队列已排序，后面的字幕更晚，可以跳出）
                            else -> {
                                // 继续检查下一个，因为可能有更早的字幕已经到期
                            }
                        }
                    }

                    // 如果队列为空，停止检查器
                    if (pendingSubtitleQueue.isEmpty()) {
                        subtitleCheckRunnable = null
                        return
                    }
                }

                // 继续下一次检查
                subtitleHandler.postDelayed(this, SUBTITLE_CHECK_INTERVAL_MS)
            }
        }

        // 启动检查器
        subtitleHandler.post(subtitleCheckRunnable!!)
    }

    /**
     * 显示字幕
     * @param text 字幕文本
     * @param startTimeMs 字幕开始时间（毫秒）
     * @param currentPosMs 当前播放位置（毫秒）
     */
    private fun displaySubtitle(text: String, startTimeMs: Long, currentPosMs: Long) {
        val currentPosSec = currentPosMs / 1000.0
        val startTimeSec = startTimeMs / 1000.0
        val delaySec = startTimeSec - currentPosSec

        val displayText = if (isSubtitleDebugMode) {
            // 调试模式：显示时间信息
            // 格式：[字幕时间 | 播放时间 | 延迟] 字幕内容
            String.format("[%.1fs | %.1fs | %.1fs] %s",
                startTimeSec, currentPosSec, delaySec, text)
        } else {
            // 正常模式：只显示字幕
            text
        }

        currentSubtitleText = displayText
        mSkyVideoView.setSubtitleText(displayText)
        Log.d(TAG, "Subtitle displayed: $displayText")
    }

    /**
     * 设置字幕调试模式
     * @param enabled true 开启调试模式，显示时间信息；false 关闭调试模式，只显示字幕
     */
    fun setSubtitleDebugMode(enabled: Boolean) {
        isSubtitleDebugMode = enabled
        Log.i(TAG, "Subtitle debug mode: $enabled")
    }

    /**
     * 停止字幕同步
     */
    private fun stopSubtitleSync() {
        isSubtitleSyncEnabled = false
        isWaitingForSubtitleAfterSeek = false
        // 停止字幕队列检查器
        subtitleCheckRunnable?.let { subtitleHandler.removeCallbacks(it) }
        subtitleCheckRunnable = null
        // 清空字幕等待队列
        synchronized(pendingSubtitleQueue) {
            pendingSubtitleQueue.clear()
        }
        mSkyVideoView.getMediaPlayer()?.setOnSubtitleWithPtsListener(null)
        currentSubtitleText = null
    }

    private fun setupFullscreenMode() {
        // 保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 使用 WindowCompat 和 WindowInsetsControllerCompat 实现全沉浸式
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)

        windowInsetsController?.let { controller ->
            // 隐藏状态栏和导航栏
            controller.hide(WindowInsetsCompat.Type.systemBars())
            // 设置沉浸式模式，用户滑动时系统栏会暂时显示然后自动隐藏
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // 设置全屏标志（兼容旧版本）
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // 当窗口重新获得焦点时，重新设置全屏模式
            setupFullscreenMode()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause() called")

        if (::mSkyVideoView.isInitialized) {
            wasPlayingBeforePause = mSkyVideoView.isPlaying()
            if (wasPlayingBeforePause) {
                mSkyVideoView.pause()
                Log.d(TAG, "Video paused in onPause()")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume() called")

        if (::mSkyVideoView.isInitialized && wasPlayingBeforePause) {
            mSkyVideoView.post {
                mSkyVideoView.start()
                wasPlayingBeforePause = false
                Log.d(TAG, "Video resumed in onResume() after Surface ready")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy() called - releasing all resources")

        // 停止字幕同步
        stopSubtitleSync()

        // 释放所有资源，确保音频完全停止
        if (::mSkyVideoView.isInitialized) {
            mSkyVideoView.release()
            Log.d(TAG, "SkyVideoView resources released")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        Log.d(TAG, "onBackPressed() called")
        super.onBackPressed()
    }
}