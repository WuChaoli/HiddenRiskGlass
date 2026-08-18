package com.rokid.glass.hiddenrisk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

internal class AlignmentDetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private data class FrameDetections(
        val imageWidth: Int,
        val imageHeight: Int,
        val items: List<AlignmentDetection>,
    )

    private val density = resources.displayMetrics.density
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BOX_COLOR
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
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
    private var frame = FrameDetections(1, 1, emptyList())

    fun showDetections(imageWidth: Int, imageHeight: Int, detections: List<AlignmentDetection>) {
        if (imageWidth <= 0 || imageHeight <= 0) return
        frame = FrameDetections(imageWidth, imageHeight, detections.toList())
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = frame
        current.items.forEach { detection ->
            val mapped = AlignmentDetectionMapper.mapToScreen(
                detection = detection,
                imageWidth = current.imageWidth,
                imageHeight = current.imageHeight,
                screenWidth = width,
                screenHeight = height,
            )
            if (mapped.right <= mapped.left || mapped.bottom <= mapped.top) return@forEach
            canvas.drawRect(mapped.left, mapped.top, mapped.right, mapped.bottom, boxPaint)
            drawLabel(canvas, mapped)
        }
    }

    private fun drawLabel(canvas: Canvas, detection: AlignmentDetection) {
        val text = "${detection.label} ${(detection.score.coerceIn(0f, 1f) * 100).toInt()}%"
        val padding = 4f * density
        val textWidth = labelPaint.measureText(text)
        val fontMetrics = labelPaint.fontMetrics
        val labelHeight = fontMetrics.bottom - fontMetrics.top + padding * 2
        val labelTop = max(0f, detection.top - labelHeight)
        val labelRight = (detection.left + textWidth + padding * 2).coerceAtMost(width.toFloat())
        val background = RectF(detection.left, labelTop, labelRight, labelTop + labelHeight)
        canvas.drawRect(background, labelBackgroundPaint)
        canvas.drawText(text, detection.left + padding, labelTop + padding - fontMetrics.top, labelPaint)
    }

    companion object {
        private val BOX_COLOR = Color.rgb(0, 255, 102)
        private val LABEL_BACKGROUND_COLOR = Color.argb(220, 0, 96, 48)
    }
}
