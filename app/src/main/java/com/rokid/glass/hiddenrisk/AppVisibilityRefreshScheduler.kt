package com.rokid.glass.hiddenrisk

internal class AppVisibilityRefreshScheduler(
    private val scheduler: Scheduler,
    private val refresh: (String) -> Unit,
) {
    interface Scheduler {
        fun postDelayed(runnable: Runnable, delayMs: Long)
        fun removeCallbacks(runnable: Runnable)
    }

    private var hasPendingRefreshes = false
    private val firstRefreshRunnable = Runnable { refresh("screen_on_first") }
    private val secondRefreshRunnable = Runnable {
        hasPendingRefreshes = false
        refresh("screen_on_second")
    }

    fun scheduleScreenOnRefresh() {
        if (hasPendingRefreshes) {
            cancel()
        }
        hasPendingRefreshes = true
        scheduler.postDelayed(firstRefreshRunnable, FIRST_REFRESH_DELAY_MS)
        scheduler.postDelayed(secondRefreshRunnable, SECOND_REFRESH_DELAY_MS)
    }

    fun cancel() {
        scheduler.removeCallbacks(firstRefreshRunnable)
        scheduler.removeCallbacks(secondRefreshRunnable)
        hasPendingRefreshes = false
    }

    companion object {
        const val FIRST_REFRESH_DELAY_MS = 300L
        const val SECOND_REFRESH_DELAY_MS = 1_500L
    }
}
