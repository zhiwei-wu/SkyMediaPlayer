package imt.zw.skymediaplayer.widget.control

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * AI 字幕设置面板
 * 从底部弹出的设置面板，包含字幕开关、推理设备选择、翻译语言选择
 */
class SkySubtitleSettingsPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "SkySubtitleSettingsPanel"
        private const val ANIMATION_DURATION_MS = 300L
    }

    // UI 组件
    private lateinit var dimBackground: View
    private lateinit var panelContainer: LinearLayout
    private lateinit var enableSwitch: Switch
    private lateinit var cpuButton: TextView
    private lateinit var gpuButton: TextView
    private lateinit var languageButtons: Map<TargetLanguage, TextView>
    private lateinit var intervalSeekBar: SeekBar
    private lateinit var intervalValueText: TextView
    private lateinit var debugSwitch: Switch

    // 当前设置
    private var currentSettings = SubtitleSettings.DEFAULT

    // 回调
    private var onSettingsChangeListener: OnSubtitleSettingsChangeListener? = null
    private var onDismissListener: (() -> Unit)? = null

    init {
        initView()
        visibility = GONE
    }

    private fun initView() {
        // 设置全屏覆盖
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        // 半透明背景遮罩
        dimBackground = View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setBackgroundColor(0x80000000.toInt())
            setOnClickListener { dismiss() }
        }
        addView(dimBackground)

        // 底部面板容器
        val scrollView = ScrollView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
            }
            isVerticalScrollBarEnabled = false
        }

        panelContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            
            // 圆角背景
            background = GradientDrawable().apply {
                setColor(0xF5222222.toInt())
                cornerRadii = floatArrayOf(
                    dpToPx(16f), dpToPx(16f),
                    dpToPx(16f), dpToPx(16f),
                    0f, 0f,
                    0f, 0f
                )
            }
            
            setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(24))
        }

        // 标题栏
        addTitleBar()

        // 分隔线
        addDivider()

        // 启用开关
        addEnableSwitch()

        // 分隔线
        addDivider()

        // 推理设备选择
        addInferenceDeviceSection()

        // 分隔线
        addDivider()

        // 翻译语言选择
        addLanguageSection()

        // 分隔线
        addDivider()

        // 处理间隔设置
        addProcessingIntervalSection()

        // 分隔线
        addDivider()

        // 调试模式开关
        addDebugModeSection()

        scrollView.addView(panelContainer)
        addView(scrollView)
    }

    /**
     * 添加标题栏
     */
    private fun addTitleBar() {
        val titleBar = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(48)
            )
        }

        // 标题
        val titleText = TextView(context).apply {
            text = "🤖 AI 字幕设置"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
        }
        titleBar.addView(titleText)

        // 关闭按钮
        val closeButton = TextView(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(40),
                dpToPx(40)
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            gravity = Gravity.CENTER
            
            val outValue = TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                outValue,
                true
            )
            setBackgroundResource(outValue.resourceId)
            
            setOnClickListener { dismiss() }
        }
        titleBar.addView(closeButton)

        panelContainer.addView(titleBar)
    }

    /**
     * 添加分隔线
     */
    private fun addDivider() {
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(1)
            ).apply {
                topMargin = dpToPx(12)
                bottomMargin = dpToPx(12)
            }
            setBackgroundColor(0x33FFFFFF)
        }
        panelContainer.addView(divider)
    }

    /**
     * 添加启用开关
     */
    private fun addEnableSwitch() {
        val switchLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(48)
            )
        }

        val label = TextView(context).apply {
            text = "启用 AI 字幕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        switchLayout.addView(label)

        enableSwitch = Switch(context).apply {
            isChecked = currentSettings.enabled
            setOnCheckedChangeListener { _, isChecked ->
                updateSettings(currentSettings.copy(enabled = isChecked))
                // 开启/关闭字幕后自动隐藏面板
                dismiss()
            }
        }
        switchLayout.addView(enableSwitch)

        panelContainer.addView(switchLayout)
    }

    /**
     * 添加推理设备选择区域
     */
    private fun addInferenceDeviceSection() {
        // 标题
        val sectionTitle = TextView(context).apply {
            text = "⚡ 推理设备"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        panelContainer.addView(sectionTitle)

        // 按钮组
        val buttonGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(12)
            }
        }

        cpuButton = createOptionButton(InferenceDevice.CPU.displayName).apply {
            setOnClickListener {
                updateSettings(currentSettings.copy(inferenceDevice = InferenceDevice.CPU))
                updateInferenceDeviceButtons()
            }
        }
        buttonGroup.addView(cpuButton)

        // 间距
        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(12), 1)
        }
        buttonGroup.addView(spacer)

        gpuButton = createOptionButton(InferenceDevice.GPU.displayName).apply {
            setOnClickListener {
                updateSettings(currentSettings.copy(inferenceDevice = InferenceDevice.GPU))
                updateInferenceDeviceButtons()
            }
        }
        buttonGroup.addView(gpuButton)

        panelContainer.addView(buttonGroup)
        updateInferenceDeviceButtons()
    }

    /**
     * 添加翻译语言选择区域
     */
    private fun addLanguageSection() {
        // 标题
        val sectionTitle = TextView(context).apply {
            text = "🌍 识别语言（视频中说的语言）"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        panelContainer.addView(sectionTitle)

        // 第一行按钮
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(12)
            }
        }

        // 第二行按钮
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(8)
            }
        }

        val buttons = mutableMapOf<TargetLanguage, TextView>()
        val languages = TargetLanguage.entries.toTypedArray()

        languages.forEachIndexed { index, language ->
            val button = createOptionButton(language.displayName).apply {
                setOnClickListener {
                    updateSettings(currentSettings.copy(targetLanguage = language))
                    updateLanguageButtons()
                }
            }
            buttons[language] = button

            // 前三个放第一行，后两个放第二行
            if (index < 3) {
                if (index > 0) {
                    row1.addView(View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dpToPx(8), 1)
                    })
                }
                row1.addView(button)
            } else {
                if (index > 3) {
                    row2.addView(View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(dpToPx(8), 1)
                    })
                }
                row2.addView(button)
            }
        }

        languageButtons = buttons

        panelContainer.addView(row1)
        panelContainer.addView(row2)
        updateLanguageButtons()
    }

    /**
     * 添加处理间隔设置区域
     */
    private fun addProcessingIntervalSection() {
        // 标题行（包含标题和当前值）
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val sectionTitle = TextView(context).apply {
            text = "⏱️ 推理间隔"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(sectionTitle)

        intervalValueText = TextView(context).apply {
            text = "${currentSettings.processingInterval}s"
            setTextColor(0xFF2196F3.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        titleRow.addView(intervalValueText)

        panelContainer.addView(titleRow)

        // SeekBar 行
        val seekBarLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(8)
            }
        }

        // 最小值标签
        val minLabel = TextView(context).apply {
            text = "${SubtitleSettings.MIN_PROCESSING_INTERVAL}s"
            setTextColor(0x99FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        seekBarLayout.addView(minLabel)

        // SeekBar
        intervalSeekBar = SeekBar(context).apply {
            max = SubtitleSettings.MAX_PROCESSING_INTERVAL - SubtitleSettings.MIN_PROCESSING_INTERVAL
            progress = currentSettings.processingInterval - SubtitleSettings.MIN_PROCESSING_INTERVAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dpToPx(8)
                marginEnd = dpToPx(8)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val interval = progress + SubtitleSettings.MIN_PROCESSING_INTERVAL
                    intervalValueText.text = "${interval}s"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val interval = (seekBar?.progress ?: 0) + SubtitleSettings.MIN_PROCESSING_INTERVAL
                    updateSettings(currentSettings.copy(processingInterval = interval))
                }
            })
        }
        seekBarLayout.addView(intervalSeekBar)

        // 最大值标签
        val maxLabel = TextView(context).apply {
            text = "${SubtitleSettings.MAX_PROCESSING_INTERVAL}s"
            setTextColor(0x99FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        seekBarLayout.addView(maxLabel)

        panelContainer.addView(seekBarLayout)

        // 说明文字
        val descText = TextView(context).apply {
            text = "每次处理的音频时长，值越大延迟越高但更稳定"
            setTextColor(0x99FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(4)
            }
        }
        panelContainer.addView(descText)
    }

    /**
     * 添加调试模式开关区域
     */
    private fun addDebugModeSection() {
        val switchLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(48)
            )
        }

        val labelLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val label = TextView(context).apply {
            text = "🔧 调试模式"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        labelLayout.addView(label)

        val desc = TextView(context).apply {
            text = "显示字幕时间信息 [字幕时间 | 播放时间 | 延迟]"
            setTextColor(0x99FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        labelLayout.addView(desc)

        switchLayout.addView(labelLayout)

        debugSwitch = Switch(context).apply {
            isChecked = currentSettings.debugMode
            setOnCheckedChangeListener { _, isChecked ->
                updateSettings(currentSettings.copy(debugMode = isChecked))
            }
        }
        switchLayout.addView(debugSwitch)

        panelContainer.addView(switchLayout)
    }

    /**
     * 创建选项按钮
     */
    private fun createOptionButton(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(44), 1f)
            
            background = createButtonBackground(false)
            
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
        }
    }

    /**
     * 创建按钮背景
     */
    private fun createButtonBackground(selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(8f)
            if (selected) {
                setColor(0xFF2196F3.toInt())
                setStroke(dpToPx(2), 0xFF2196F3.toInt())
            } else {
                setColor(0x33FFFFFF)
                setStroke(dpToPx(1), 0x66FFFFFF)
            }
        }
    }

    /**
     * 更新推理设备按钮状态
     */
    private fun updateInferenceDeviceButtons() {
        cpuButton.background = createButtonBackground(currentSettings.inferenceDevice == InferenceDevice.CPU)
        gpuButton.background = createButtonBackground(currentSettings.inferenceDevice == InferenceDevice.GPU)
    }

    /**
     * 更新语言按钮状态
     */
    private fun updateLanguageButtons() {
        languageButtons.forEach { (language, button) ->
            button.background = createButtonBackground(currentSettings.targetLanguage == language)
        }
    }

    /**
     * 更新设置
     */
    private fun updateSettings(newSettings: SubtitleSettings) {
        val oldSettings = currentSettings
        currentSettings = newSettings
        
        if (oldSettings != newSettings) {
            Log.d(TAG, "Settings changed: $newSettings")
            onSettingsChangeListener?.onSubtitleSettingsChanged(newSettings)
        }
    }

    /**
     * dp 转 px (Int)
     */
    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    /**
     * dp 转 px (Float)
     */
    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        )
    }

    // ============================================================================
    // 公共方法
    // ============================================================================

    /**
     * 显示设置面板
     */
    fun show() {
        if (visibility == VISIBLE) return

        visibility = VISIBLE

        // 背景淡入动画
        dimBackground.alpha = 0f
        ObjectAnimator.ofFloat(dimBackground, "alpha", 0f, 1f).apply {
            duration = ANIMATION_DURATION_MS
            start()
        }

        // 面板滑入动画
        panelContainer.translationY = panelContainer.height.toFloat()
        panelContainer.post {
            ObjectAnimator.ofFloat(panelContainer, "translationY", panelContainer.height.toFloat(), 0f).apply {
                duration = ANIMATION_DURATION_MS
                start()
            }
        }

        Log.d(TAG, "Panel shown")
    }

    /**
     * 隐藏设置面板
     */
    fun dismiss() {
        if (visibility != VISIBLE) return

        // 背景淡出动画
        ObjectAnimator.ofFloat(dimBackground, "alpha", 1f, 0f).apply {
            duration = ANIMATION_DURATION_MS
            start()
        }

        // 面板滑出动画
        ObjectAnimator.ofFloat(panelContainer, "translationY", 0f, panelContainer.height.toFloat()).apply {
            duration = ANIMATION_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = GONE
                    onDismissListener?.invoke()
                }
            })
            start()
        }

        Log.d(TAG, "Panel dismissed")
    }

    /**
     * 设置当前设置值
     */
    fun setSettings(settings: SubtitleSettings) {
        currentSettings = settings
        enableSwitch.isChecked = settings.enabled
        updateInferenceDeviceButtons()
        updateLanguageButtons()
        // 更新处理间隔
        intervalSeekBar.progress = settings.processingInterval - SubtitleSettings.MIN_PROCESSING_INTERVAL
        intervalValueText.text = "${settings.processingInterval}s"
        // 更新调试模式
        debugSwitch.isChecked = settings.debugMode
    }

    /**
     * 获取当前设置值
     */
    fun getSettings(): SubtitleSettings = currentSettings

    /**
     * 设置设置变更监听器
     */
    fun setOnSettingsChangeListener(listener: OnSubtitleSettingsChangeListener?) {
        this.onSettingsChangeListener = listener
    }

    /**
     * 设置关闭监听器
     */
    fun setOnDismissListener(listener: () -> Unit) {
        this.onDismissListener = listener
    }

    /**
     * 面板是否正在显示
     */
    fun isShowing(): Boolean = visibility == VISIBLE
}

/**
 * 字幕设置变更监听器
 */
interface OnSubtitleSettingsChangeListener {
    fun onSubtitleSettingsChanged(settings: SubtitleSettings)
}
