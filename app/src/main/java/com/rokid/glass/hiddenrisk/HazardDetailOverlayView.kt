package com.rokid.glass.hiddenrisk

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

internal class HazardDetailOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val density = resources.displayMetrics.density
    private val labelView = textView(20f, bold = true, maxLines = 1)
    private val codeView = textView(10f, bold = true, maxLines = 1)
    private val levelView = textView(10f, bold = true, maxLines = 1).apply { gravity = Gravity.END }
    private val descriptionView = bodyTextView(maxLines = 5)
    private val adviceView = bodyTextView(maxLines = 5)
    private val lawBasisView = bodyTextView(maxLines = 3)
    private val pageView = textView(9f, bold = true, maxLines = 1).apply { gravity = Gravity.END }
    private var boundsChangedListener: ((RectF?) -> Unit)? = null

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(13), dp(14), dp(12))
        background = roundedBackground(Color.argb(166, 0, 0, 0), 16f)
        addView(textView(10f, bold = true, maxLines = 1).apply { text = "隐患详情" })
        addView(labelView, matchWrap(top = 3))
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                addView(codeView, weighted())
                addView(levelView, weighted())
            },
            matchWrap(top = 4, bottom = 12),
        )
        addView(buildContentBox(), matchWrap())
        addView(textView(10f, bold = true, maxLines = 1).apply { text = "法律依据" }, matchWrap(top = 12))
        addView(lawBasisView, matchWrap(top = 4))
        addView(pageView, matchWrap(top = 4))
        visibility = GONE
    }

    fun render(model: HazardDetailDisplayModel, pageIndex: Int, pageCount: Int) {
        labelView.text = model.label
        codeView.text = "隐患编号  ${model.hazardCode}"
        levelView.text = "隐患等级  ${model.level}"
        descriptionView.text = model.description
        adviceView.text = model.advice
        lawBasisView.text = model.lawBasis
        pageView.visibility = if (HazardDetailDisplayModel.shouldShowPageIndicator(pageCount)) VISIBLE else GONE
        pageView.text = "${pageIndex + 1} / $pageCount"
        visibility = VISIBLE
        requestLayout()
    }

    fun clear() {
        visibility = GONE
        boundsChangedListener?.invoke(null)
    }

    fun setOnCardBoundsChangedListener(listener: (RectF?) -> Unit) {
        boundsChangedListener = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxHeight = dp(MAX_HEIGHT_DP)
        val cappedHeight = MeasureSpec.makeMeasureSpec(
            minOf(MeasureSpec.getSize(heightMeasureSpec), maxHeight),
            MeasureSpec.AT_MOST,
        )
        super.onMeasure(widthMeasureSpec, cappedHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (visibility == VISIBLE) {
            boundsChangedListener?.invoke(RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()))
        }
    }

    private fun buildContentBox(): View {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = roundedBackground(Color.TRANSPARENT, 11f)
            addView(contentColumn("隐患描述", descriptionView), weighted())
            addView(
                View(context).apply { setBackgroundColor(BORDER_COLOR) },
                LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT),
            )
            addView(contentColumn("整改建议", adviceView), weighted())
        }
    }

    private fun contentColumn(title: String, body: TextView) = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(11), dp(10), dp(11), dp(11))
        addView(textView(10f, bold = true, maxLines = 1).apply { text = title })
        addView(body, matchWrap(top = 6))
    }

    private fun textView(sizeSp: Float, bold: Boolean, maxLines: Int) = TextView(context).apply {
        setTextColor(TEXT_COLOR)
        textSize = sizeSp
        setMaxLines(maxLines)
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun bodyTextView(maxLines: Int) = textView(11f, bold = true, maxLines = maxLines).apply {
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun roundedBackground(fillColor: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * density
        setColor(fillColor)
        setStroke(dp(1), BORDER_COLOR)
    }

    private fun matchWrap(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        const val MAX_HEIGHT_DP = 360
        val TEXT_COLOR: Int = Color.rgb(0, 255, 102)
        val BORDER_COLOR: Int = Color.argb(220, 0, 255, 102)
    }
}
