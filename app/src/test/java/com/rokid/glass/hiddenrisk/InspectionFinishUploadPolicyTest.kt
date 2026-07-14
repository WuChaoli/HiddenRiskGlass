package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionFinishUploadPolicyTest {
    @Test fun offlineDoesNotEnqueueFinish() = assertFalse(InspectionFinishUploadPolicy.canEnqueue(false))
    @Test fun recoveredNetworkAllowsFinish() = assertTrue(InspectionFinishUploadPolicy.canEnqueue(true))
}
