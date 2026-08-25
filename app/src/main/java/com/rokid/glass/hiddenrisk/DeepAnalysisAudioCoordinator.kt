package com.rokid.glass.hiddenrisk

internal enum class DeepAnalysisEndpoint {
    DEEP,
    GENERAL_DEEP,
    DEEP_V2,
}

internal enum class DeepAnalysisAudioCue {
    ANALYZING,
    NO_HAZARD,
    HAS_HAZARD,
}

internal class DeepAnalysisAudioCoordinator {
    private val activeRequests = mutableMapOf<String, DeepAnalysisEndpoint>()

    fun begin(token: String, endpoint: DeepAnalysisEndpoint): DeepAnalysisAudioCue? {
        if (activeRequests.putIfAbsent(token, endpoint) != null) return null
        return DeepAnalysisAudioCue.ANALYZING
    }

    fun complete(token: String, hasHazard: Boolean): DeepAnalysisAudioCue? {
        activeRequests.remove(token) ?: return null
        return if (hasHazard) {
            DeepAnalysisAudioCue.HAS_HAZARD
        } else {
            DeepAnalysisAudioCue.NO_HAZARD
        }
    }

    fun cancel(token: String) {
        activeRequests.remove(token)
    }

    fun cancelAll() {
        activeRequests.clear()
    }
}
