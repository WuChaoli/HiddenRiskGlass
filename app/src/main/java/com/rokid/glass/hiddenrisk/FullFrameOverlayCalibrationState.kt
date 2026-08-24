package com.rokid.glass.hiddenrisk

internal data class FullFrameOverlayCalibrationState(
    val calibration: AlignmentCalibrationState = DetectionOverlayAlignmentState(1f).calibrationState(),
    val previewAlpha: Float = 0f,
) {
    val distanceMeters: Float get() = FIXED_DISTANCE_METERS

    fun selectNextControl(): FullFrameOverlayCalibrationState = copy(
        calibration = calibration.selectNextControl(),
    )

    fun adjust(direction: AdjustmentDirection): FullFrameOverlayCalibrationState = copy(
        calibration = calibration.adjust(direction),
    )

    fun togglePreview(): FullFrameOverlayCalibrationState = copy(
        previewAlpha = if (previewAlpha == 0f) PREVIEW_ALPHA else 0f,
    )

    companion object {
        private const val FIXED_DISTANCE_METERS = 1f
        private const val PREVIEW_ALPHA = 0.5f
    }
}
