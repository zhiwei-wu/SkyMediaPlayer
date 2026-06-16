package imt.zw.skymediaplayer.widget.control

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 播放器交互覆盖层
 * 
 * 职责：
 * - 接收整个视频区域的触摸事件
 * - 统一管理字幕和播控的显示/隐藏
 * - 自动隐藏定时器管理
 */
class SkyPlayerOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "SkyPlayerOverlay"
        private const val AUTO_HIDE_DELAY_MS = 5000L
    }

    // UI 组件
    private lateinit var subtitleTextView: TextView
    private lateinit var controlView: SkyPlayerControlView
    private lateinit var settingsPanel: SkySubtitleSettingsPanel
    private lateinit var qualityPanel: SkyQualityPanel

    // 状态
    private var isControlVisible = false
    private var playerControl: PlayerControl? = null

    // 自动隐藏定时器
    private val autoHideHandler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable { hideControl() }

    // 回调
    private var onSubtitleSettingsChangeListener: OnSubtitleSettingsChangeListener? = null
    private var onRotateButtonClickListener: View.OnClickListener? = null
    private var onDebugButtonClickListener: View.OnClickListener? = null
    private var onQualityPanelListener: SkyQualityPanel.OnQualityPanelListener? = null

    init {
        initView()
    }

    private fun initView() {
        // 设置全屏覆盖
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        // 字幕视图（位于底部，播控栏上方）
        subtitleTextView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
            setBackgroundColor(0x80000000.toInt())
            visibility = GONE
            
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dpToPx(80) // 留出播控栏空间
                marginStart = dpToPx(32)
                marginEnd = dpToPx(32)
            }
        }
        addView(subtitleTextView)

        // 底部播控栏
        controlView = SkyPlayerControlView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
            }
            visibility = GONE

            // 设置用户交互回调，用于重置自动隐藏定时器
            setOnUserInteractionListener {
                resetAutoHideTimer()
            }

            // 设置 AI 字幕按钮点击监听
            setOnSubtitleButtonClickListener {
                showSettingsPanel()
            }

            // 设置画质入口按钮点击监听 —— 打开画质面板
            setOnFilterButtonClickListener {
                showQualityPanel()
            }

            // 设置旋转按钮点击监听
            setOnRotateButtonClickListener {
                onRotateButtonClickListener?.onClick(it)
            }

            // 设置调试信息按钮点击监听
            setOnDebugButtonClickListener {
                onDebugButtonClickListener?.onClick(it)
            }
        }
        addView(controlView)

        // 字幕设置面板（最顶层）
        settingsPanel = SkySubtitleSettingsPanel(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            
            setOnSettingsChangeListener(object : OnSubtitleSettingsChangeListener {
                override fun onSubtitleSettingsChanged(settings: SubtitleSettings) {
                    // 更新字幕按钮状态
                    controlView.updateSubtitleButtonState(settings.enabled)
                    // 通知外部
                    onSubtitleSettingsChangeListener?.onSubtitleSettingsChanged(settings)
                }
            })

            setOnDismissListener {
                // 面板关闭后重置自动隐藏定时器
                resetAutoHideTimer()
            }
        }
        addView(settingsPanel)

        // 画质面板（最顶层）
        qualityPanel = SkyQualityPanel(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setOnQualityPanelListener(object : SkyQualityPanel.OnQualityPanelListener {
                override fun onFilterSelected(item: SkyQualityPanel.QualityFilterItem) {
                    onQualityPanelListener?.onFilterSelected(item)
                }
                override fun onIntensityChanged(percent: Int) {
                    onQualityPanelListener?.onIntensityChanged(percent)
                }
                override fun onEnhanceChanged(sharpness: Int, deband: Int) {
                    onQualityPanelListener?.onEnhanceChanged(sharpness, deband)
                }
            })
            setOnDismissListener { resetAutoHideTimer() }
        }
        addView(qualityPanel)
    }

    /**
     * 处理触摸事件
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            // 如果设置面板/画质面板正在显示，不处理触摸事件（由面板自己处理）
            if (settingsPanel.isShowing() || qualityPanel.isShowing()) {
                return false
            }

            // 点击空白区域，切换播控显示/隐藏
            toggleControlVisibility()
            return true
        }
        return super.onTouchEvent(event)
    }

    /**
     * 拦截触摸事件
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // 如果设置面板/画质面板正在显示，不拦截事件
        if (settingsPanel.isShowing() || qualityPanel.isShowing()) {
            return false
        }

        // 如果播控栏正在显示，检查是否点击在播控栏区域
        if (isControlVisible && ev.action == MotionEvent.ACTION_DOWN) {
            val controlTop = controlView.top
            if (ev.y >= controlTop) {
                // 点击在播控栏区域，不拦截，让播控栏处理
                return false
            }
        }

        return false
    }

    /**
     * 切换播控显示/隐藏
     */
    fun toggleControlVisibility() {
        if (isControlVisible) {
            hideControl()
        } else {
            showControl()
        }
    }

    /**
     * 显示播控栏
     */
    fun showControl() {
        if (isControlVisible) {
            resetAutoHideTimer()
            return
        }

        isControlVisible = true
        controlView.visibility = VISIBLE
        controlView.startProgressUpdate()

        // 启动自动隐藏定时器
        resetAutoHideTimer()

        Log.d(TAG, "Control shown")
    }

    /**
     * 隐藏播控栏
     */
    fun hideControl() {
        if (!isControlVisible) return

        isControlVisible = false
        controlView.visibility = GONE
        controlView.stopProgressUpdate()

        // 取消自动隐藏定时器
        autoHideHandler.removeCallbacks(autoHideRunnable)

        Log.d(TAG, "Control hidden")
    }

    /**
     * 重置自动隐藏定时器
     */
    private fun resetAutoHideTimer() {
        autoHideHandler.removeCallbacks(autoHideRunnable)
        autoHideHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
    }

    /**
     * 显示设置面板
     */
    private fun showSettingsPanel() {
        // 取消自动隐藏定时器
        autoHideHandler.removeCallbacks(autoHideRunnable)
        settingsPanel.show()
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
        controlView.setPlayerControl(control)
    }

    /**
     * 设置字幕文本
     */
    fun setSubtitleText(text: String?) {
        if (text.isNullOrEmpty()) {
            subtitleTextView.visibility = GONE
        } else {
            subtitleTextView.text = text
            subtitleTextView.visibility = VISIBLE
        }
    }

    /**
     * 隐藏字幕
     */
    fun hideSubtitle() {
        subtitleTextView.visibility = GONE
    }

    /**
     * 设置字幕设置变更监听器
     */
    fun setOnSubtitleSettingsChangeListener(listener: OnSubtitleSettingsChangeListener?) {
        this.onSubtitleSettingsChangeListener = listener
    }

    /**
     * 获取当前字幕设置
     */
    fun getSubtitleSettings(): SubtitleSettings {
        return settingsPanel.getSettings()
    }

    /**
     * 设置字幕设置
     */
    fun setSubtitleSettings(settings: SubtitleSettings) {
        settingsPanel.setSettings(settings)
        controlView.updateSubtitleButtonState(settings.enabled)
    }

    /**
     * 更新播放/暂停按钮状态
     */
    fun updatePlayPauseButton(playing: Boolean) {
        controlView.updatePlayPauseButton(playing)
    }

    /**
     * 显示画质面板
     */
    fun showQualityPanel() {
        // 显示前先隐藏播控栏，避免遮挡
        hideControl()
        qualityPanel.show()
    }

    /** 设置画质滤镜列表 */
    fun setQualityFilterItems(items: List<SkyQualityPanel.QualityFilterItem>) {
        qualityPanel.setFilterItems(items)
    }

    /** 设置当前选中滤镜 */
    fun setSelectedQualityFilter(id: String?) {
        qualityPanel.setSelectedFilter(id)
    }

    /** 设置滤镜强度（0-100） */
    fun setQualityIntensity(percent: Int) {
        qualityPanel.setIntensity(percent)
    }

    /** 设置画质增强参数（各 0-100） */
    fun setEnhanceValues(sharpness: Int, deband: Int) {
        qualityPanel.setEnhanceValues(sharpness, deband)
    }

    /** 设置画质面板回调 */
    fun setOnQualityPanelListener(listener: SkyQualityPanel.OnQualityPanelListener?) {
        this.onQualityPanelListener = listener
    }

    /**
     * 设置旋转按钮点击监听器
     */
    fun setOnRotateButtonClickListener(listener: View.OnClickListener?) {
        this.onRotateButtonClickListener = listener
    }

    /**
     * 设置调试信息按钮点击监听器
     */
    fun setOnDebugButtonClickListener(listener: View.OnClickListener?) {
        this.onDebugButtonClickListener = listener
    }

    /**
     * 播控栏是否正在显示
     */
    fun isControlShowing(): Boolean = isControlVisible

    /**
     * 设置面板是否正在显示
     */
    fun isSettingsPanelShowing(): Boolean = settingsPanel.isShowing()

    /**
     * 释放资源
     */
    fun release() {
        autoHideHandler.removeCallbacks(autoHideRunnable)
        controlView.stopProgressUpdate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }
}
