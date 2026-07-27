package com.rokid.glass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryGuardPolicyTest {

    @Test
    fun offlineLocalSkipsWifi() {
        assertFalse(EntryGuardPolicy.requiresWifi(offlineLocal = true))
    }

    @Test
    fun offlineLocalSkipsAutoUpdate() {
        assertFalse(EntryGuardPolicy.allowsAutoUpdate(offlineLocal = true))
    }

    @Test
    fun offlineLocalSkipsEnterpriseContext() {
        assertFalse(EntryGuardPolicy.requiresEnterpriseContext(offlineLocal = true))
    }

    @Test
    fun offlineLocalKeepsSessionOfflineWhenWifiIsConnected() {
        assertFalse(
            EntryGuardPolicy.sessionNetworkAvailable(
                offlineLocal = true,
                systemNetworkAvailable = true,
            ),
        )
    }

    @Test
    fun onlineModeKeepsExistingEntryGuards() {
        assertTrue(EntryGuardPolicy.requiresWifi(offlineLocal = false))
        assertTrue(EntryGuardPolicy.allowsAutoUpdate(offlineLocal = false))
        assertTrue(EntryGuardPolicy.requiresEnterpriseContext(offlineLocal = false))
        assertTrue(
            EntryGuardPolicy.sessionNetworkAvailable(
                offlineLocal = false,
                systemNetworkAvailable = true,
            ),
        )
    }
}
