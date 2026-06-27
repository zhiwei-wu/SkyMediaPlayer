package imt.zw.skymediaplayer.widget.control

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * A/B 对比分屏滑动条：覆盖在视频上，左侧原图 / 右侧滤镜，拖动竖向分隔条调节分界。
 * 仅负责 UI（竖线 + 圆形把手）与拖动，分界归一化值（0..1）通过 [onSplitChanged] 回调上抛。
 */
class SkyCompareSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 分界回调（0..1） */
    var onSplitChanged: ((Float) -> Unit)? = null

    /** 当前分界，归一化 0..1 */
    var split: Float = 0.5f
        private set

    // 视频实际区域（SurfaceView 居中可能留黑边），拖动/绘制均基于此区域而非全屏
    private var contentLeft = 0f
    private var contentWidth = 0f
    private fun left() = if (contentWidth > 0f) contentLeft else 0f
    private fun span() = if (contentWidth > 0f) contentWidth else width.toFloat()

    /** 设置视频内容区（左边距与宽度，px），分界按此映射对齐画面 */
    fun setContentBounds(left: Float, w: Float) {
        contentLeft = left
        contentWidth = w
        invalidate()
    }

    private val lineWidth = dp(2f)
    private val handleRadius = dp(16f)
    private val touchSlop = dp(24f)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(dp(3f), 0f, 0f, 0x99000000.toInt())
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000
        style = Paint.Style.FILL
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        setShadowLayer(dp(3f), 0f, 0f, 0x99000000.toInt())
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // 阴影需软件层
    }

    /** 设置分界并刷新（不回调），范围 0..1 */
    fun setSplit(value: Float) {
        split = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val x = (left() + split * span()).coerceIn(0f, width.toFloat())
        val cy = height / 2f
        canvas.drawRect(x - lineWidth / 2, 0f, x + lineWidth / 2, height.toFloat(), linePaint)
        canvas.drawCircle(x, cy, handleRadius, handleFill)
        canvas.drawCircle(x, cy, handleRadius, handleStroke)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val x = left() + split * span()
                if (abs(event.x - x) > touchSlop) return false // 远离分隔条，交还其它交互
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                split = ((event.x - left()) / span()).coerceIn(0f, 1f)
                invalidate()
                onSplitChanged?.invoke(split)
                return true
            }
        }
        return false
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
