package com.rokid.glass.hiddenrisk

internal object AutoDeepTriggerDecider {
    private const val MIN_SCREEN_AREA_RATIO = 1f / 8f

    fun shouldTrigger(
        visibleDetections: List<AlignmentDetection>,
        screenSize: FrameSize,
    ): Boolean = qualifyingLabels(visibleDetections, screenSize) { false }.isNotEmpty()

    fun qualifyingLabels(
        visibleDetections: List<AlignmentDetection>,
        screenSize: FrameSize,
        isCooling: (String) -> Boolean,
    ): List<String> {
        val minimumArea = screenSize.width.toFloat() * screenSize.height * MIN_SCREEN_AREA_RATIO
        return visibleDetections.asSequence()
            .filter { detection ->
                detection.right > detection.left &&
                detection.bottom > detection.top &&
                    (detection.right - detection.left) * (detection.bottom - detection.top) > minimumArea
            }
            .map { it.label.trim() }
            .filter { it.isNotBlank() && !isCooling(it) }
            .distinct()
            .toList()
    }
}
