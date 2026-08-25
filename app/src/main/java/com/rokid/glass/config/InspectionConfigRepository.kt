package com.rokid.glass.config

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.rokid.glesse.BuildConfig
import java.io.StringReader

/**
 * 巡检配置仓库。
 * 负责从 assets 加载 JSONC，并把 base + flavor overlay 合并成最终运行配置。
 */
object InspectionConfigRepository {
    private const val TAG = "InspectionConfig"
    private const val BASE_CONFIG_ASSET = "inspection_config.base.jsonc"

    private val gson = Gson()

    @Volatile
    private var currentConfig: InspectionAppConfig = InspectionAppConfig()

    fun init(context: Context, flavor: String = BuildConfig.FLAVOR): InspectionAppConfig {
        val config = loadFromAssets(context.assets, flavor)
        currentConfig = config
        return config
    }

    fun get(): InspectionAppConfig = currentConfig

    /**
     * 仅供单元测试直接注入 JSONC 文本。
     */
    fun reloadForTest(
        baseJsonc: String,
        overlayJsonc: String? = null,
    ): InspectionAppConfig {
        val config = buildConfig(baseJsonc = baseJsonc, overlayJsonc = overlayJsonc)
        currentConfig = config
        return config
    }

    internal fun buildConfig(
        baseJsonc: String?,
        overlayJsonc: String?,
    ): InspectionAppConfig {
        val defaults = InspectionAppConfig()
        val baseOverride = parseOverride(baseJsonc)
        val overlayOverride = parseOverride(overlayJsonc)
        return merge(
            merge(defaults, baseOverride),
            overlayOverride,
        )
    }

    internal fun parseOverride(jsonc: String?): InspectionAppConfigOverride {
        if (jsonc.isNullOrBlank()) {
            return InspectionAppConfigOverride()
        }
        return runCatching {
            JsonReader(StringReader(jsonc)).use { reader ->
                reader.isLenient = true
                gson.fromJson(reader, InspectionAppConfigOverride::class.java) ?: InspectionAppConfigOverride()
            }
        }.onFailure { error ->
            logError("parse config jsonc failed", error)
        }.getOrDefault(InspectionAppConfigOverride())
    }

    private fun loadFromAssets(
        assets: AssetManager,
        flavor: String,
    ): InspectionAppConfig {
        val baseJsonc = readAssetOrNull(assets, BASE_CONFIG_ASSET)
        val overlayAssetName = overlayAssetName(flavor)
        val overlayJsonc = overlayAssetName?.let { readAssetOrNull(assets, it) }
        val config = buildConfig(baseJsonc = baseJsonc, overlayJsonc = overlayJsonc)
        logInfo("config loaded flavor=$flavor overlay=${overlayAssetName ?: "(none)"}")
        return config
    }

    private fun readAssetOrNull(
        assets: AssetManager,
        assetName: String,
    ): String? {
        return runCatching {
            assets.open(assetName).bufferedReader().use { it.readText() }
        }.onFailure { error ->
            logWarn("read asset failed asset=$assetName message=${error.message}")
        }.getOrNull()
    }

    private fun overlayAssetName(flavor: String): String? {
        val normalized = flavor.trim()
        if (normalized.isEmpty()) {
            return null
        }
        return "inspection_config.$normalized.jsonc"
    }

    private fun merge(
        base: InspectionAppConfig,
        override: InspectionAppConfigOverride,
    ): InspectionAppConfig {
        return InspectionAppConfig(
            featureFlags = merge(base.featureFlags, override.featureFlags),
            businessMock = merge(base.businessMock, override.businessMock),
            enterpriseScan = merge(base.enterpriseScan, override.enterpriseScan),
            enterpriseInfo = merge(base.enterpriseInfo, override.enterpriseInfo),
            aiInspection = merge(base.aiInspection, override.aiInspection),
            network = merge(base.network, override.network),
            appVisibility = merge(base.appVisibility, override.appVisibility),
        )
    }

    private fun merge(
        base: BusinessMockConfig,
        override: BusinessMockConfigOverride?,
    ): BusinessMockConfig {
        return BusinessMockConfig(
            enabled = override?.enabled ?: base.enabled,
            placeCode = override?.placeCode ?: base.placeCode,
            allowHazardUpload = override?.allowHazardUpload ?: base.allowHazardUpload,
            allowFinishUpload = override?.allowFinishUpload ?: base.allowFinishUpload,
        )
    }

    private fun merge(
        base: FeatureFlagsConfig,
        override: FeatureFlagsConfigOverride?,
    ): FeatureFlagsConfig {
        return FeatureFlagsConfig(
            enableEnterpriseInspectionFlow =
                override?.enableEnterpriseInspectionFlow ?: base.enableEnterpriseInspectionFlow,
            networkAccessMode = override?.networkAccessMode ?: base.networkAccessMode,
        )
    }

