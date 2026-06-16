package imt.zw.skymediaplayer.widget.control

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * 播控弹出面板统一规范基类。
 *
 * 规范（后续播控弹出的控制面板都按此适配）：
 * - 竖屏：从下往上弹（顶部圆角，最高占屏 70%）；横屏：从右往左弹（右侧整高、约 46% 宽、左侧圆角）。
 * - 内容区 [panelContainer] 由子类在自身 init 块中填充；整体置于 ScrollView 内，可滚动。
 * - 半透明遮罩 + 点击空白处关闭；显示/关闭带淡入淡出 + 滑动动画。
 * - 旋转适配：每次 [show] 复位两轴平移（避免上次关闭残留的另一轴位移把面板顶到屏外）；
 *   显示中旋转会自动按新方向重新布局并就位。
 *
 * 用法：子类继承本类，在自身 init 块里向 [panelContainer] 添加标题与内容，并实现各自业务 API。
 */
abstract class SkySlidePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val animDuration = 280L
    private val panelBgOpaque = 0xE6141414.toInt()
    private val panelBgTransparent = 0x00000000           // 透明模式：面板全透明，完全露出背后视频
    private var transparent = false
    private fun panelBg() = if (transparent) panelBgTransparent else panelBgOpaque
    private fun dimColor() = if (transparent) 0x00000000 else 0x66000000

    private val dimBackground: View
    private val scrollView: ScrollView

    /** 内容容器：子类向其添加标题/分区/控件 */
    protected val panelContainer: LinearLayout

    private var isLandscape = false
    private var onDismissListener: (() -> Unit)? = null

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

        dimBackground = View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setBackgroundColor(dimColor())
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

        scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = false
            addView(panelContainer)
        }
        addView(scrollView)

        visibility = GONE
    }

    /** 用面板自身已布局的真实宽高判方向与尺寸，避免 configChanges 旋转后 metrics/config 短暂滞后导致错位 */
    private fun configureForOrientation(screenW: Int, screenH: Int) {
        isLandscape = screenW > screenH
        if (isLandscape) {
            val w = minOf((screenW * 0.46f).toInt(), dpToPx(560))
            scrollView.layoutParams = LayoutParams(w, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.END
            }
            panelContainer.minimumHeight = screenH
            panelContainer.background = GradientDrawable().apply {
                setColor(panelBg())
                cornerRadii = floatArrayOf(
                    dpToPx(16f), dpToPx(16f), 0f, 0f, 0f, 0f, dpToPx(16f), dpToPx(16f)
                )
            }
        } else {
            scrollView.layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
            panelContainer.minimumHeight = 0
            panelContainer.background = GradientDrawable().apply {
                setColor(panelBg())
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
            duration = animDuration; start()
        }

        // 等面板自身完成一次布局，用其真实宽高配置方向/尺寸，再做滑入动画
        post {
            configureForOrientation(width, height)
            val h = height
            scrollView.post {
                capHeightIfNeeded(h)
                // 复位两轴：上次关闭只把某一轴推出屏外，旋转后换了滑入方向会导致残留位移把面板顶出屏外
                scrollView.translationX = 0f
                scrollView.translationY = 0f
                if (isLandscape) {
                    val from = scrollView.width.toFloat()
                    scrollView.translationX = from
                    ObjectAnimator.ofFloat(scrollView, "translationX", from, 0f).apply {
                        duration = animDuration; start()
                    }
                } else {
                    val from = scrollView.height.toFloat()
                    scrollView.translationY = from
                    ObjectAnimator.ofFloat(scrollView, "translationY", from, 0f).apply {
                        duration = animDuration; start()
                    }
                }
            }
        }
    }

    fun dismiss() {
        if (visibility != VISIBLE) return
        ObjectAnimator.ofFloat(dimBackground, "alpha", 1f, 0f).apply {
            duration = animDuration; start()
        }
        val prop = if (isLandscape) "translationX" else "translationY"
        val to = if (isLandscape) scrollView.width.toFloat() else scrollView.height.toFloat()
        ObjectAnimator.ofFloat(scrollView, prop, 0f, to).apply {
            duration = animDuration
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = GONE
                    onDismissListener?.invoke()
                }
            })
            start()
        }
    }

    /** 面板透明模式：开启后面板背景与遮罩降透明，调节滤镜时可立即看到背后视频效果 */
    fun setPanelTransparent(on: Boolean) {
        if (transparent == on) return
        transparent = on
        dimBackground.setBackgroundColor(dimColor())
        panelContainer.background = GradientDrawable().apply {
            setColor(panelBg())
            cornerRadii = if (isLandscape) {
                floatArrayOf(dpToPx(16f), dpToPx(16f), 0f, 0f, 0f, 0f, dpToPx(16f), dpToPx(16f))
            } else {
                floatArrayOf(dpToPx(16f), dpToPx(16f), dpToPx(16f), dpToPx(16f), 0f, 0f, 0f, 0f)
            }
        }
    }

    fun isShowing(): Boolean = visibility == VISIBLE

    fun setOnDismissListener(listener: () -> Unit) { onDismissListener = listener }

    protected fun dpToPx(dp: Int): Int = dpToPx(dp.toFloat()).toInt()
    protected fun dpToPx(dp: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics
    )
}
