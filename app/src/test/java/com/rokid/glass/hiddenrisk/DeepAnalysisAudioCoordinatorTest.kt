package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepAnalysisAudioCoordinatorTest {
    private val coordinator = DeepAnalysisAudioCoordinator()

    @Test
    fun `each deep endpoint starts with analyzing cue`() {
        DeepAnalysisEndpoint.entries.forEachIndexed { index, endpoint ->
            assertEquals(
                DeepAnalysisAudioCue.ANALYZING,
                coordinator.begin("request-$index", endpoint),
            )
        }
    }

    @Test
    fun `valid terminal result emits exactly one matching cue`() {
        coordinator.begin("hazard", DeepAnalysisEndpoint.DEEP_V2)
        coordinator.begin("safe", DeepAnalysisEndpoint.GENERAL_DEEP)

        assertEquals(DeepAnalysisAudioCue.HAS_HAZARD, coordinator.complete("hazard", true))
        assertEquals(DeepAnalysisAudioCue.NO_HAZARD, coordinator.complete("safe", false))
        assertNull(coordinator.complete("hazard", true))
        assertNull(coordinator.complete("safe", false))
    }

    @Test
    fun `duplicate begin and callback after cancel do not emit cues`() {
        assertEquals(
            DeepAnalysisAudioCue.ANALYZING,
            coordinator.begin("request", DeepAnalysisEndpoint.DEEP),
        )
        assertNull(coordinator.begin("request", DeepAnalysisEndpoint.DEEP))

        coordinator.cancel("request")

        assertNull(coordinator.complete("request", true))
    }

    @Test
    fun `cancel all rejects callbacks from old generation`() {
        coordinator.begin("deep", DeepAnalysisEndpoint.DEEP)
        coordinator.begin("general", DeepAnalysisEndpoint.GENERAL_DEEP)

        coordinator.cancelAll()

        assertNull(coordinator.complete("deep", false))
        assertNull(coordinator.complete("general", true))
    }
}
