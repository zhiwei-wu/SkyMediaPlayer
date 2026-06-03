package imt.zw.skymediaplayer.widget.control

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * 底部播控栏组件
 * 包含进度条、时间显示、播放控制按钮、AI字幕按钮等
 */
class SkyPlayerControlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "SkyPlayerControlView"
        private const val PROGRESS_UPDATE_INTERVAL_MS = 500L
    }

    // UI 组件
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var progressSeekBar: SeekBar
    private lateinit var rewindButton: TextView
    private lateinit var playPauseButton: TextView
    private lateinit var forwardButton: TextView
    private lateinit var subtitleButton: TextView
    private lateinit var filterButton: TextView
    private lateinit var rotateButton: TextView
    private lateinit var debugButton: TextView

    // 状态
    private var isPlaying = false
    private var isDragging = false
    private var duration = 0
    private var currentPosition = 0

    // 进度更新
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            if (!isDragging) {
                updateProgress()
            }
            progressHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
        }
    }

    // 回调接口
    private var playerControl: PlayerControl? = null
    private var onSubtitleButtonClickListener: OnClickListener? = null
    private var onFilterButtonClickListener: OnClickListener? = null
    private var onRotateButtonClickListener: OnClickListener? = null
    private var onDebugButtonClickListener: OnClickListener? = null

    init {
        initView()
    }

    private fun initView() {
        orientation = VERTICAL
        setBackgroundColor(0xCC000000.toInt())
        setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(12))

        // 进度条区域
        addProgressArea()

        // 控制按钮区域
        addControlButtonArea()
    }

    /**
     * 添加进度条区域
     */
    private fun addProgressArea() {
        val progressLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        // 当前时间
        currentTimeText = TextView(context).apply {
            text = "00:00"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        progressLayout.addView(currentTimeText)

        // 进度条
        progressSeekBar = SeekBar(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dpToPx(8)
                marginEnd = dpToPx(8)
            }
            max = 1000
            progress = 0
            
            // 设置进度条样式
            progressDrawable?.setTint(0xFF2196F3.toInt())
            thumb?.setTint(Color.WHITE)
            
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && duration > 0) {
                        val newPosition = (progress.toLong() * duration / 1000).toInt()
                        currentTimeText.text = formatTime(newPosition)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isDragging = true
                    onUserInteraction()
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    isDragging = false
                    seekBar?.let {
                        val newPosition = (it.progress.toLong() * duration / 1000).toInt()
                        playerControl?.seekTo(newPosition)
                        Log.d(TAG, "Seek to: $newPosition")
                    }
                }
            })
        }
        progressLayout.addView(progressSeekBar)

        // 总时长
        totalTimeText = TextView(context).apply {
            text = "00:00"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        progressLayout.addView(totalTimeText)

        addView(progressLayout)
    }

    /**
     * 添加控制按钮区域
     */
    private fun addControlButtonArea() {
        val buttonLayout = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dpToPx(48)).apply {
                topMargin = dpToPx(4)
            }
        }

        // 左侧按钮组（后退、播放/暂停、前进）
        val leftButtonGroup = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
        }

        // 后退 10 秒按钮
        rewindButton = createTextButton("◀◀").apply {
            setOnClickListener {
                onUserInteraction()
                playerControl?.let {
                    val newPos = maxOf(0, it.getCurrentPosition() - 10000)
                    it.seekTo(newPos)
                    Log.d(TAG, "Rewind 10s to: $newPos")
                }
            }
            contentDescription = "后退10秒"
        }
        leftButtonGroup.addView(rewindButton)

        // 播放/暂停按钮
        playPauseButton = createTextButton("▶").apply {
            setOnClickListener {
                onUserInteraction()
                togglePlayPause()
            }
            contentDescription = "播放/暂停"
        }
        leftButtonGroup.addView(playPauseButton)

        // 前进 10 秒按钮
        forwardButton = createTextButton("▶▶").apply {
            setOnClickListener {
                onUserInteraction()
                playerControl?.let {
                    val newPos = minOf(it.getDuration(), it.getCurrentPosition() + 10000)
                    it.seekTo(newPos)
                    Log.d(TAG, "Forward 10s to: $newPos")
                }
            }
            contentDescription = "前进10秒"
        }
        leftButtonGroup.addView(forwardButton)

        buttonLayout.addView(leftButtonGroup)

        // 右侧按钮组（AI字幕、音量）
        val rightButtonGroup = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        }

        // AI 字幕按钮
        subtitleButton = createTextButton("AI").apply {
            setOnClickListener {
                onUserInteraction()
                onSubtitleButtonClickListener?.onClick(this)
            }
            contentDescription = "AI字幕设置"
        }
        rightButtonGroup.addView(subtitleButton)

        // 画质入口按钮（文字「画质」）
        filterButton = createTextButton("画质").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            // 文字按钮用自适应宽度 + 左右内边距，避免两个汉字被裁切
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dpToPx(48))
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
            setOnClickListener {
                onUserInteraction()
                onFilterButtonClickListener?.onClick(this)
                Log.d(TAG, "Filter button clicked")
            }
            contentDescription = "画质"
        }
        rightButtonGroup.addView(filterButton)

        // 横竖屏旋转按钮
        rotateButton = createTextButton("↻").apply {
            setOnClickListener {
                onUserInteraction()
                onRotateButtonClickListener?.onClick(this)
                Log.d(TAG, "Rotate button clicked")
            }
            contentDescription = "旋转屏幕"
        }
        rightButtonGroup.addView(rotateButton)

        // 调试信息按钮
        debugButton = createTextButton("ℹ").apply {
            setOnClickListener {
                onUserInteraction()
                onDebugButtonClickListener?.onClick(this)
                Log.d(TAG, "Debug button clicked")
            }
            contentDescription = "调试信息"
        }
        rightButtonGroup.addView(debugButton)

        buttonLayout.addView(rightButtonGroup)

        addView(buttonLayout)
    }

    /**
     * 创建文字按钮（使用 TextView 实现，支持 Unicode 符号）
     */
    private fun createTextButton(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(dpToPx(48), dpToPx(48))
            
            // 设置可点击背景
            val outValue = TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                outValue,
                true
            )
            setBackgroundResource(outValue.resourceId)
            
            isClickable = true
            isFocusable = true
        }
    }

    /**
     * 切换播放/暂停状态
     */
    private fun togglePlayPause() {
        playerControl?.let {
            if (it.isPlaying()) {
                it.pause()
                updatePlayPauseButton(false)
            } else {
                it.start()
                updatePlayPauseButton(true)
            }
        }
    }

    /**
     * 更新播放/暂停按钮状态
     */
    fun updatePlayPauseButton(playing: Boolean) {
        isPlaying = playing
        playPauseButton.text = if (playing) "⏸" else "▶"
        Log.d(TAG, "Play state updated: $playing")
    }

    /**
     * 更新进度
     */
    private fun updateProgress() {
        playerControl?.let {
            duration = it.getDuration()
            currentPosition = it.getCurrentPosition()

            if (duration > 0) {
                val progress = (currentPosition.toLong() * 1000 / duration).toInt()
                progressSeekBar.progress = progress
                currentTimeText.text = formatTime(currentPosition)
                totalTimeText.text = formatTime(duration)
            }

            // 同步播放状态
            val playing = it.isPlaying()
            if (isPlaying != playing) {
                updatePlayPauseButton(playing)
            }
        }
    }

    /**
     * 格式化时间
     */
    private fun formatTime(timeMs: Int): String {
        val totalSeconds = timeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    /**
     * dp 转 px
     */
    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    // ============================================================================
    // 公共方法
    // ============================================================================

    /**
     * 设置播放器控制接口
     */
    fun setPlayerControl(control: PlayerControl) {
        this.playerControl = control
    }

    /**
     * 设置 AI 字幕按钮点击监听器
     */
    fun setOnSubtitleButtonClickListener(listener: OnClickListener?) {
        this.onSubtitleButtonClickListener = listener
    }

    /**
     * 设置画质滤镜按钮点击监听器
     */
    fun setOnFilterButtonClickListener(listener: OnClickListener?) {
        this.onFilterButtonClickListener = listener
    }

    /**
     * 设置旋转按钮点击监听器
     */
    fun setOnRotateButtonClickListener(listener: OnClickListener?) {
        this.onRotateButtonClickListener = listener
    }

    /**
     * 设置调试信息按钮点击监听器
     */
    fun setOnDebugButtonClickListener(listener: OnClickListener?) {
        this.onDebugButtonClickListener = listener
    }

    /**
     * 更新 AI 字幕按钮状态
     */
    fun updateSubtitleButtonState(enabled: Boolean) {
        subtitleButton.alpha = if (enabled) 1.0f else 0.5f
    }

    /**
     * 开始进度更新
     */
    fun startProgressUpdate() {
        progressHandler.removeCallbacks(progressUpdateRunnable)
        progressHandler.post(progressUpdateRunnable)
    }

    /**
     * 停止进度更新
     */
    fun stopProgressUpdate() {
        progressHandler.removeCallbacks(progressUpdateRunnable)
    }

    /**
     * 用户交互回调（用于重置自动隐藏定时器）
     */
    private var onUserInteractionListener: (() -> Unit)? = null

    fun setOnUserInteractionListener(listener: () -> Unit) {
        this.onUserInteractionListener = listener
    }

    private fun onUserInteraction() {
        onUserInteractionListener?.invoke()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopProgressUpdate()
    }
}

/**
 * 播放器控制接口
 */
interface PlayerControl {
    fun start()
    fun pause()
    fun getDuration(): Int
    fun getCurrentPosition(): Int
    fun seekTo(position: Int)
    fun isPlaying(): Boolean
    fun getBufferPercentage(): Int
}
