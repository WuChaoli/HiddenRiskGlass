package com.rokid.glass.hiddenrisk

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BOX_COLOR
        style = Paint.Style.STROKE
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LABEL_BACKGROUND_COLOR
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        style = Paint.Style.FILL
        isFakeBoldText = true
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LEVEL_COLOR
        textSize = 11f * density
        style = Paint.Style.FILL
        isFakeBoldText = true
    }
    private var boxes: List<DeepV2OverlayBox> = emptyList()
    private var selectedLabelId: String? = null
    private var previousSelectedLabelId: String? = null
    private var selectionProgress = 1f
    private var selectionAnimator: ValueAnimator? = null

    fun setBoxes(boxes: List<DeepV2OverlayBox>) {
        this.boxes = boxes.toList()
        if (selectedLabelId !in boxes.map(DeepV2OverlayBox::labelId)) {
            selectedLabelId = null
            previousSelectedLabelId = null
        }
        invalidate()
    }

    fun setSelectedLabelId(labelId: String?, animate: Boolean) {
        val validLabelId = labelId?.takeIf { candidate -> boxes.any { it.labelId == candidate } }
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
            addUpdateListener { animator ->
                selectionProgress = animator.animatedValue as Float
                invalidate()
            }
            doOnEnd {
                previousSelectedLabelId = null
                selectionProgress = 1f
                invalidate()
            }
            start()
        }
    }

    fun clear() {
        selectionAnimator?.cancel()
        selectionAnimator = null
        boxes = emptyList()
        selectedLabelId = null
        previousSelectedLabelId = null
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
        boxes.forEach { box -> drawBox(canvas, box) }
    }

    private fun drawBox(canvas: Canvas, box: DeepV2OverlayBox) {
        val selectedFraction = when (box.labelId) {
            selectedLabelId -> selectionProgress
            previousSelectedLabelId -> 1f - selectionProgress
            else -> 0f
        }
        val scale = 1f + SELECTED_SCALE_DELTA * selectedFraction
        val displayRect = DeepV2OverlayGeometry.expandAroundCenter(box.rect, scale)
        boxPaint.strokeWidth = density * (
            UNSELECTED_STROKE_DP +
                (SELECTED_STROKE_DP - UNSELECTED_STROKE_DP) * selectedFraction
            )
        canvas.drawRect(displayRect.toRectF(), boxPaint)
        drawLabel(canvas, box, displayRect)
    }

    private fun drawLabel(canvas: Canvas, box: DeepV2OverlayBox, rect: RectFModel) {
        val padding = 4f * density
        val lineSpacing = 2f * density
        val labelHeight = labelPaint.fontMetrics.bottom - labelPaint.fontMetrics.top
        val levelHeight = levelPaint.fontMetrics.bottom - levelPaint.fontMetrics.top
        val contentWidth = maxOf(
            labelPaint.measureText(box.label),
            levelPaint.measureText(box.highestLevel),
        )
        val background = RectF(
            rect.left,
            rect.top,
            (rect.left + contentWidth + padding * 2).coerceAtMost(rect.right),
            (rect.top + padding * 2 + labelHeight + lineSpacing + levelHeight).coerceAtMost(rect.bottom),
        )
        if (background.width() <= 0f || background.height() <= 0f) return
        canvas.drawRect(background, labelBackgroundPaint)
        canvas.save()
        canvas.clipRect(background)
        val labelBaseline = background.top + padding - labelPaint.fontMetrics.top
        canvas.drawText(box.label, background.left + padding, labelBaseline, labelPaint)
        val levelBaseline = labelBaseline + labelHeight + lineSpacing
        canvas.drawText(box.highestLevel, background.left + padding, levelBaseline, levelPaint)
        canvas.restore()
    }

    private fun RectFModel.toRectF(): RectF = RectF(left, top, right, bottom)

    private inline fun ValueAnimator.doOnEnd(crossinline action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                action()
            }
        })
    }

    private companion object {
        const val SELECTION_ANIMATION_MS = 220L
        const val UNSELECTED_STROKE_DP = 1f
        const val SELECTED_STROKE_DP = 3f
        const val SELECTED_SCALE_DELTA = 0.10f
        val BOX_COLOR: Int = Color.rgb(0, 255, 102)
        val LABEL_BACKGROUND_COLOR: Int = Color.argb(230, 0, 0, 0)
        val LEVEL_COLOR: Int = Color.rgb(255, 214, 64)
    }
}
