package com.rokid.glass.hiddenrisk

internal data class DeepV2BBoxShape(
    val rect: RectFModel,
    val cornerLength: Float,
    val cornerRadius: Float,
    val horizontalSegmentLength: Float,
    val verticalSegmentLength: Float,
)

internal object DeepV2BBoxGeometry {
    fun compute(
        rect: RectFModel,
        cornerLength: Float,
        cornerRadius: Float,
    ): DeepV2BBoxShape {
        val safeCornerLength = cornerLength.coerceAtLeast(0f)
        val scale = if (safeCornerLength == 0f) {
            0f
        } else {
            minOf(
                1f,
                rect.width.coerceAtLeast(0f) / (safeCornerLength * 2f),
                rect.height.coerceAtLeast(0f) / (safeCornerLength * 2f),
            ).coerceAtLeast(0f)
        }
        val scaledCornerLength = safeCornerLength * scale
        return DeepV2BBoxShape(
            rect = rect,
            cornerLength = scaledCornerLength,
            cornerRadius = cornerRadius.coerceIn(0f, safeCornerLength) * scale,
            horizontalSegmentLength = (rect.width - scaledCornerLength * 2f).coerceAtLeast(0f),
            verticalSegmentLength = (rect.height - scaledCornerLength * 2f).coerceAtLeast(0f),
        )
    }
}
