package com.rokid.glass.hiddenrisk

import com.rokid.glass.hiddenrisk.AutoHazardPipelineDecider.PipelineMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoHazardPipelineDeciderTest {

    @Test
    fun decideStart_usesRemoteWhenNetworkAvailable() {
        val decision = AutoHazardPipelineDecider.decideStart(networkAvailable = true)

        assertEquals(PipelineMode.REMOTE_PRIMARY, decision.mode)
        assertTrue(decision.startRemote)
        assertFalse(decision.startLocal)
        assertFalse(decision.loadLocalModel)
        assertTrue(decision.resetRemoteFailures)
    }

    @Test
    fun decideStart_loadsLocalWhenNetworkUnavailable() {
        val decision = AutoHazardPipelineDecider.decideStart(networkAvailable = false)

        assertEquals(PipelineMode.LOCAL_FALLBACK_LOADING, decision.mode)
        assertFalse(decision.startRemote)
        assertFalse(decision.startLocal)
        assertTrue(decision.loadLocalModel)
    }

    @Test
    fun decideAfterRemoteFailure_fallsBackAtThreshold() {
        val beforeThreshold = AutoHazardPipelineDecider.decideAfterRemoteFailure(
            currentFailureCount = 2,
            threshold = 3,
        )
        val atThreshold = AutoHazardPipelineDecider.decideAfterRemoteFailure(
            currentFailureCount = 3,
            threshold = 3,
        )

        assertEquals(PipelineMode.REMOTE_PRIMARY, beforeThreshold.mode)
        assertTrue(beforeThreshold.startRemote)
        assertFalse(beforeThreshold.loadLocalModel)

        assertEquals(PipelineMode.LOCAL_FALLBACK_LOADING, atThreshold.mode)
        assertFalse(atThreshold.startRemote)
        assertTrue(atThreshold.loadLocalModel)
    }

    @Test
    fun decideAfterLocalModelLoaded_startsLocalOnlyOnSuccess() {
        val success = AutoHazardPipelineDecider.decideAfterLocalModelLoaded(success = true)
        val failed = AutoHazardPipelineDecider.decideAfterLocalModelLoaded(success = false)

        assertEquals(PipelineMode.LOCAL_FALLBACK, success.mode)
        assertTrue(success.startLocal)
        assertFalse(success.startRemote)

        assertEquals(PipelineMode.LOCAL_FALLBACK_LOADING, failed.mode)
        assertFalse(failed.startLocal)
        assertTrue(failed.loadLocalModel)
    }

    @Test
    fun decideLocalNetworkProbe_switchesBackToRemoteWhenNetworkReturns() {
        val restored = AutoHazardPipelineDecider.decideLocalNetworkProbe(networkAvailable = true)
        val stillOffline = AutoHazardPipelineDecider.decideLocalNetworkProbe(networkAvailable = false)

        assertEquals(PipelineMode.REMOTE_PRIMARY, restored.mode)
        assertTrue(restored.startRemote)
        assertTrue(restored.resetRemoteFailures)

        assertEquals(PipelineMode.LOCAL_FALLBACK, stillOffline.mode)
        assertTrue(stillOffline.startLocal)
        assertFalse(stillOffline.startRemote)
    }
}
