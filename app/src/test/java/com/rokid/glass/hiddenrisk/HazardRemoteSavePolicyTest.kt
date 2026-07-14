package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardRemoteSavePolicyTest {
    @Test
    fun offlineResolvedContentIsNeverUploadableAfterNetworkRecovery() {
        val content = hazard(remoteSaveAllowed = false)
        assertFalse(HazardRemoteSavePolicy.canUpload(content, networkAvailable = false))
        assertFalse(HazardRemoteSavePolicy.canUpload(content, networkAvailable = true))
    }

    @Test
    fun onlineContentRemainsUploadable() {
        assertTrue(HazardRemoteSavePolicy.canUpload(hazard(remoteSaveAllowed = true), networkAvailable = true))
    }

    private fun hazard(remoteSaveAllowed: Boolean) = ResolvedHazardContent(
        source = HazardSource.LOCAL,
        description = "隐患",
        advice = "建议",
        hidLevel = "1",
        hidNum = "HZ-1",
        lawBasis = "依据",
        displayTitle = "标题",
        jpegBytes = byteArrayOf(1),
        remoteSaveAllowed = remoteSaveAllowed,
    )
}
