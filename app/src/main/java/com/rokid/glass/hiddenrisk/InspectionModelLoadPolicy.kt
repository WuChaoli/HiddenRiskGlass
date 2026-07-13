package com.rokid.glass.hiddenrisk

import com.rokid.glass.config.AiInspectionConfig
import com.rokid.glass.config.AutoDetectProvider
import com.rokid.glass.config.AutoHazardRoutingMode
import com.rokid.glass.config.AutoInferenceMode

/** 统一判断巡检业务是否需要本地模型，以及当前会话是否可以放行。 */
internal object InspectionModelLoadPolicy {

    fun requiresModel(config: AiInspectionConfig): Boolean {
        return config.autoDetectProvider == AutoDetectProvider.LOCAL_TRIGGER ||
            config.autoInferenceMode == AutoInferenceMode.LOCAL_ONLY ||
            config.autoHazardRoutingMode == AutoHazardRoutingMode.LOCAL_ONLY ||
            config.enableLocalFallbackLoading
    }

    fun isSessionReady(
        config: AiInspectionConfig,
        isInitialized: Boolean,
        isModelLoaded: Boolean,
    ): Boolean {
        return isInitialized && (!requiresModel(config) || isModelLoaded)
    }
}
