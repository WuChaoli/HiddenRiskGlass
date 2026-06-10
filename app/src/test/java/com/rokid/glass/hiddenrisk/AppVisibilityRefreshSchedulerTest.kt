package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Test

class AppVisibilityRefreshSchedulerTest {

    @Test
    fun `screen on schedules two delayed refreshes`() {
        val scheduler = FakeScheduler()
        val reasons = mutableListOf<String>()
        val refreshScheduler = AppVisibilityRefreshScheduler(scheduler, reasons::add)

        refreshScheduler.scheduleScreenOnRefresh()
        scheduler.runAll()

        assertEquals(
            listOf("screen_on_first", "screen_on_second"),
            reasons,
        )
        assertEquals(
            listOf(
                AppVisibilityRefreshScheduler.FIRST_REFRESH_DELAY_MS,
                AppVisibilityRefreshScheduler.SECOND_REFRESH_DELAY_MS,
            ),
            scheduler.postedDelays,
        )
    }

    @Test
    fun `consecutive screen on replaces pending refreshes`() {
        val scheduler = FakeScheduler()
        val reasons = mutableListOf<String>()
        val refreshScheduler = AppVisibilityRefreshScheduler(scheduler, reasons::add)

        refreshScheduler.scheduleScreenOnRefresh()
        refreshScheduler.scheduleScreenOnRefresh()
        scheduler.runAll()

        assertEquals(
            listOf("screen_on_first", "screen_on_second"),
            reasons,
        )
        assertEquals(2, scheduler.removedRunnables.size)
    }

    private class FakeScheduler : AppVisibilityRefreshScheduler.Scheduler {
        private val pending = linkedMapOf<Runnable, Long>()
        val postedDelays = mutableListOf<Long>()
        val removedRunnables = mutableListOf<Runnable>()

        override fun postDelayed(runnable: Runnable, delayMs: Long) {
            pending[runnable] = delayMs
            postedDelays += delayMs
        }

        override fun removeCallbacks(runnable: Runnable) {
            pending.remove(runnable)
            removedRunnables += runnable
        }

        fun runAll() {
            pending.entries
                .sortedBy { it.value }
                .map { it.key }
                .also { pending.clear() }
                .forEach(Runnable::run)
        }
    }
}
