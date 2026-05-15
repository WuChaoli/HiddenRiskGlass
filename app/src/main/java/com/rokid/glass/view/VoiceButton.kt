package com.rokid.glass.view

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatTextView
import com.rokid.glesse.R

class VoiceButton @JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context!!, attrs, defStyleAttr) {
    init {
        init(attrs)
    }

    var key: String? = ""
    var isFocus: Boolean = false
    var isIconButton: Boolean = false

    private fun init(attrs: AttributeSet?) {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) // 12sp
        setPadding(
            dpToPx(2),  //
            dpToPx(2),  //
            dpToPx(6),  //
            dpToPx(2),  //
        )
        // 设置文本颜色选择器
        setTextColor(resources.getColor(R.color.green))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)

        // 确保点击效果
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
    }


    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
