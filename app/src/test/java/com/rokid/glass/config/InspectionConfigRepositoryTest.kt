package com.rokid.glass.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionConfigRepositoryTest {

    @Test
    fun `business mock enables fixed scene and disables uploads`() {
        val config = InspectionConfigRepository.buildConfig(
            baseJsonc = """
                {
                  "businessMock": {
                    "enabled": true,
                    "placeCode": "XFAQ-JXCS-001",
                    "allowHazardUpload": false,
                    "allowFinishUpload": false
                  }
                }
            """.trimIndent(),
            overlayJsonc = null,
        )

        assertTrue(config.businessMock.enabled)
        assertEquals("XFAQ-JXCS-001", config.businessMock.placeCode)
        assertFalse(config.businessMock.allowHazardUpload)
        assertFalse(config.businessMock.allowFinishUpload)
    }

    @Test
    fun `network access defaults to online`() {
        val config = InspectionConfigRepository.buildConfig(
            baseJsonc = null,
            overlayJsonc = null,
        )

        assertEquals(NetworkAccessMode.ONLINE, config.featureFlags.networkAccessMode)
    }

    @Test
    fun `offline local overlay disables enterprise and remote routes`() {
        val config = InspectionConfigRepository.buildConfig(
            baseJsonc = "{}",
            overlayJsonc = """
                {
                  "featureFlags": {
                    "enableEnterpriseInspectionFlow": false,
                    "networkAccessMode": "OFFLINE_LOCAL"
                  },
                  "aiInspection": {
                    "autoInferenceMode": "LOCAL_ONLY",
                    "autoHazardRoutingMode": "LOCAL_ONLY",
                    "autoDetectProvider": "LOCAL_TRIGGER",
                    "enableOnlineSceneHazardDetection": false,
                    "forceOnlineDetailForLocalHazard": false,
                    "forceLocalHazardDetailAnalysis": true
                  }
                }
            """.trimIndent(),
        )

        assertFalse(config.featureFlags.enableEnterpriseInspectionFlow)
        assertEquals(NetworkAccessMode.OFFLINE_LOCAL, config.featureFlags.networkAccessMode)
        assertEquals(AutoInferenceMode.LOCAL_ONLY, config.aiInspection.autoInferenceMode)
        assertEquals(AutoHazardRoutingMode.LOCAL_ONLY, config.aiInspection.autoHazardRoutingMode)
        assertEquals(AutoDetectProvider.LOCAL_TRIGGER, config.aiInspection.autoDetectProvider)
        assertFalse(config.aiInspection.enableOnlineSceneHazardDetection)
        assertFalse(config.aiInspection.forceOnlineDetailForLocalHazard)
        assertTrue(config.aiInspection.forceLocalHazardDetailAnalysis)
    }

    @Test
    fun `auto detect provider defaults to HTTP`() {
        val config = InspectionConfigRepository.buildConfig(
            baseJsonc = null,
            overlayJsonc = null,
        )

        assertEquals(AutoDetectProvider.HTTP, config.aiInspection.autoDetectProvider)
    }

    @Test
    fun `auto detect provider can be overridden to local trigger`() {
        val config = InspectionConfigRepository.buildConfig(
            baseJsonc = null,
            overlayJsonc = """
                {
                  "aiInspection": {
                    "autoDetectProvider": "LOCAL_TRIGGER"
                  }
                }
            """.trimIndent(),
        )

        assertEquals(AutoDetectProvider.LOCAL_TRIGGER, config.aiInspection.autoDetectProvider)
    }

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
        assertEquals(4000L, config.network.aiGmApi.detectTimeoutMs)
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
    fun `wear monitoring defaults to enabled`() {
        val config = InspectionConfigRepository.buildConfig(
            baseJsonc = null,
            overlayJsonc = null,
        )

        assertTrue(config.aiInspection.enableAutoSleepMonitoring)
    }

    @Test
    fun `wear monitoring can be disabled from jsonc`() {
        val config = InspectionConfigRepository.buildConfig(
            baseJsonc = """
                {
                  "aiInspection": {
                    "enableAutoSleepMonitoring": false
                  }
                }
            """.trimIndent(),
            overlayJsonc = null,
        )

        assertFalse(config.aiInspection.enableAutoSleepMonitoring)
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

    @Test
    fun `data backup save config can be enabled from overlay`() {
        val config = InspectionConfigRepository.buildConfig(
            baseJsonc = null,
            overlayJsonc = """
                {
                  "network": {
                    "saveResultApi": {
                      "enableBackupUpload": true,
                      "backupBaseUrl": "http://backup.test"
                    }
                  }
                }
            """.trimIndent(),
        )

        assertTrue(config.network.saveResultApi.enableBackupUpload)
        assertEquals("http://backup.test/hxy/apis/hazardCheckRecord/saveHazard", config.network.saveResultApi.backupSaveResultUrl)
        assertEquals("http://backup.test/hxy/apis/hazardCheckRecord/hazardIsEnd", config.network.saveResultApi.backupFinishResultUrl)
    }

    @Test
    fun `app visibility defaults to FULL`() {
        val config = InspectionConfigRepository.buildConfig(
            baseJsonc = null,
            overlayJsonc = null,
        )

        assertEquals(AppVisibilityMode.FULL, config.appVisibility.mode)
    }

    @Test
    fun `app visibility can be overridden to MINIMAL from jsonc`() {
        val config = InspectionConfigRepository.buildConfig(
            baseJsonc = """
                {
                  "appVisibility": {
                    "mode": "MINIMAL"
                  }
                }
            """.trimIndent(),
            overlayJsonc = null,
        )

        assertEquals(AppVisibilityMode.MINIMAL, config.appVisibility.mode)
    }

    @Test
    fun `app visibility can be overridden by overlay`() {
        val config = InspectionConfigRepository.buildConfig(
            baseJsonc = """
                {
                  "appVisibility": {
                    "mode": "FULL"
                  }
                }
            """.trimIndent(),
            overlayJsonc = """
                {
                  "appVisibility": {
                    "mode": "MINIMAL"
                  }
                }
            """.trimIndent(),
        )

        assertEquals(AppVisibilityMode.MINIMAL, config.appVisibility.mode)
    }

    @Test
    fun `force local hazard detail analysis defaults to enabled`() {
        val config = InspectionConfigRepository.buildConfig("{}", null)

        assertTrue(config.aiInspection.forceLocalHazardDetailAnalysis)
    }

    @Test
    fun `force local hazard detail analysis can be disabled`() {
        val config = InspectionConfigRepository.buildConfig(
            """{"aiInspection":{"forceLocalHazardDetailAnalysis":false}}""",
            null,
        )

        assertFalse(config.aiInspection.forceLocalHazardDetailAnalysis)
    }

    @Test
    fun `overlay overrides force local hazard detail analysis`() {
        val config = InspectionConfigRepository.buildConfig(
            """{"aiInspection":{"forceLocalHazardDetailAnalysis":false}}""",
            """{"aiInspection":{"forceLocalHazardDetailAnalysis":true}}""",
        )

        assertTrue(config.aiInspection.forceLocalHazardDetailAnalysis)
    }
}
