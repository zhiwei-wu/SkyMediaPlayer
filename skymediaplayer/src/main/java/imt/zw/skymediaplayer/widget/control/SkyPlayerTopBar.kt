package imt.zw.skymediaplayer.widget.control

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 顶部栏组件
 * 最左侧为返回按钮（<），显示/隐藏时机与底部播控栏一致
 */
class SkyPlayerTopBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private lateinit var backButton: TextView
    private var onBackButtonClickListener: OnClickListener? = null

    init {
        initView()
    }

    private fun initView() {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0xCC000000.toInt())
        setPadding(dpToPx(8), dpToPx(28), dpToPx(16), dpToPx(8))

        // 返回按钮
        backButton = TextView(context).apply {
            text = "退出"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(dpToPx(64), dpToPx(48))

            val outValue = TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                outValue,
                true
            )
            setBackgroundResource(outValue.resourceId)
            isClickable = true
            isFocusable = true
            contentDescription = "退出"

            setOnClickListener { onBackButtonClickListener?.onClick(it) }
        }
        addView(backButton)
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    fun setOnBackButtonClickListener(listener: OnClickListener?) {
        this.onBackButtonClickListener = listener
    }
}
