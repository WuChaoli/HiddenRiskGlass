package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineHazardCompetitionDeciderTest {

    @Test
    fun positiveResult_stopsAllLanes() {
        val decision = OnlineHazardCompetitionDecider.decide(
            requestId = 1L,
            activeRequestIds = setOf(1L, 2L),
            outcome = OnlineHazardCompetitionDecider.Outcome.POSITIVE,
        )

        assertFalse(decision.shouldIgnore)
        assertTrue(decision.shouldStopAllLanes)
        assertFalse(decision.shouldContinueCurrentLane)
        assertFalse(decision.shouldCountRemoteFailure)
    }

    @Test
    fun negativeResult_continuesOnlyCurrentLane() {
        val decision = OnlineHazardCompetitionDecider.decide(
            requestId = 2L,
            activeRequestIds = setOf(1L, 2L),
            outcome = OnlineHazardCompetitionDecider.Outcome.NEGATIVE,
        )

        assertFalse(decision.shouldIgnore)
        assertFalse(decision.shouldStopAllLanes)
        assertTrue(decision.shouldContinueCurrentLane)
        assertFalse(decision.shouldCountRemoteFailure)
    }

    @Test
    fun failure_countsRemoteFailureAndContinuesLane() {
        val decision = OnlineHazardCompetitionDecider.decide(
            requestId = 3L,
            activeRequestIds = setOf(3L, 4L),
            outcome = OnlineHazardCompetitionDecider.Outcome.FAILURE,
        )

        assertFalse(decision.shouldIgnore)
        assertFalse(decision.shouldStopAllLanes)
        assertTrue(decision.shouldContinueCurrentLane)
        assertTrue(decision.shouldCountRemoteFailure)
    }

    @Test
    fun staleCallback_isIgnored() {
        val decision = OnlineHazardCompetitionDecider.decide(
            requestId = 4L,
            activeRequestIds = setOf(5L),
            outcome = OnlineHazardCompetitionDecider.Outcome.POSITIVE,
        )

        assertTrue(decision.shouldIgnore)
        assertFalse(decision.shouldStopAllLanes)
        assertFalse(decision.shouldContinueCurrentLane)
        assertFalse(decision.shouldCountRemoteFailure)
    }
}