    private fun merge(
        base: EnterpriseScanConfig,
        override: EnterpriseScanConfigOverride?,
    ): EnterpriseScanConfig {
        return EnterpriseScanConfig(
            scanIntervalMs = override?.scanIntervalMs ?: base.scanIntervalMs,
            scanFrameTargetSize = override?.scanFrameTargetSize ?: base.scanFrameTargetSize,
            enableCameraRecovery = override?.enableCameraRecovery ?: base.enableCameraRecovery,
        )
    }

    private fun merge(
        base: EnterpriseInfoConfig,
        override: EnterpriseInfoConfigOverride?,
    ): EnterpriseInfoConfig {
        return EnterpriseInfoConfig(
            maxHazardHistoryDisplayCount =
                override?.maxHazardHistoryDisplayCount ?: base.maxHazardHistoryDisplayCount,
            recentInspectionTimeFallbackText =
                override?.recentInspectionTimeFallbackText ?: base.recentInspectionTimeFallbackText,
            layoutMode = override?.layoutMode ?: base.layoutMode,
        )
    }

    private fun merge(
        base: AiInspectionConfig,
        override: AiInspectionConfigOverride?,
    ): AiInspectionConfig {
        return AiInspectionConfig(
            autoInferenceMode = override?.autoInferenceMode ?: base.autoInferenceMode,
            autoHazardRoutingMode = override?.autoHazardRoutingMode ?: base.autoHazardRoutingMode,
            autoDetectProvider = override?.autoDetectProvider ?: base.autoDetectProvider,
            captureWarmupMs = override?.captureWarmupMs ?: base.captureWarmupMs,
            autoInferenceRetryDelayMs =
                override?.autoInferenceRetryDelayMs ?: base.autoInferenceRetryDelayMs,
            autoHazardPresentDelayMs =
                override?.autoHazardPresentDelayMs ?: base.autoHazardPresentDelayMs,
            localLabelCooldownMs = override?.localLabelCooldownMs ?: base.localLabelCooldownMs,
            streamThumbnailTargetPx =
                override?.streamThumbnailTargetPx ?: base.streamThumbnailTargetPx,
            localSaveSuccessToastMs =
                override?.localSaveSuccessToastMs ?: base.localSaveSuccessToastMs,
            backend = override?.backend ?: base.backend,
            gpuProfile = override?.gpuProfile ?: base.gpuProfile,
            targetInputSize = override?.targetInputSize ?: base.targetInputSize,
            enableHitCaptureSave = override?.enableHitCaptureSave ?: base.enableHitCaptureSave,
            staleFrameThresholdMs =
                override?.staleFrameThresholdMs ?: base.staleFrameThresholdMs,
            sharedFrameMotionClearThresholdMs =
                override?.sharedFrameMotionClearThresholdMs ?: base.sharedFrameMotionClearThresholdMs,
            enableHeadMotionStabilityGate =
                override?.enableHeadMotionStabilityGate ?: base.enableHeadMotionStabilityGate,
            onlineJpegQuality = override?.onlineJpegQuality ?: base.onlineJpegQuality,
            onlineSelectWindowMs = override?.onlineSelectWindowMs ?: base.onlineSelectWindowMs,
            onlineSelectMaxFrames = override?.onlineSelectMaxFrames ?: base.onlineSelectMaxFrames,
            onlineSelectPollIntervalMs =
                override?.onlineSelectPollIntervalMs ?: base.onlineSelectPollIntervalMs,
            onlineDetectIntervalMs =
                override?.onlineDetectIntervalMs ?: base.onlineDetectIntervalMs,
            onlineDetectConcurrencyLimit =
                override?.onlineDetectConcurrencyLimit ?: base.onlineDetectConcurrencyLimit,
            enableOnlineSceneHazardDetection =
                override?.enableOnlineSceneHazardDetection ?: base.enableOnlineSceneHazardDetection,
            onlineSceneDetectIntervalMs =
                override?.onlineSceneDetectIntervalMs ?: base.onlineSceneDetectIntervalMs,
            remoteFailureFallbackThreshold =
                override?.remoteFailureFallbackThreshold ?: base.remoteFailureFallbackThreshold,
            enableLocalFallbackLoading =
                override?.enableLocalFallbackLoading ?: base.enableLocalFallbackLoading,
            localNetworkProbeIntervalMs =
                override?.localNetworkProbeIntervalMs ?: base.localNetworkProbeIntervalMs,
            forceOnlineDetailForLocalHazard =
                override?.forceOnlineDetailForLocalHazard ?: base.forceOnlineDetailForLocalHazard,
            forceLocalHazardDetailAnalysis =
                override?.forceLocalHazardDetailAnalysis ?: base.forceLocalHazardDetailAnalysis,
            enableAutoSleepMonitoring =
                override?.enableAutoSleepMonitoring ?: base.enableAutoSleepMonitoring,
            sharedCameraZoomRatio =
                override?.sharedCameraZoomRatio ?: base.sharedCameraZoomRatio,
            wifiConfirmIntervalMs =
                override?.wifiConfirmIntervalMs ?: base.wifiConfirmIntervalMs,
            wifiConfirmMaxAttempts =
                override?.wifiConfirmMaxAttempts ?: base.wifiConfirmMaxAttempts,
        )
    }

