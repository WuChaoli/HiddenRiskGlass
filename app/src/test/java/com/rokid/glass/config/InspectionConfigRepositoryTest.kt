package com.rokid.glass.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionConfigRepositoryTest {

    @Test
    fun `head motion stability gate defaults to disabled`() {
        val config = InspectionConfigRepository.buildConfig(
            baseJsonc = null,
            overlayJsonc = null,
        )

        assertFalse(config.aiInspection.enableHeadMotionStabilityGate)
    }

    @Test
    fun `head motion stability gate can be enabled from jsonc`() {
        val config = InspectionConfigRepository.buildConfig(
            baseJsonc = """
                {
                  "aiInspection": {
                    "enableHeadMotionStabilityGate": true
                  }
                }
            """.trimIndent(),
            overlayJsonc = null,
        )

        assertTrue(config.aiInspection.enableHeadMotionStabilityGate)
    }

    @Test
    fun `local hazard detect overlay uses local route and remote detail`() {
        val config = InspectionConfigRepository.buildConfig(
            baseJsonc = null,
            overlayJsonc = """
                {
                  "aiInspection": {
                    "autoHazardRoutingMode": "LOCAL_ONLY",
                    "forceOnlineDetailForLocalHazard": true
                  }
                }
            """.trimIndent(),
        )

        assertEquals(AutoHazardRoutingMode.LOCAL_ONLY, config.aiInspection.autoHazardRoutingMode)
        assertTrue(config.aiInspection.forceOnlineDetailForLocalHazard)
    }
}
