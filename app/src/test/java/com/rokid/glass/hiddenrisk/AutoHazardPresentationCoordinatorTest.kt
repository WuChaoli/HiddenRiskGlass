package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoHazardPresentationCoordinatorTest {

    private val coordinator = AutoHazardPresentationCoordinator(delayMs = 3000L)

    @Test
    fun remainingDelayMs_returnsFullDelayAtDetectionTime() {
        val remaining = coordinator.remainingDelayMs(
            detectedAtElapsedMs = 1000L,
            nowElapsedMs = 1000L,
        )

        assertEquals(3000L, remaining)
    }

    @Test
    fun remainingDelayMs_clampsAtZeroAfterDeadline() {
        val remaining = coordinator.remainingDelayMs(
            detectedAtElapsedMs = 1000L,
            nowElapsedMs = 4500L,
        )

        assertEquals(0L, remaining)
    }

    @Test
    fun canPresent_returnsFalseBeforeDelayEvenWhenReady() {
        val canPresent = coordinator.canPresent(
            detectedAtElapsedMs = 1000L,
            isReady = true,
            nowElapsedMs = 3999L,
        )

        assertFalse(canPresent)
    }

    @Test
    fun canPresent_returnsFalseWhenDelayElapsedButResultNotReady() {
        val canPresent = coordinator.canPresent(
            detectedAtElapsedMs = 1000L,
            isReady = false,
            nowElapsedMs = 4000L,
        )

        assertFalse(canPresent)
    }

    @Test
    fun canPresent_returnsTrueWhenDelayElapsedAndResultReady() {
        val canPresent = coordinator.canPresent(
            detectedAtElapsedMs = 1000L,
            isReady = true,
            nowElapsedMs = 4000L,
        )

        assertTrue(canPresent)
    }
}
