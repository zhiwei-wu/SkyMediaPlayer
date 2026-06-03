package imt.zw.skymediaplayer.widget.control

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * 画质面板（含画质滤镜选择 + 强度调节）。
 * - 竖屏：从下往上弹；横屏：从右往左弹；内容支持滚动。
 * - 风格参考腾讯视频画质面板：暗色半透明、卡片网格、选中态金色描边、顶部为带滑杆的高亮卡片。
 */
class SkyQualityPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val ANIMATION_DURATION_MS = 280L
        private const val GOLD = 0xFFE8B96A.toInt()
        private const val TEXT_GRAY = 0xFFBBBBBB.toInt()
        private const val CARD_BG = 0x33FFFFFF
        private const val CARD_BG_SELECTED = 0x33E8A030
        private const val PANEL_BG = 0xE6141414.toInt()
    }

    /** 一个画质滤镜项 */
    data class QualityFilterItem(val id: String, val title: String, val subtitle: String? = null)

    interface OnQualityPanelListener {
        /** 选中某个滤镜 */
        fun onFilterSelected(item: QualityFilterItem)
        /** 强度调节结束（0-100） */
        fun onIntensityChanged(percent: Int)
    }

    private lateinit var dimBackground: View
    private lateinit var scrollView: ScrollView
    private lateinit var panelContainer: LinearLayout
    private lateinit var gridContainer: LinearLayout
    private lateinit var headerValue: TextView

    // 顶部高亮卡片（强度）
    private lateinit var featuredCard: LinearLayout
    private lateinit var featuredTitle: TextView
    private lateinit var intensitySeekBar: SeekBar
    private lateinit var intensityValue: TextView

    private var items: List<QualityFilterItem> = emptyList()
    private val cardViews = HashMap<String, LinearLayout>()
    private var selectedId: String? = null

    private var listener: OnQualityPanelListener? = null
    private var onDismissListener: (() -> Unit)? = null

    private var isLandscape = false

    init {
        initView()
        visibility = GONE
    }

    private fun initView() {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        dimBackground = View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setBackgroundColor(0x66000000)
            setOnClickListener { dismiss() }
        }
        addView(dimBackground)

        panelContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dpToPx(20), dpToPx(18), dpToPx(20), dpToPx(24))
        }

        // 区段标题：画质滤镜 + 当前值
        panelContainer.addView(buildSectionHeader())
        // 顶部高亮卡片（强度调节）
        panelContainer.addView(buildFeaturedCard())
        // 滤镜卡片网格
        gridContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(12) }
        }
        panelContainer.addView(gridContainer)

        scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = false
            addView(panelContainer)
        }
        addView(scrollView)
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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dpToPx(10) }
        }
        row.addView(headerValue)
        return row
    }

    private fun buildFeaturedCard(): View {
        featuredCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 顶部行：标题（左） + 百分比（右）
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        featuredTitle = TextView(context).apply {
            text = "滤镜强度"
            setTextColor(GOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(featuredTitle)
        intensityValue = TextView(context).apply {
            text = "100%"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        topRow.addView(intensityValue)
        featuredCard.addView(topRow)

        // 副标题
        featuredCard.addView(TextView(context).apply {
            text = "滑动调节滤镜浓淡"
            setTextColor(TEXT_GRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(2) }
        })

        // 整宽长滑杆
        intensitySeekBar = SeekBar(context).apply {
            max = 100
            progress = 100
            progressTintList = android.content.res.ColorStateList.valueOf(GOLD)
            thumbTintList = android.content.res.ColorStateList.valueOf(GOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(8) }
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
                    // 占位，保持列对齐
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
        // 顶部强度卡片：选了非「无」滤镜才可用
        val active = selectedId != null && selectedId != "none"
        applyFeaturedStyle(active)
        val name = items.firstOrNull { it.id == selectedId }?.title ?: "无"
        headerValue.text = "当前：$name"
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
    fun setOnDismissListener(l: () -> Unit) { onDismissListener = l }
    fun isShowing(): Boolean = visibility == VISIBLE

    /**
     * 按当前屏幕方向配置面板的位置/尺寸/圆角。
     * 用面板自身已布局的宽高（screenW/screenH）判断方向与尺寸，避免 configChanges 旋转后
     * displayMetrics/configuration 短暂滞后导致的错位。
     */
    private fun configureForOrientation(screenW: Int, screenH: Int) {
        isLandscape = screenW > screenH

        if (isLandscape) {
            // 横屏：右侧竖条，从右往左，铺满高度
            val w = minOf((screenW * 0.46f).toInt(), dpToPx(560))
            scrollView.layoutParams = LayoutParams(w, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.END
            }
            panelContainer.background = GradientDrawable().apply {
                setColor(PANEL_BG)
                cornerRadii = floatArrayOf(
                    dpToPx(16f), dpToPx(16f), 0f, 0f, 0f, 0f, dpToPx(16f), dpToPx(16f)
                )
            }
            panelContainer.minimumHeight = screenH
        } else {
            // 竖屏：底部横条，从下往上（最高 70%）
            scrollView.layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
            panelContainer.minimumHeight = 0
            panelContainer.background = GradientDrawable().apply {
                setColor(PANEL_BG)
                cornerRadii = floatArrayOf(
                    dpToPx(16f), dpToPx(16f), dpToPx(16f), dpToPx(16f), 0f, 0f, 0f, 0f
                )
            }
        }
    }

    private fun capHeightIfNeeded(screenH: Int) {
        if (isLandscape) return
        val maxH = (screenH * 0.7).toInt()
        if (scrollView.height > maxH) {
            scrollView.layoutParams = scrollView.layoutParams.apply { height = maxH }
            scrollView.requestLayout()
        }
    }

    /** 旋转时若面板正显示，按新方向重新布局并就位（不做滑入动画，避免闪烁） */
    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        if (visibility != VISIBLE) return
        post {
            configureForOrientation(width, height)
            val h = height
            scrollView.post {
                capHeightIfNeeded(h)
                scrollView.translationX = 0f
                scrollView.translationY = 0f
            }
        }
    }

    fun show() {
        if (visibility == VISIBLE) return
        visibility = VISIBLE

        dimBackground.alpha = 0f
        ObjectAnimator.ofFloat(dimBackground, "alpha", 0f, 1f).apply {
            duration = ANIMATION_DURATION_MS; start()
        }

        // 等面板自身完成一次布局，用其真实宽高配置方向/尺寸，再做滑入动画
        post {
            configureForOrientation(width, height)
            val h = height
            scrollView.post {
                capHeightIfNeeded(h)
                // 复位两个轴：上次关闭时只把某一个轴推出屏外，旋转后换了滑入方向，
                // 残留的另一轴位移会把面板顶到屏外，导致“打不开”。每次显示都先清零。
                scrollView.translationX = 0f
                scrollView.translationY = 0f
                if (isLandscape) {
                    val from = scrollView.width.toFloat()
                    scrollView.translationX = from
                    ObjectAnimator.ofFloat(scrollView, "translationX", from, 0f).apply {
                        duration = ANIMATION_DURATION_MS; start()
                    }
                } else {
                    val from = scrollView.height.toFloat()
                    scrollView.translationY = from
                    ObjectAnimator.ofFloat(scrollView, "translationY", from, 0f).apply {
                        duration = ANIMATION_DURATION_MS; start()
                    }
                }
            }
        }
    }

    fun dismiss() {
        if (visibility != VISIBLE) return
        ObjectAnimator.ofFloat(dimBackground, "alpha", 1f, 0f).apply {
            duration = ANIMATION_DURATION_MS; start()
        }
        val prop = if (isLandscape) "translationX" else "translationY"
        val to = if (isLandscape) scrollView.width.toFloat() else scrollView.height.toFloat()
        ObjectAnimator.ofFloat(scrollView, prop, 0f, to).apply {
            duration = ANIMATION_DURATION_MS
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = GONE
                    onDismissListener?.invoke()
                }
            })
            start()
        }
    }

    private fun dpToPx(dp: Int): Int = dpToPx(dp.toFloat()).toInt()
    private fun dpToPx(dp: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics
    )
}
