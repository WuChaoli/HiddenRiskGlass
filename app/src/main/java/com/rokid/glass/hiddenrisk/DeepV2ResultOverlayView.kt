package com.rokid.glass.hiddenrisk

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

internal data class DeepV2OverlayBox(
    val labelId: String,
    val label: String,
    val highestLevel: String,
    val rect: RectFModel,
)

internal class DeepV2ResultOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val thinPaint = framePaint(THIN_STROKE_DP)
    private val cornerPaint = framePaint(CORNER_STROKE_DP).apply { strokeJoin = Paint.Join.ROUND }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BOX_COLOR }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LABEL_BACKGROUND_COLOR
    }
    private val labelPaint = textPaint(11f)
    private val levelPaint = textPaint(9f)
    private val cornerPath = Path()
    private val excludedCardPath = Path()
    private var boxes: List<DeepV2OverlayBox> = emptyList()
    private var selectedLabelId: String? = null
    private var previousSelectedLabelId: String? = null
    private var selectionProgress = 1f
    private var selectionAnimator: ValueAnimator? = null
    private var excludedCardRect: RectF? = null

    fun setBoxes(boxes: List<DeepV2OverlayBox>) {
        this.boxes = boxes.toList()
        if (selectedLabelId !in boxes.map(DeepV2OverlayBox::labelId)) {
            selectedLabelId = null
            previousSelectedLabelId = null
        }
        invalidate()
    }

    fun setSelectedLabelId(labelId: String?, animate: Boolean) {
        val validLabelId = labelId?.takeIf { id -> boxes.any { it.labelId == id } }
        if (validLabelId == selectedLabelId) return
        selectionAnimator?.cancel()
        previousSelectedLabelId = selectedLabelId
        selectedLabelId = validLabelId
        if (!animate) {
            selectionProgress = 1f
            previousSelectedLabelId = null
            invalidate()
            return
        }
        selectionProgress = 0f
        selectionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SELECTION_ANIMATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                selectionProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    previousSelectedLabelId = null
                    selectionProgress = 1f
                    invalidate()
                }
            })
            start()
        }
    }

    fun setExcludedCardRect(rect: RectF?) {
        excludedCardRect = rect?.let(::RectF)
        invalidate()
    }

    fun clear() {
        selectionAnimator?.cancel()
        selectionAnimator = null
        boxes = emptyList()
        selectedLabelId = null
        previousSelectedLabelId = null
        excludedCardRect = null
        selectionProgress = 1f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        selectionAnimator?.cancel()
        selectionAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        excludedCardRect?.let {
            excludedCardPath.reset()
            excludedCardPath.addRoundRect(
                it,
                CARD_RADIUS_DP * density,
                CARD_RADIUS_DP * density,
                Path.Direction.CW,
            )
            @Suppress("DEPRECATION")
            canvas.clipPath(excludedCardPath, Region.Op.DIFFERENCE)
        }
        boxes.forEach { drawBox(canvas, it) }
        canvas.restore()
    }

    private fun drawBox(canvas: Canvas, box: DeepV2OverlayBox) {
        val selectedFraction = when (box.labelId) {
            selectedLabelId -> selectionProgress
            previousSelectedLabelId -> 1f - selectionProgress
            else -> 0f
        }
        val rect = DeepV2OverlayGeometry.expandAroundCenter(
            box.rect,
            1f + SELECTED_SCALE_DELTA * selectedFraction,
        )
        val shape = DeepV2BBoxGeometry.compute(
            rect,
            CORNER_LENGTH_DP * density,
            CORNER_RADIUS_DP * density,
        )
        drawFrame(canvas, shape)
        drawDots(canvas, shape)
        drawLabel(canvas, box, rect)
    }

    private fun drawFrame(canvas: Canvas, shape: DeepV2BBoxShape) {
        val rect = shape.rect
        val length = shape.cornerLength
        val radius = shape.cornerRadius
        canvas.drawLine(rect.left + length, rect.top, rect.right - length, rect.top, thinPaint)
        canvas.drawLine(rect.right, rect.top + length, rect.right, rect.bottom - length, thinPaint)
        canvas.drawLine(rect.left + length, rect.bottom, rect.right - length, rect.bottom, thinPaint)
        canvas.drawLine(rect.left, rect.top + length, rect.left, rect.bottom - length, thinPaint)

        cornerPath.reset()
        cornerPath.moveTo(rect.left + length, rect.top)
        cornerPath.lineTo(rect.left, rect.top)
        cornerPath.lineTo(rect.left, rect.top + length)
        cornerPath.moveTo(rect.right - length, rect.top)
        cornerPath.lineTo(rect.right - radius, rect.top)
        cornerPath.quadTo(rect.right, rect.top, rect.right, rect.top + radius)
        cornerPath.lineTo(rect.right, rect.top + length)
        cornerPath.moveTo(rect.right, rect.bottom - length)
        cornerPath.lineTo(rect.right, rect.bottom - radius)
        cornerPath.quadTo(rect.right, rect.bottom, rect.right - radius, rect.bottom)
        cornerPath.lineTo(rect.right - length, rect.bottom)
        cornerPath.moveTo(rect.left + length, rect.bottom)
        cornerPath.lineTo(rect.left + radius, rect.bottom)
        cornerPath.quadTo(rect.left, rect.bottom, rect.left, rect.bottom - radius)
        cornerPath.lineTo(rect.left, rect.bottom - length)
        canvas.drawPath(cornerPath, cornerPaint)
    }

    private fun drawDots(canvas: Canvas, shape: DeepV2BBoxShape) {
        val radius = DOT_DIAMETER_DP * density / 2f
        val step = (DOT_DIAMETER_DP + DOT_GAP_DP) * density
        val startY = shape.rect.bottom - shape.cornerLength - step
        repeat(3) { index ->
            canvas.drawCircle(shape.rect.left, startY - index * step, radius, dotPaint)
        }
    }

    private fun drawLabel(canvas: Canvas, box: DeepV2OverlayBox, rect: RectFModel) {
        val paddingX = 5f * density
        val paddingY = 3f * density
        val gap = density
        val labelHeight = labelPaint.fontMetrics.bottom - labelPaint.fontMetrics.top
        val levelHeight = levelPaint.fontMetrics.bottom - levelPaint.fontMetrics.top
        val contentWidth = maxOf(labelPaint.measureText(box.label), levelPaint.measureText(box.highestLevel))
        val left = rect.left + 5f * density
        val top = rect.top + 5f * density
        val background = RectF(
            left,
            top,
            minOf(rect.right, left + contentWidth + paddingX * 2f),
            minOf(rect.bottom, top + paddingY * 2f + labelHeight + gap + levelHeight),
        )
        if (background.width() <= 0f || background.height() <= 0f) return
        canvas.drawRoundRect(background, 6f * density, 6f * density, labelBackgroundPaint)
        canvas.save()
        canvas.clipRect(background)
        val labelBaseline = background.top + paddingY - labelPaint.fontMetrics.top
        canvas.drawText(box.label, background.left + paddingX, labelBaseline, labelPaint)
        canvas.drawText(box.highestLevel, background.left + paddingX, labelBaseline + labelHeight + gap, levelPaint)
        canvas.restore()
    }

    private fun framePaint(strokeDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BOX_COLOR
        style = Paint.Style.STROKE
        strokeWidth = strokeDp * density
    }

    private fun textPaint(sizeSp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BOX_COLOR
        textSize = sizeSp * density
        isFakeBoldText = true
    }

    private companion object {
        const val SELECTION_ANIMATION_MS = 220L
        const val SELECTED_SCALE_DELTA = 0.10f
        const val THIN_STROKE_DP = 1f
        const val CORNER_STROKE_DP = 4f
        const val CORNER_LENGTH_DP = 18f
        const val CORNER_RADIUS_DP = 10f
        const val DOT_DIAMETER_DP = 2f
        const val DOT_GAP_DP = 2f
        const val CARD_RADIUS_DP = 16f
        val BOX_COLOR: Int = Color.rgb(0, 255, 102)
        val LABEL_BACKGROUND_COLOR: Int = Color.argb(194, 0, 55, 23)
    }
}
