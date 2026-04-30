package com.rokid.glass.hiddenrisk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * 隐患录入倒计时页的四角取景提示框，仅作静态引导，不承载实时预览。
 */
class HazardRecordFrameOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF66FF73.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(3f)
        strokeCap = Paint.Cap.SQUARE
    }
    private val cornerLength = dpToPx(32f)
    private val inset = dpToPx(2f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = inset
        val top = inset
        val right = width - inset
        val bottom = height - inset

        canvas.drawLine(left, top, left + cornerLength, top, paint)
        canvas.drawLine(left, top, left, top + cornerLength, paint)

        canvas.drawLine(right, top, right - cornerLength, top, paint)
        canvas.drawLine(right, top, right, top + cornerLength, paint)

        canvas.drawLine(left, bottom, left + cornerLength, bottom, paint)
        canvas.drawLine(left, bottom, left, bottom - cornerLength, paint)

        canvas.drawLine(right, bottom, right - cornerLength, bottom, paint)
        canvas.drawLine(right, bottom, right, bottom - cornerLength, paint)
    }

    private fun dpToPx(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics,
        )
    }
}
