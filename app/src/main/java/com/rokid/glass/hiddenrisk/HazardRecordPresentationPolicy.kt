package com.rokid.glass.hiddenrisk

internal enum class HazardRecordPresentation {
    IDLE,
    RESULT_BACKGROUND,
    RESULT_WITH_HAZARDS,
}

internal enum class HazardRecordAudioCue {
    NONE,
    HAS_HAZARD,
}

internal object HazardRecordPresentationPolicy {
    fun afterCapture(): HazardRecordPresentation = HazardRecordPresentation.RESULT_BACKGROUND

    fun afterResponse(hasHazards: Boolean): HazardRecordPresentation = if (hasHazards) {
        HazardRecordPresentation.RESULT_WITH_HAZARDS
    } else {
        HazardRecordPresentation.IDLE
    }

    fun audioCueAfterResponse(hasHazards: Boolean): HazardRecordAudioCue = if (hasHazards) {
        HazardRecordAudioCue.HAS_HAZARD
    } else {
        HazardRecordAudioCue.NONE
    }

    fun afterSaveAccepted(): HazardRecordPresentation = HazardRecordPresentation.IDLE
}