    private fun merge(
        base: NetworkConfig,
        override: NetworkConfigOverride?,
    ): NetworkConfig {
        return NetworkConfig(
            enterpriseObjectApi = merge(base.enterpriseObjectApi, override?.enterpriseObjectApi),
            aiAutoApi = merge(base.aiAutoApi, override?.aiAutoApi),
            aiDeepApi = merge(base.aiDeepApi, override?.aiDeepApi),
            aiDeepV2Api = merge(base.aiDeepV2Api, override?.aiDeepV2Api),
            aiGeneralDeepV2Api = merge(base.aiGeneralDeepV2Api, override?.aiGeneralDeepV2Api),
            aiGmApi = merge(base.aiGmApi, override?.aiGmApi),
            aiGmV2Api = merge(base.aiGmV2Api, override?.aiGmV2Api),
            aiGeneralApi = merge(base.aiGeneralApi, override?.aiGeneralApi),
            aiGeneralDeepApi = merge(base.aiGeneralDeepApi, override?.aiGeneralDeepApi),
            aiDeviceApi = merge(base.aiDeviceApi, override?.aiDeviceApi),
            aiSuggestionChecksApi = merge(base.aiSuggestionChecksApi, override?.aiSuggestionChecksApi),
            saveResultApi = merge(base.saveResultApi, override?.saveResultApi),
            mayHazardVerifyApi = merge(base.mayHazardVerifyApi, override?.mayHazardVerifyApi),
        )
    }

    private fun merge(
        base: EnterpriseObjectApiConfig,
        override: EnterpriseObjectApiConfigOverride?,
    ): EnterpriseObjectApiConfig {
        return EnterpriseObjectApiConfig(
            connectTimeoutMs = override?.connectTimeoutMs ?: base.connectTimeoutMs,
            readTimeoutMs = override?.readTimeoutMs ?: base.readTimeoutMs,
            writeTimeoutMs = override?.writeTimeoutMs ?: base.writeTimeoutMs,
        )
    }

    private fun merge(
        base: AiArApiConfig,
        override: AiArApiConfigOverride?,
    ): AiArApiConfig {
        return AiArApiConfig(
            url = override?.url ?: base.url,
            connectTimeoutMs = override?.connectTimeoutMs ?: base.connectTimeoutMs,
            readTimeoutMs = override?.readTimeoutMs ?: base.readTimeoutMs,
            writeTimeoutMs = override?.writeTimeoutMs ?: base.writeTimeoutMs,
            detectTimeoutMs = override?.detectTimeoutMs ?: base.detectTimeoutMs,
        )
    }

    private fun merge(
        base: SaveResultApiConfig,
        override: SaveResultApiConfigOverride?,
    ): SaveResultApiConfig {
        return SaveResultApiConfig(
            primarySaveResultUrl = override?.primarySaveResultUrl ?: base.primarySaveResultUrl,
            backupBaseUrl = override?.backupBaseUrl ?: base.backupBaseUrl,
            enableBackupUpload = override?.enableBackupUpload ?: base.enableBackupUpload,
            connectTimeoutMs = override?.connectTimeoutMs ?: base.connectTimeoutMs,
            readTimeoutMs = override?.readTimeoutMs ?: base.readTimeoutMs,
            writeTimeoutMs = override?.writeTimeoutMs ?: base.writeTimeoutMs,
        )
    }

    private fun merge(
        base: MayHazardVerifyApiConfig,
        override: MayHazardVerifyApiConfigOverride?,
    ): MayHazardVerifyApiConfig {
        return MayHazardVerifyApiConfig(
            answerUrl = override?.answerUrl ?: base.answerUrl,
            connectTimeoutMs = override?.connectTimeoutMs ?: base.connectTimeoutMs,
            readTimeoutMs = override?.readTimeoutMs ?: base.readTimeoutMs,
            writeTimeoutMs = override?.writeTimeoutMs ?: base.writeTimeoutMs,
        )
    }

    private fun merge(
        base: AppVisibilityConfig,
        override: AppVisibilityConfigOverride?,
    ): AppVisibilityConfig {
        return AppVisibilityConfig(
            mode = override?.mode ?: base.mode,
        )
    }

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private fun logWarn(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    private fun logError(
        message: String,
        error: Throwable,
    ) {
        runCatching { Log.e(TAG, message, error) }
    }
}
