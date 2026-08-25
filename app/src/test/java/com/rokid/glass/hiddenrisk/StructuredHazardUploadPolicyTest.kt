package com.rokid.glass.hiddenrisk

import com.rokid.glass.config.BusinessMockConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredHazardUploadPolicyTest {
    @Test
    fun `business mock blocks upload on all three structured hazard pages`() {
        val mock = BusinessMockConfig(enabled = true, allowHazardUpload = false)

        assertFalse(StructuredHazardUploadPolicy.canUpload(StructuredHazardSource.MANUAL, mock))
        assertFalse(StructuredHazardUploadPolicy.canUpload(StructuredHazardSource.SCENE, mock))
        assertFalse(StructuredHazardUploadPolicy.canUpload(StructuredHazardSource.HAZARD_RECORD, mock))
    }

    @Test
    fun `disabled business mock keeps uploads enabled`() {
        val production = BusinessMockConfig(enabled = false, allowHazardUpload = false)

        assertTrue(StructuredHazardUploadPolicy.canUpload(StructuredHazardSource.MANUAL, production))
        assertTrue(StructuredHazardUploadPolicy.canUpload(StructuredHazardSource.SCENE, production))
        assertTrue(StructuredHazardUploadPolicy.canUpload(StructuredHazardSource.HAZARD_RECORD, production))
    }
}
