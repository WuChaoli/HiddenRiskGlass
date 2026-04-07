package com.rokid.glass.hiddenrisk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * 按 hidden_risk 服务端 multi 模式在 Android 侧完成二次过滤与标注绘制。
 */
object HiddenRiskMultiOverlayRenderer {
    private val classNames = arrayOf(
        "T字按钮",                  // 0
        "三通接口",                 // 1
        "切断联动装置",             // 2
        "卡式炉",                   // 3
        "可燃气体报警器",           // 4
        "安全出口标志",             // 5
        "室内消火栓箱",             // 6
        "室外消火栓",               // 7
        "工业可燃气体探测器",       // 8
        "应急灯",                   // 9
        "排气扇",                   // 10
        "栓口",                     // 11
        "气瓶调压阀",               // 12
        "氧气瓶",                   // 13
        "水带",                     // 14
        "水枪",                     // 15
        "水泵接合器",               // 16
        "液化石油气瓶",             // 17
        "灭火器",                   // 18
        "灭火器箱",                 // 19
        "炭炉",                     // 20
        "点火针",                   // 21
        "煤炉",                     // 22
        "照明灯具",                 // 23
        "熄火保护装置",             // 24
        "燃气灶",                   // 25
        "电动三轮车",               // 26
        "电动车",                   // 27
        "负荷开关",                 // 28
        "软管",                     // 29
        "防火门闭门器",             // 30
        "防火门顺序器",             // 31
        "防盗窗",                   // 32
    )

    private val criticalLabels = setOf("燃气灶", "液化石油气瓶", "气瓶调压阀")
    private val palette = intArrayOf(
        Color.parseColor("#FF6B35"),
        Color.parseColor("#0077B6"),
        Color.parseColor("#2A9D8F"),
        Color.parseColor("#E63946"),
        Color.parseColor("#6A4C93"),
        Color.parseColor("#F4A261"),
        Color.parseColor("#264653"),
        Color.parseColor("#8AC926"),
    )

    data class DisplayDetection(
        val raw: DetectionResult,
        val label: String,
    )

    fun filterForMulti(detections: Array<DetectionResult>): List<DisplayDetection> {
        if (detections.isEmpty()) {
            return emptyList()
        }

        val mapped = detections.map { detection ->
            DisplayDetection(
                raw = detection,
                label = labelFor(detection),
            )
        }

        val hasCritical = mapped.any { it.label in criticalLabels }
        return if (!hasCritical) {
            mapped
        } else {
            mapped.filterNot { it.label == "软管" }
        }
    }

    fun render(source: Bitmap, detections: List<DisplayDetection>): Bitmap {
        val annotated = if (source.config == Bitmap.Config.ARGB_8888 && source.isMutable) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw IllegalStateException("copy bitmap failed")
        }
        val canvas = Canvas(annotated)

        val strokeWidth = max(2f, annotated.width / 320f)
        val textSize = max(18f, annotated.width / 28f)
        val labelPadding = max(6f, annotated.width / 120f)

        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
        }
        val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            style = Paint.Style.FILL
        }

        val textBounds = android.graphics.Rect()
        detections.forEach { detection ->
            val rect = clampRect(
                left = detection.raw.x,
                top = detection.raw.y,
                right = detection.raw.x + detection.raw.width,
                bottom = detection.raw.y + detection.raw.height,
                bitmapWidth = annotated.width.toFloat(),
                bitmapHeight = annotated.height.toFloat(),
            ) ?: return@forEach

            val color = palette[detection.raw.labelId.absoluteModulo(palette.size)]
            boxPaint.color = color
            labelBgPaint.color = color

            canvas.drawRect(rect, boxPaint)

            val scorePercent = (detection.raw.score * 100f).coerceIn(0f, 100f)
            val labelText = "${detection.label} ${scorePercent.toInt()}%"
            textPaint.getTextBounds(labelText, 0, labelText.length, textBounds)

            val textWidth = textBounds.width().toFloat()
            val textHeight = max(textBounds.height().toFloat(), textSize)
            val labelLeft = rect.left
            val labelTop = if (rect.top - textHeight - labelPadding * 2 >= 0f) {
                rect.top - textHeight - labelPadding * 2
            } else {
                rect.top
            }
            val labelRight = min(annotated.width.toFloat(), labelLeft + textWidth + labelPadding * 2)
            val labelBottom = min(annotated.height.toFloat(), labelTop + textHeight + labelPadding * 2)
            canvas.drawRect(labelLeft, labelTop, labelRight, labelBottom, labelBgPaint)
            canvas.drawText(
                labelText,
                labelLeft + labelPadding,
                labelBottom - labelPadding,
                textPaint,
            )
        }

        return annotated
    }

    private fun labelFor(detection: DetectionResult): String {
        val labelId = detection.labelId
        return if (labelId in classNames.indices) {
            classNames[labelId]
        } else {
            detection.label
        }
    }

    private fun clampRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        bitmapWidth: Float,
        bitmapHeight: Float,
    ): RectF? {
        val clampedLeft = left.coerceIn(0f, bitmapWidth)
        val clampedTop = top.coerceIn(0f, bitmapHeight)
        val clampedRight = right.coerceIn(0f, bitmapWidth)
        val clampedBottom = bottom.coerceIn(0f, bitmapHeight)
        if (clampedRight <= clampedLeft || clampedBottom <= clampedTop) {
            return null
        }
        return RectF(clampedLeft, clampedTop, clampedRight, clampedBottom)
    }

    private fun Int.absoluteModulo(mod: Int): Int {
        val value = this % mod
        return if (value >= 0) value else value + mod
    }
}
