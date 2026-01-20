package imt.zw.skymediaplayer.widget

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.MediaController
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet

/**
 * 自定义 MediaController，在原生播控栏下方添加额外的控制栏
 * 额外控制栏使用 ConstraintLayout，方便后续扩展更多按钮
 *
 * 注意：必须使用 MediaController(Context, Boolean) 构造函数，
 * 使用 MediaController(Context, AttributeSet) 会导致 mAnchorView 初始化问题
 */
class SkyMediaController(context: Context) : MediaController(context, true) {

    companion object {
        private const val TAG = "SkyMediaController"
    }

    // 额外控制栏容器
    private var extraControlsView: ConstraintLayout? = null
    
    // AI 字幕按钮
    private var subtitleButton: ImageButton? = null
    
    // 字幕开关状态
    private var isSubtitleEnabled: Boolean = false
    
    // 字幕开关回调
    private var onSubtitleToggleListener: OnSubtitleToggleListener? = null

    /**
     * 字幕开关回调接口
     */
    interface OnSubtitleToggleListener {
        /**
         * 字幕开关状态变化
         * @param enabled 是否启用
         */
        fun onSubtitleToggle(enabled: Boolean)
    }

    override fun setAnchorView(view: View?) {
        super.setAnchorView(view)
        
        // 在 setAnchorView 后创建额外控制栏
        createExtraControlsView()
    }

    /**
     * 创建额外控制栏
     */
    private fun createExtraControlsView() {
        if (extraControlsView != null) {
            return
        }

        val dp48 = dpToPx(48)
        val dp16 = dpToPx(16)
        val dp8 = dpToPx(8)

        // 创建 ConstraintLayout 作为额外控制栏容器
        extraControlsView = ConstraintLayout(context).apply {
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp48
            ).apply {
                topMargin = dp8
            }
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp16, 0, dp16, 0)
        }

        // 创建 AI 字幕按钮
        subtitleButton = ImageButton(context).apply {
            id = View.generateViewId()
            // 使用系统图标作为占位，实际项目中应替换为自定义图标
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundResource(getSelectableItemBackgroundResource())
            contentDescription = "AI字幕"
            setOnClickListener {
                toggleSubtitle()
            }
            updateSubtitleButtonState()
        }

        // 将字幕按钮添加到 ConstraintLayout
        extraControlsView?.addView(subtitleButton, ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.WRAP_CONTENT,
            ConstraintLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        })

        // 将额外控制栏添加到 MediaController
        // MediaController 内部使用 FrameLayout，我们需要找到合适的位置添加
        addView(extraControlsView)

        Log.i(TAG, "Extra controls view created")
    }

    /**
     * 切换字幕开关状态
     */
    private fun toggleSubtitle() {
        isSubtitleEnabled = !isSubtitleEnabled
        updateSubtitleButtonState()
        onSubtitleToggleListener?.onSubtitleToggle(isSubtitleEnabled)
        Log.i(TAG, "Subtitle toggled: $isSubtitleEnabled")
    }

    /**
     * 更新字幕按钮状态
     */
    private fun updateSubtitleButtonState() {
        subtitleButton?.apply {
            alpha = if (isSubtitleEnabled) 1.0f else 0.5f
            // 可以根据状态切换不同的图标
            // setImageResource(if (isSubtitleEnabled) R.drawable.ic_subtitle_on else R.drawable.ic_subtitle_off)
        }
    }

    /**
     * 设置字幕开关回调
     */
    fun setOnSubtitleToggleListener(listener: OnSubtitleToggleListener?) {
        onSubtitleToggleListener = listener
    }

    /**
     * 设置字幕开关状态（不触发回调）
     */
    fun setSubtitleEnabled(enabled: Boolean) {
        if (isSubtitleEnabled != enabled) {
            isSubtitleEnabled = enabled
            updateSubtitleButtonState()
        }
    }

    /**
     * 获取字幕开关状态
     */
    fun isSubtitleEnabled(): Boolean = isSubtitleEnabled

    override fun show() {
        super.show()
        extraControlsView?.visibility = View.VISIBLE
    }

    override fun show(timeout: Int) {
        super.show(timeout)
        extraControlsView?.visibility = View.VISIBLE
    }

    override fun hide() {
        super.hide()
        extraControlsView?.visibility = View.GONE
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

    /**
     * 获取可选择项背景资源
     */
    private fun getSelectableItemBackgroundResource(): Int {
        val outValue = TypedValue()
        context.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless,
            outValue,
            true
        )
        return outValue.resourceId
    }
}
