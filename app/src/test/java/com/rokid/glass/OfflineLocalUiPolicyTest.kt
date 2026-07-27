package com.rokid.glass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineLocalUiPolicyTest {

    @Test
    fun offlineLocalShowsOnlyHazardAnalysis() {
        assertFalse(OfflineLocalUiPolicy.showsDeviceGuide(offlineLocal = true))
        assertFalse(OfflineLocalUiPolicy.showsHazardRecord(offlineLocal = true))
    }

    @Test
    fun offlineLocalDisablesManualDeepAnalysis() {
        assertFalse(OfflineLocalUiPolicy.manualDeepEnabled(offlineLocal = true))
    }

    @Test
    fun onlineModeKeepsExtendedFeatures() {
        assertTrue(OfflineLocalUiPolicy.showsDeviceGuide(offlineLocal = false))
        assertTrue(OfflineLocalUiPolicy.showsHazardRecord(offlineLocal = false))
        assertTrue(OfflineLocalUiPolicy.manualDeepEnabled(offlineLocal = false))
    }
}
