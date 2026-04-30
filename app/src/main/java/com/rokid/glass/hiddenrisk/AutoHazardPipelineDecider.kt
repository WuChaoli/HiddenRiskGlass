package com.rokid.glass.hiddenrisk

/**
 * 自动隐患识别链路决策。
 * 只描述远程主链路与本地备用链路的切换规则，避免 Activity 中散落判断。
 */
internal object AutoHazardPipelineDecider {

    enum class PipelineMode {
        REMOTE_PRIMARY,
        LOCAL_FALLBACK_LOADING,
        LOCAL_FALLBACK,
    }

    data class PipelineDecision(
        val mode: PipelineMode,
        val startRemote: Boolean,
        val startLocal: Boolean,
        val loadLocalModel: Boolean,
        val resetRemoteFailures: Boolean = false,
    )

    fun decideStart(networkAvailable: Boolean): PipelineDecision {
        return if (networkAvailable) {
            PipelineDecision(
                mode = PipelineMode.REMOTE_PRIMARY,
                startRemote = true,
                startLocal = false,
                loadLocalModel = false,
                resetRemoteFailures = true,
            )
        } else {
            PipelineDecision(
                mode = PipelineMode.LOCAL_FALLBACK_LOADING,
                startRemote = false,
                startLocal = false,
                loadLocalModel = true,
            )
        }
    }

    fun decideAfterRemoteFailure(
        currentFailureCount: Int,
        threshold: Int,
    ): PipelineDecision {
        val normalizedThreshold = threshold.coerceAtLeast(1)
        return if (currentFailureCount >= normalizedThreshold) {
            PipelineDecision(
                mode = PipelineMode.LOCAL_FALLBACK_LOADING,
                startRemote = false,
                startLocal = false,
                loadLocalModel = true,
            )
        } else {
            PipelineDecision(
                mode = PipelineMode.REMOTE_PRIMARY,
                startRemote = true,
                startLocal = false,
                loadLocalModel = false,
            )
        }
    }

    fun decideAfterLocalModelLoaded(success: Boolean): PipelineDecision {
        return if (success) {
            PipelineDecision(
                mode = PipelineMode.LOCAL_FALLBACK,
                startRemote = false,
                startLocal = true,
                loadLocalModel = false,
            )
        } else {
            PipelineDecision(
                mode = PipelineMode.LOCAL_FALLBACK_LOADING,
                startRemote = false,
                startLocal = false,
                loadLocalModel = true,
            )
        }
    }

    fun decideLocalNetworkProbe(networkAvailable: Boolean): PipelineDecision {
        return if (networkAvailable) {
            PipelineDecision(
                mode = PipelineMode.REMOTE_PRIMARY,
                startRemote = true,
                startLocal = false,
                loadLocalModel = false,
                resetRemoteFailures = true,
            )
        } else {
            PipelineDecision(
                mode = PipelineMode.LOCAL_FALLBACK,
                startRemote = false,
                startLocal = true,
                loadLocalModel = false,
            )
        }
    }
}
