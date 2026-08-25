package com.rokid.glass.hiddenrisk

import kotlin.math.floor

internal data class IntCropRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal object AlignedDeepImageCropPlanner {
    fun plan(sourceSize: FrameSize, calibration: AlignmentCalibrationState): IntCropRect {
        val normalized = calibration.normalizedCameraCrop()
        val centerX = (normalized.left + normalized.width / 2f) * sourceSize.width
        val centerY = (normalized.top + normalized.height / 2f) * sourceSize.height
        val desiredWidth = floor(normalized.width * sourceSize.width).toInt().coerceAtLeast(3)
        val desiredHeight = floor(normalized.height * sourceSize.height).toInt().coerceAtLeast(4)
        val widthFromHeight = desiredHeight * 3 / 4
        val cropWidth = minOf(desiredWidth, widthFromHeight).coerceAtLeast(3) / 3 * 3
        val cropHeight = cropWidth * 4 / 3
        val left = (centerX - cropWidth / 2f).toInt().coerceIn(0, sourceSize.width - cropWidth)
        val top = (centerY - cropHeight / 2f).toInt().coerceIn(0, sourceSize.height - cropHeight)
        return IntCropRect(left, top, left + cropWidth, top + cropHeight)
    }
}
