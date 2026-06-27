package imt.zw.skymediaplayer.widget.control

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * 画质面板（画质滤镜选择 + 强度调节）。弹出/方向适配遵循 [SkySlidePanel] 规范。
 * 风格参考腾讯视频画质面板：暗色半透明、卡片网格、选中态金色描边、顶部为带滑杆的高亮卡片。
 */
class SkyQualityPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SkySlidePanel(context, attrs, defStyleAttr) {

    companion object {
        private const val GOLD = 0xFFE8B96A.toInt()
        private const val TEXT_GRAY = 0xFFBBBBBB.toInt()
        private const val CARD_BG = 0x33FFFFFF
        private const val CARD_BG_SELECTED = 0x33E8A030
    }

    /** 一个画质滤镜项 */
    data class QualityFilterItem(val id: String, val title: String, val subtitle: String? = null)

    interface OnQualityPanelListener {
        /** 选中某个滤镜 */
        fun onFilterSelected(item: QualityFilterItem)
        /** 强度调节结束（0-100） */
        fun onIntensityChanged(percent: Int)
        /** 画质增强参数调节结束（各 0-100，0=关闭） */
        fun onEnhanceChanged(sharpness: Int, deband: Int)
        /** A/B 对比分屏开关切换（左原图右滤镜） */
        fun onCompareToggle(enabled: Boolean)
    }

    private lateinit var gridContainer: LinearLayout
    private lateinit var headerValue: TextView
    private lateinit var featuredCard: LinearLayout
    private lateinit var featuredTitle: TextView
    private lateinit var intensitySeekBar: SeekBar
    private lateinit var intensityValue: TextView
    private lateinit var enhanceSharpnessBar: SeekBar
    private lateinit var enhanceDebandBar: SeekBar

    private var items: List<QualityFilterItem> = emptyList()
    private val cardViews = HashMap<String, LinearLayout>()
    private var selectedId: String? = null
    private var listener: OnQualityPanelListener? = null

    init {
        panelContainer.addView(buildSectionHeader())
        gridContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        panelContainer.addView(gridContainer)
        panelContainer.addView(buildFeaturedCard())
        panelContainer.addView(buildSimpleHeader("画质增强"))
        enhanceSharpnessBar = addEnhanceRow("锐化", "低码率糊源更清晰（CAS）")
        enhanceDebandBar = addEnhanceRow("去色带", "平滑渐变处的压缩色带")
    }

