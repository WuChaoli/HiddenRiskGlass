package com.rokid.glass.hiddenrisk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.rokid.glass.utils.dpToPx

/**
 * 正方形取景框描边。
 */
class SquareViewfinderOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00FF66.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(3f)
    }

    private val frameRect = RectF()
    private val cornerRadius = dpToPx(14f)
    private val frameInset = dpToPx(2f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) {
            frameRect.setEmpty()
            return
        }
        frameRect.set(frameInset, frameInset, w - frameInset, h - frameInset)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frameRect.isEmpty) {
            return
        }
        canvas.drawRoundRect(frameRect, cornerRadius, cornerRadius, strokePaint)
    }

}
