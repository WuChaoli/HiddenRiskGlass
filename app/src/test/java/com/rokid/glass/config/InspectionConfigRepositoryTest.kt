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
    fun `gm api defaults to compatibility endpoint`() {
        val config = InspectionConfigRepository.buildConfig(
            baseJsonc = null,
            overlayJsonc = null,
        )

        assertEquals("http://183.147.142.133:10012/ai/gm", config.network.aiGmApi.url)
        assertEquals(1500L, config.network.aiGmApi.detectTimeoutMs)
    }

    @Test
    fun `gm api can be overridden from jsonc`() {
        val config = InspectionConfigRepository.buildConfig(
            baseJsonc = """
                {
                  "network": {
                    "aiGmApi": {
                      "url": "http://example.test/ai/gm",
                      "detectTimeoutMs": 2600
                    }
                  }
                }
            """.trimIndent(),
            overlayJsonc = null,
        )

        assertEquals("http://example.test/ai/gm", config.network.aiGmApi.url)
        assertEquals(2600L, config.network.aiGmApi.detectTimeoutMs)
    }

    @Test
    fun `auto sleep config uses default timeout values`() {
        val config = InspectionConfigRepository.buildConfig(
            baseJsonc = null,
            overlayJsonc = null,
        )

        assertEquals(60_000L, config.aiInspection.sleepWakingDurationMs)
        assertEquals(15_000L, config.aiInspection.sleepWarningDurationMs)
        assertEquals(0.20f, config.aiInspection.sleepQuietGyroMaxRad, 0.001f)
    }

    @Test
    fun `auto sleep config can be overridden from jsonc`() {
        val config = InspectionConfigRepository.buildConfig(
            baseJsonc = """
                {
                  "aiInspection": {
                    "sleepWakingDurationMs": 30000,
                    "sleepWarningDurationMs": 10000,
                    "sleepQuietGyroMaxRad": 0.15
                  }
                }
            """.trimIndent(),
            overlayJsonc = null,
        )

        assertEquals(30_000L, config.aiInspection.sleepWakingDurationMs)
        assertEquals(10_000L, config.aiInspection.sleepWarningDurationMs)
        assertEquals(0.15f, config.aiInspection.sleepQuietGyroMaxRad, 0.001f)
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

    @Test
    fun `scene hazard detection config can be enabled with general deep api`() {
        val config = InspectionConfigRepository.buildConfig(
            baseJsonc = """
                {
                  "aiInspection": {
                    "enableOnlineSceneHazardDetection": true,
                    "onlineSceneDetectIntervalMs": 3000
                  },
                  "network": {
                    "aiGeneralDeepApi": {
                      "url": "http://example.test/ai/general_deep",
                      "detectTimeoutMs": 2500
                    }
                  }
                }
            """.trimIndent(),
            overlayJsonc = null,
        )

        assertTrue(config.aiInspection.enableOnlineSceneHazardDetection)
        assertEquals(3000L, config.aiInspection.onlineSceneDetectIntervalMs)
        assertEquals("http://example.test/ai/general_deep", config.network.aiGeneralDeepApi.url)
        assertEquals(2500L, config.network.aiGeneralDeepApi.detectTimeoutMs)
    }
}
