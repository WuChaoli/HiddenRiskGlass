package com.rokid.glass.hiddenrisk

/**
 * 共享推理帧是否可复用的纯决策逻辑。
 * 仅负责判断是否允许在线链路复用本地推理缓存，以及是否需要清空过期旧帧。
 */
internal object SharedInferenceFrameDecider {

    data class Decision(
        val canUseSharedFrame: Boolean,
        val shouldClearSharedFrame: Boolean = false,
        val reason: String,
    )

    fun decide(
        frameTimestamp: Long,
        frameReceivedAtElapsedMs: Long,
        lastTimestampExclusive: Long,
        nowElapsedMs: Long,
        staleFrameThresholdMs: Long,
        lastMotionUnstableElapsedMs: Long?,
        motionClearThresholdMs: Long,
    ): Decision {
        if (frameTimestamp <= lastTimestampExclusive) {
            return Decision(
                canUseSharedFrame = false,
                reason = "duplicate",
            )
        }

        val frameAgeMs = nowElapsedMs - frameReceivedAtElapsedMs
        if (frameAgeMs > staleFrameThresholdMs) {
            return Decision(
                canUseSharedFrame = false,
                reason = "stale",
            )
        }

        val motionUnstableElapsedMs = lastMotionUnstableElapsedMs
        val framePredatesLastMotion = motionUnstableElapsedMs != null &&
            frameReceivedAtElapsedMs < motionUnstableElapsedMs
        if (framePredatesLastMotion && frameAgeMs > motionClearThresholdMs) {
            return Decision(
                canUseSharedFrame = false,
                shouldClearSharedFrame = true,
                reason = "motion_timeout",
            )
        }

        return Decision(
            canUseSharedFrame = true,
            reason = "use_shared",
        )
    }
}
