package com.rokid.glass.hiddenrisk

/**
 * 双在线检测 lane 的纯竞争决策逻辑。
 * 负责过滤过期回调，并明确正命中/否命中/失败的后续动作。
 */
internal object OnlineHazardCompetitionDecider {

    enum class Outcome {
        POSITIVE,
        NEGATIVE,
        FAILURE,
    }

    data class Decision(
        val shouldIgnore: Boolean,
        val shouldStopAllLanes: Boolean = false,
        val shouldContinueCurrentLane: Boolean = false,
        val shouldCountRemoteFailure: Boolean = false,
    )

    fun decide(
        requestId: Long,
        activeRequestIds: Set<Long>,
        outcome: Outcome,
    ): Decision {
        if (!activeRequestIds.contains(requestId)) {
            return Decision(shouldIgnore = true)
        }
        return when (outcome) {
            Outcome.POSITIVE -> Decision(
                shouldIgnore = false,
                shouldStopAllLanes = true,
            )
            Outcome.NEGATIVE -> Decision(
                shouldIgnore = false,
                shouldContinueCurrentLane = true,
            )
            Outcome.FAILURE -> Decision(
                shouldIgnore = false,
                shouldContinueCurrentLane = true,
                shouldCountRemoteFailure = true,
            )
        }
    }
}
