package com.rokid.glass.hiddenrisk

import com.rokid.glass.config.AiInspectionConfig
import com.rokid.glass.config.AutoDetectProvider
import com.rokid.glass.config.AutoHazardRoutingMode
import com.rokid.glass.config.AutoInferenceMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionModelLoadPolicyTest {

    @Test
    fun `local trigger requires model`() {
        val config = AiInspectionConfig(autoDetectProvider = AutoDetectProvider.LOCAL_TRIGGER)

        assertTrue(InspectionModelLoadPolicy.requiresModel(config))
    }

    @Test
    fun `local only inference requires model`() {
        val config = AiInspectionConfig(autoInferenceMode = AutoInferenceMode.LOCAL_ONLY)

        assertTrue(InspectionModelLoadPolicy.requiresModel(config))
    }

    @Test
    fun `local only routing requires model`() {
        val config = AiInspectionConfig(autoHazardRoutingMode = AutoHazardRoutingMode.LOCAL_ONLY)

        assertTrue(InspectionModelLoadPolicy.requiresModel(config))
    }

    @Test
    fun `local fallback requires model`() {
        val config = AiInspectionConfig(enableLocalFallbackLoading = true)

        assertTrue(InspectionModelLoadPolicy.requiresModel(config))
    }

    @Test
    fun `remote only configuration does not require model`() {
        val config = AiInspectionConfig(
            autoInferenceMode = AutoInferenceMode.ONLINE_ONLY,
            autoHazardRoutingMode = AutoHazardRoutingMode.ONLINE_ONLY,
            autoDetectProvider = AutoDetectProvider.HTTP,
            enableLocalFallbackLoading = false,
        )

        assertFalse(InspectionModelLoadPolicy.requiresModel(config))
    }

    @Test
    fun `required model must be loaded before session is ready`() {
        val config = AiInspectionConfig(autoDetectProvider = AutoDetectProvider.LOCAL_TRIGGER)

        assertFalse(
            InspectionModelLoadPolicy.isSessionReady(
                config = config,
                isInitialized = true,
                isModelLoaded = false,
            ),
        )
        assertTrue(
            InspectionModelLoadPolicy.isSessionReady(
                config = config,
                isInitialized = true,
                isModelLoaded = true,
            ),
        )
    }

    @Test
    fun `initialized remote session is ready without model`() {
        val config = AiInspectionConfig(
            autoInferenceMode = AutoInferenceMode.ONLINE_ONLY,
            autoHazardRoutingMode = AutoHazardRoutingMode.ONLINE_ONLY,
        )

        assertTrue(
            InspectionModelLoadPolicy.isSessionReady(
                config = config,
                isInitialized = true,
                isModelLoaded = false,
            ),
        )
    }
}