    private fun buildSectionHeader(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(12) }
        }
        row.addView(TextView(context).apply {
            text = "画质滤镜"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        headerValue = TextView(context).apply {
            text = ""
            setTextColor(TEXT_GRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dpToPx(10) }
        }
        row.addView(headerValue)
        // 对比开关：开启后左原图右滤镜，拖动竖条对比效果差异；自动联动面板透明以便观察
        row.addView(TextView(context).apply {
            text = "对比"
            setTextColor(TEXT_GRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        row.addView(Switch(context).apply {
            isChecked = false
            setOnCheckedChangeListener { _, checked ->
                if (checked) setPanelTransparent(true)
                listener?.onCompareToggle(checked)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dpToPx(10) }
        })
        // 面板透明开关：默认关；打开后面板/遮罩透明，调节滤镜时可立即看到背后视频
        row.addView(TextView(context).apply {
            text = "面板透明"
            setTextColor(TEXT_GRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        row.addView(Switch(context).apply {
            isChecked = false
            setOnCheckedChangeListener { _, checked -> setPanelTransparent(checked) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dpToPx(6) }
        })
        return row
    }

    private fun buildFeaturedCard(): View {
        // 单行紧凑卡：标题 + 滑杆 + 百分比，高度对齐滤镜卡片（64dp）
        featuredCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(16), 0, dpToPx(16), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(64)
            ).apply { topMargin = dpToPx(10) }
        }
        featuredTitle = TextView(context).apply {
            text = "滤镜强度"
            setTextColor(GOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        featuredCard.addView(featuredTitle)
        intensitySeekBar = SeekBar(context).apply {
            max = 100
            progress = 100
            progressTintList = android.content.res.ColorStateList.valueOf(GOLD)
            thumbTintList = android.content.res.ColorStateList.valueOf(GOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dpToPx(12); rightMargin = dpToPx(12) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    intensityValue.text = "$p%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    listener?.onIntensityChanged(sb?.progress ?: 100)
                }
            })
        }
        featuredCard.addView(intensitySeekBar)
        intensityValue = TextView(context).apply {
            text = "100%"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        featuredCard.addView(intensityValue)
        featuredCard.visibility = View.GONE  // 无滤镜时隐藏，选中真滤镜才显示
        applyFeaturedStyle(false)
        return featuredCard
    }

    private fun applyFeaturedStyle(active: Boolean) {
        featuredCard.background = GradientDrawable().apply {
            cornerRadius = dpToPx(10f)
            setColor(if (active) CARD_BG_SELECTED else CARD_BG)
            setStroke(dpToPx(if (active) 2 else 1), if (active) GOLD else 0x33FFFFFF)
        }
        featuredTitle.setTextColor(if (active) GOLD else Color.WHITE)
        intensitySeekBar.isEnabled = active
        intensityValue.alpha = if (active) 1f else 0.4f
    }

    /** 设置滤镜列表并重建网格（每行 3 个） */
    fun setFilterItems(list: List<QualityFilterItem>) {
        items = list
        gridContainer.removeAllViews()
        cardViews.clear()

        var i = 0
        while (i < list.size) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dpToPx(10) }
            }
            for (col in 0 until 3) {
                if (i < list.size) {
                    row.addView(buildCard(list[i]))
                } else {
                    row.addView(View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    })
                }
                i++
            }
            gridContainer.addView(row)
        }
        refreshSelection()
    }

    private fun buildCard(item: QualityFilterItem): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(64), 1f).apply {
                leftMargin = dpToPx(5); rightMargin = dpToPx(5)
            }
            isClickable = true
            setOnClickListener {
                selectedId = item.id
                refreshSelection()
                listener?.onFilterSelected(item)
            }
        }
        card.addView(TextView(context).apply {
            text = item.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            tag = "title"
        })
        if (!item.subtitle.isNullOrEmpty()) {
            card.addView(TextView(context).apply {
                text = item.subtitle
                setTextColor(TEXT_GRAY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dpToPx(2) }
            })
        }
        cardViews[item.id] = card
        return card
    }

    private fun refreshSelection() {
        for ((id, card) in cardViews) {
            val selected = id == selectedId
            card.background = GradientDrawable().apply {
                cornerRadius = dpToPx(8f)
                setColor(if (selected) CARD_BG_SELECTED else CARD_BG)
                setStroke(dpToPx(if (selected) 2 else 0), if (selected) GOLD else Color.TRANSPARENT)
            }
            (card.findViewWithTag<TextView>("title"))?.setTextColor(if (selected) GOLD else Color.WHITE)
        }
        val active = selectedId != null && selectedId != "none"
        featuredCard.visibility = if (active) View.VISIBLE else View.GONE
        applyFeaturedStyle(active)
        val name = items.firstOrNull { it.id == selectedId }?.title ?: "无"
        headerValue.text = "当前：$name"
    }

    private fun buildSimpleHeader(title: String): View {
        return TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(18) }
        }
    }

    /** 添加一行增强滑杆卡片（标题 + 百分比 + 副标题 + 滑杆），返回滑杆引用 */
    private fun addEnhanceRow(title: String, subtitle: String): SeekBar {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(10) }
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(10f)
                setColor(CARD_BG)
                setStroke(dpToPx(1), 0x33FFFFFF)
            }
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        topRow.addView(TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val valueText = TextView(context).apply {
            text = "0%"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        topRow.addView(valueText)
        card.addView(topRow)

        card.addView(TextView(context).apply {
            text = subtitle
            setTextColor(TEXT_GRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(2) }
        })

        val bar = SeekBar(context).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(GOLD)
            thumbTintList = android.content.res.ColorStateList.valueOf(GOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(6) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    valueText.text = "$p%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    notifyEnhanceChanged()
                }
            })
        }
        card.addView(bar)
        panelContainer.addView(card)
        return bar
    }

    private fun notifyEnhanceChanged() {
        listener?.onEnhanceChanged(
            enhanceSharpnessBar.progress,
            enhanceDebandBar.progress
        )
    }

    /** 设置增强参数（各 0-100），仅更新 UI 不回调 */
    fun setEnhanceValues(sharpness: Int, deband: Int) {
        if (!::enhanceSharpnessBar.isInitialized) return
        enhanceSharpnessBar.progress = sharpness.coerceIn(0, 100)
        enhanceDebandBar.progress = deband.coerceIn(0, 100)
    }

    /** 设置当前选中滤镜 */
    fun setSelectedFilter(id: String?) {
        selectedId = id
        if (::gridContainer.isInitialized) refreshSelection()
    }

    /** 设置强度（0-100） */
    fun setIntensity(percent: Int) {
        if (::intensitySeekBar.isInitialized) {
            intensitySeekBar.progress = percent.coerceIn(0, 100)
            intensityValue.text = "${intensitySeekBar.progress}%"
        }
    }

    fun setOnQualityPanelListener(l: OnQualityPanelListener?) { listener = l }
}
