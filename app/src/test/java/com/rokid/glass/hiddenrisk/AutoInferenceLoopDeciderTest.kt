package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoInferenceLoopDeciderTest {

    @Test
    fun decidePipelineStart_startsLocalAndOnlineTogetherWhenBothEnabled() {
        val decision = AutoInferenceLoopDecider.decidePipelineStart(
            localEnabled = true,
            onlineEnabled = true,
        )

        assertTrue(decision.startLocal)
        assertTrue(decision.startOnline)
    }

    @Test
    fun shouldContinueLocalLoop_returnsTrueWhenNoDisplayableHazard() {
        assertTrue(
            AutoInferenceLoopDecider.shouldContinueLocalLoop(
                hasDisplayableHazard = false,
            ),
        )
    }

    @Test
    fun decideOnlineLoopAdvance_schedulesNextWhenWindowElapsed() {
        val decision = AutoInferenceLoopDecider.decideOnlineLoopAdvance(
            queuedNext = false,
            nowElapsedMs = 1_500L,
            nextEarliestStartElapsedMs = 1_000L,
            loopAlreadyPosted = false,
        )

        assertFalse(decision.queueNext)
        assertFalse(decision.startNow)
        assertEquals(0L, decision.delayMs)
    }

    @Test
    fun decideOnlineLoopAdvance_startsImmediatelyAfterQueuedRequestCompletes() {
        val decision = AutoInferenceLoopDecider.decideOnlineLoopAdvance(
            queuedNext = true,
            nowElapsedMs = 1_500L,
            nextEarliestStartElapsedMs = 1_000L,
            loopAlreadyPosted = false,
        )

        assertTrue(decision.startNow)
        assertFalse(decision.queueNext)
    }
}
