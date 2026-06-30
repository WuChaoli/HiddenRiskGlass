package com.rokid.glass.hiddenrisk

/**
 * 协调自动识别命中后的延迟展示时机。
 * 只有“结果已就绪”且“延迟窗口结束”两个条件同时满足，才允许进入结果页。
 */
internal class AutoHazardPresentationCoordinator(
    private val delayMs: Long,
) {

    fun remainingDelayMs(
        detectedAtElapsedMs: Long,
        nowElapsedMs: Long,
    ): Long {
        val presentAtElapsedMs = detectedAtElapsedMs + delayMs
        return (presentAtElapsedMs - nowElapsedMs).coerceAtLeast(0L)
    }

    fun canPresent(
        detectedAtElapsedMs: Long,
        isReady: Boolean,
        nowElapsedMs: Long,
    ): Boolean {
        if (!isReady) {
            return false
        }
        return remainingDelayMs(
            detectedAtElapsedMs = detectedAtElapsedMs,
            nowElapsedMs = nowElapsedMs,
        ) == 0L
    }
}
