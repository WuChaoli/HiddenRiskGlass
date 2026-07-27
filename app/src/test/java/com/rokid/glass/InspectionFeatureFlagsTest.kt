package com.rokid.glass

import com.rokid.glass.config.InspectionConfigRepository
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionFeatureFlagsTest {

    @After
    fun tearDown() {
        InspectionConfigRepository.reloadForTest("{}")
    }

    @Test
    fun offlineLocalDisablesBusinessNetworkAndWifiGuard() {
        InspectionConfigRepository.reloadForTest(
            baseJsonc = "{}",
            overlayJsonc = """{"featureFlags":{"networkAccessMode":"OFFLINE_LOCAL"}}""",
        )

        assertTrue(InspectionFeatureFlags.isOfflineLocalMode())
        assertFalse(InspectionFeatureFlags.isBusinessNetworkAllowed())
        assertFalse(InspectionFeatureFlags.isWifiEntryGuardRequired())
    }

    @Test
    fun onlineModeAllowsBusinessNetworkAndRequiresWifiGuard() {
        InspectionConfigRepository.reloadForTest("{}")

        assertFalse(InspectionFeatureFlags.isOfflineLocalMode())
        assertTrue(InspectionFeatureFlags.isBusinessNetworkAllowed())
        assertTrue(InspectionFeatureFlags.isWifiEntryGuardRequired())
    }
}
