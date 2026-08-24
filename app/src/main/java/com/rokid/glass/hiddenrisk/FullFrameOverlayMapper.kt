package com.rokid.glass.hiddenrisk

internal data class FrameSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0)
    }
}

internal data class RectFModel(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

internal data class MappedOverlayFrame(
    val sourceCrop: RectFModel,
    val sourceDetections: List<AlignmentDetection>,
    val detections: List<AlignmentDetection>,
)

internal object FullFrameOverlayMapper {
    fun map(
        responseDetections: List<AlignmentDetection>,
        requestSize: FrameSize,
        sourceSize: FrameSize,
        overlaySize: FrameSize,
        calibration: AlignmentCalibrationState,
    ): MappedOverlayFrame {
        require(requestSize.width * 4 == requestSize.height * 3) {
            "request must use a 3:4 portrait aspect ratio"
        }
        require(sourceSize.width * 4 == sourceSize.height * 3) {
            "source must use a 3:4 portrait aspect ratio"
        }

        val scaleX = sourceSize.width.toFloat() / requestSize.width
        val scaleY = sourceSize.height.toFloat() / requestSize.height
        val sourceDetections = responseDetections.map { detection ->
            detection.copy(
                left = detection.left * scaleX,
                top = detection.top * scaleY,
                right = detection.right * scaleX,
                bottom = detection.bottom * scaleY,
            )
        }
        val normalizedCrop = calibration.normalizedCameraCrop()
        val sourceCrop = RectFModel(
            left = normalizedCrop.left * sourceSize.width,
            top = normalizedCrop.top * sourceSize.height,
            right = (normalizedCrop.left + normalizedCrop.width) * sourceSize.width,
            bottom = (normalizedCrop.top + normalizedCrop.height) * sourceSize.height,
        )
        val mapped = sourceDetections.mapNotNull { detection ->
            val intersection = intersect(detection, sourceCrop) ?: return@mapNotNull null
            val overlayScaleX = overlaySize.width / sourceCrop.width
            val overlayScaleY = overlaySize.height / sourceCrop.height
            detection.copy(
                left = ((intersection.left - sourceCrop.left) * overlayScaleX)
                    .coerceIn(0f, overlaySize.width.toFloat()),
                top = ((intersection.top - sourceCrop.top) * overlayScaleY)
                    .coerceIn(0f, overlaySize.height.toFloat()),
                right = ((intersection.right - sourceCrop.left) * overlayScaleX)
                    .coerceIn(0f, overlaySize.width.toFloat()),
                bottom = ((intersection.bottom - sourceCrop.top) * overlayScaleY)
                    .coerceIn(0f, overlaySize.height.toFloat()),
            )
        }
        return MappedOverlayFrame(sourceCrop, sourceDetections, mapped)
    }

    private fun intersect(detection: AlignmentDetection, crop: RectFModel): RectFModel? {
        val intersection = RectFModel(
            left = maxOf(detection.left, crop.left),
            top = maxOf(detection.top, crop.top),
            right = minOf(detection.right, crop.right),
            bottom = minOf(detection.bottom, crop.bottom),
        )
        return intersection.takeIf { it.width > 0f && it.height > 0f }
    }
}
