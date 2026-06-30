package com.rokid.glass.hiddenrisk

/**
 * 自动推理双链路的纯决策逻辑。
 * 仅负责“应该启动哪条链路”和“在线链路下一步该做什么”，方便 JVM 单测覆盖。
 */
internal object AutoInferenceLoopDecider {

    data class PipelineStartDecision(
        val startLocal: Boolean,
        val startOnline: Boolean,
    )

    data class OnlineLoopAdvance(
        val queueNext: Boolean = false,
        val startNow: Boolean = false,
        val delayMs: Long? = null,
    )

    fun decidePipelineStart(
        localEnabled: Boolean,
        onlineEnabled: Boolean,
    ): PipelineStartDecision {
        return PipelineStartDecision(
            startLocal = localEnabled,
            startOnline = onlineEnabled,
        )
    }

    fun shouldContinueLocalLoop(hasDisplayableHazard: Boolean): Boolean {
        return !hasDisplayableHazard
    }

    fun decideOnlineLoopAdvance(
        queuedNext: Boolean,
        nowElapsedMs: Long,
        nextEarliestStartElapsedMs: Long,
        loopAlreadyPosted: Boolean,
    ): OnlineLoopAdvance {
        if (queuedNext) {
            return OnlineLoopAdvance(startNow = true)
        }
        if (loopAlreadyPosted) {
            return OnlineLoopAdvance()
        }
        if (nowElapsedMs < nextEarliestStartElapsedMs) {
            return OnlineLoopAdvance(
                delayMs = nextEarliestStartElapsedMs - nowElapsedMs,
            )
        }
        return OnlineLoopAdvance()
    }
}
