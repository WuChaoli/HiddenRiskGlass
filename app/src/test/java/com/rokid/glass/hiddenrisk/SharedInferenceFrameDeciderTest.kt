package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedInferenceFrameDeciderTest {

    @Test
    fun decide_allowsSharedFrameWhenTimestampAdvancesAndFrameIsFresh() {
        val decision = SharedInferenceFrameDecider.decide(
            frameTimestamp = 200L,
            frameReceivedAtElapsedMs = 9_500L,
            lastTimestampExclusive = 100L,
            nowElapsedMs = 10_000L,
            staleFrameThresholdMs = 1_200L,
            lastMotionUnstableElapsedMs = null,
            motionClearThresholdMs = 1_000L,
        )

        assertTrue(decision.canUseSharedFrame)
        assertFalse(decision.shouldClearSharedFrame)
        assertEquals("use_shared", decision.reason)
    }

    @Test
    fun decide_rejectsSharedFrameWhenTimestampDoesNotAdvance() {
        val decision = SharedInferenceFrameDecider.decide(
            frameTimestamp = 100L,
            frameReceivedAtElapsedMs = 9_500L,
            lastTimestampExclusive = 100L,
            nowElapsedMs = 10_000L,
            staleFrameThresholdMs = 1_200L,
            lastMotionUnstableElapsedMs = null,
            motionClearThresholdMs = 1_000L,
        )

        assertFalse(decision.canUseSharedFrame)
        assertFalse(decision.shouldClearSharedFrame)
        assertEquals("duplicate", decision.reason)
    }

    @Test
    fun decide_rejectsSharedFrameWhenFrameIsStale() {
        val decision = SharedInferenceFrameDecider.decide(
            frameTimestamp = 200L,
            frameReceivedAtElapsedMs = 8_000L,
            lastTimestampExclusive = 100L,
            nowElapsedMs = 10_000L,
            staleFrameThresholdMs = 1_200L,
            lastMotionUnstableElapsedMs = null,
            motionClearThresholdMs = 1_000L,
        )

        assertFalse(decision.canUseSharedFrame)
        assertFalse(decision.shouldClearSharedFrame)
        assertEquals("stale", decision.reason)
    }

    @Test
    fun decide_clearsSharedFrameWhenItPredatesMotionAndExceedsTimeout() {
        val decision = SharedInferenceFrameDecider.decide(
            frameTimestamp = 200L,
            frameReceivedAtElapsedMs = 8_900L,
            lastTimestampExclusive = 100L,
            nowElapsedMs = 10_000L,
            staleFrameThresholdMs = 1_200L,
            lastMotionUnstableElapsedMs = 9_000L,
            motionClearThresholdMs = 1_000L,
        )

        assertFalse(decision.canUseSharedFrame)
        assertTrue(decision.shouldClearSharedFrame)
        assertEquals("motion_timeout", decision.reason)
    }

    @Test
    fun decide_keepsSharedFrameWhenItPredatesMotionButIsStillWithinTimeout() {
        val decision = SharedInferenceFrameDecider.decide(
            frameTimestamp = 200L,
            frameReceivedAtElapsedMs = 9_100L,
            lastTimestampExclusive = 100L,
            nowElapsedMs = 10_000L,
            staleFrameThresholdMs = 1_200L,
            lastMotionUnstableElapsedMs = 9_500L,
            motionClearThresholdMs = 1_000L,
        )

        assertTrue(decision.canUseSharedFrame)
        assertFalse(decision.shouldClearSharedFrame)
        assertEquals("use_shared", decision.reason)
    }
}
