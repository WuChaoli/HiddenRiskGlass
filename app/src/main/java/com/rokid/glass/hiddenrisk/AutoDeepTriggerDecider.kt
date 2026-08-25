package com.rokid.glass.hiddenrisk

internal object AutoDeepTriggerDecider {
    private const val MIN_SCREEN_AREA_RATIO = 1f / 8f

    fun shouldTrigger(
        visibleDetections: List<AlignmentDetection>,
        screenSize: FrameSize,
    ): Boolean {
        val minimumArea = screenSize.width.toFloat() * screenSize.height * MIN_SCREEN_AREA_RATIO
        return visibleDetections.any { detection ->
            detection.right > detection.left &&
                detection.bottom > detection.top &&
                (detection.right - detection.left) * (detection.bottom - detection.top) >= minimumArea
        }
    }
}
