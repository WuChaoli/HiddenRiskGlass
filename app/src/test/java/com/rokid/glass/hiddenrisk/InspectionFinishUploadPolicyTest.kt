package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionFinishUploadPolicyTest {
    @Test
    fun offlineDoesNotEnqueueFinish() {
        assertFalse(
            InspectionFinishUploadPolicy.canEnqueue(
                networkAvailable = false,
                businessNetworkAllowed = true,
            ),
        )
    }

    @Test
    fun offlineLocalNeverEnqueuesFinishAfterNetworkRecovery() {
        assertFalse(
            InspectionFinishUploadPolicy.canEnqueue(
                networkAvailable = true,
                businessNetworkAllowed = false,
            ),
        )
    }

    @Test
    fun onlineModeWithNetworkAllowsFinish() {
        assertTrue(
            InspectionFinishUploadPolicy.canEnqueue(
                networkAvailable = true,
                businessNetworkAllowed = true,
            ),
        )
    }

    @Test
    fun businessMockBlocksFinishUploadEvenWhileOnline() {
        assertFalse(
            InspectionFinishUploadPolicy.canEnqueue(
                networkAvailable = true,
                businessNetworkAllowed = true,
                businessUploadAllowed = false,
            ),
        )
    }
}
