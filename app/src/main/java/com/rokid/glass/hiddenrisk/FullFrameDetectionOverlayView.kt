package com.rokid.glass.hiddenrisk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

internal class FullFrameDetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BOX_COLOR
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LABEL_BACKGROUND_COLOR
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * density
        style = Paint.Style.FILL
    }
    private var frame: List<AlignmentDetection> = emptyList()

    fun showDetections(detections: List<AlignmentDetection>) {
        frame = detections.toList()
        invalidate()
    }

    fun clearDetections() {
        frame = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        frame.forEach { detection ->
            if (detection.right <= detection.left || detection.bottom <= detection.top) return@forEach
            canvas.drawRect(detection.left, detection.top, detection.right, detection.bottom, boxPaint)
            drawLabel(canvas, detection)
        }
    }

    private fun drawLabel(canvas: Canvas, detection: AlignmentDetection) {
        val text = "${detection.label} ${(detection.score.coerceIn(0f, 1f) * 100).toInt()}%"
        val padding = 4f * density
        val fontMetrics = labelPaint.fontMetrics
        val labelTop = AlignmentOverlayGeometry.labelTop(detection.top)
        val labelRight = (detection.left + labelPaint.measureText(text) + padding * 2)
            .coerceAtMost(detection.right)
            .coerceAtMost(width.toFloat())
        val labelBottom = (labelTop + fontMetrics.bottom - fontMetrics.top + padding * 2)
            .coerceAtMost(detection.bottom)
        val background = RectF(detection.left, labelTop, labelRight, labelBottom)
        canvas.drawRect(background, labelBackgroundPaint)
        canvas.save()
        canvas.clipRect(background)
        canvas.drawText(text, detection.left + padding, labelTop + padding - fontMetrics.top, labelPaint)
        canvas.restore()
    }

    companion object {
        private val BOX_COLOR = Color.rgb(0, 255, 102)
        private val LABEL_BACKGROUND_COLOR = Color.argb(220, 0, 96, 48)
    }
}
