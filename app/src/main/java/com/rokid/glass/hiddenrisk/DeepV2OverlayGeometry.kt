package com.rokid.glass.hiddenrisk

internal object DeepV2OverlayGeometry {
    fun map(
        bbox: DeepV2BoundingBox,
        sourceWidth: Int,
        sourceHeight: Int,
        destinationWidth: Int,
        destinationHeight: Int,
    ): RectFModel? {
        if (
            sourceWidth <= 0 || sourceHeight <= 0 ||
            destinationWidth <= 0 || destinationHeight <= 0 ||
            bbox.right <= bbox.left || bbox.bottom <= bbox.top
        ) {
            return null
        }
        val scaleX = destinationWidth.toFloat() / sourceWidth
        val scaleY = destinationHeight.toFloat() / sourceHeight
        val mapped = RectFModel(
            left = (bbox.left * scaleX).coerceIn(0f, destinationWidth.toFloat()),
            top = (bbox.top * scaleY).coerceIn(0f, destinationHeight.toFloat()),
            right = (bbox.right * scaleX).coerceIn(0f, destinationWidth.toFloat()),
            bottom = (bbox.bottom * scaleY).coerceIn(0f, destinationHeight.toFloat()),
        )
        return mapped.takeIf { it.width > 0f && it.height > 0f }
    }

    fun expandAroundCenter(rect: RectFModel, scale: Float): RectFModel {
        require(scale > 0f)
        val centerX = (rect.left + rect.right) / 2f
        val centerY = (rect.top + rect.bottom) / 2f
        val halfWidth = rect.width * scale / 2f
        val halfHeight = rect.height * scale / 2f
        return RectFModel(
            left = centerX - halfWidth,
            top = centerY - halfHeight,
            right = centerX + halfWidth,
            bottom = centerY + halfHeight,
        )
    }
}
