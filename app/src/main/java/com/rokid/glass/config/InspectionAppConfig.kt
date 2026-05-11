package com.rokid.glass.config

/**
 * 巡检链路统一配置。
 * 所有业务参数优先从 JSONC 读取，缺失时回退到这里的代码默认值。
 */
data class InspectionAppConfig(
    val featureFlags: FeatureFlagsConfig = FeatureFlagsConfig(),
    val enterpriseScan: EnterpriseScanConfig = EnterpriseScanConfig(),
    val enterpriseInfo: EnterpriseInfoConfig = EnterpriseInfoConfig(),
    val aiInspection: AiInspectionConfig = AiInspectionConfig(),
    val network: NetworkConfig = NetworkConfig(),
)

data class FeatureFlagsConfig(
    val enableEnterpriseInspectionFlow: Boolean = true,
)

data class EnterpriseScanConfig(
    val scanIntervalMs: Long = 800L,
    val scanFrameTargetSize: Int = 1080,
    val enableCameraRecovery: Boolean = true,
)

data class EnterpriseInfoConfig(
    val maxHazardHistoryDisplayCount: Int = 3,
    val recentInspectionTimeFallbackText: String = "最近巡查时间：2026年1月21日",
    val layoutMode: EnterpriseInfoLayoutMode = EnterpriseInfoLayoutMode.NEW,
)

enum class EnterpriseInfoLayoutMode {
    NEW,
}

data class AiInspectionConfig(
    val autoInferenceMode: AutoInferenceMode = AutoInferenceMode.BOTH,
    val autoHazardRoutingMode: AutoHazardRoutingMode = AutoHazardRoutingMode.SEPARATED,
    val captureWarmupMs: Long = 1200L,
    val autoInferenceRetryDelayMs: Long = 80L,
    val autoHazardPresentDelayMs: Long = 3000L,
    val localLabelCooldownMs: Long = 15_000L,
    val streamThumbnailTargetPx: Int = 160,
    val localSaveSuccessToastMs: Int = 1500,
    val backend: InferenceBackend = InferenceBackend.GPU,
    val gpuProfile: GpuProfile = GpuProfile.BALANCED_FP16,
    val targetInputSize: Int = 640,
    val enableHitCaptureSave: Boolean = false,
    val enableOnlineAdvicePage: Boolean = true,
    val staleFrameThresholdMs: Long = 1200L,
    val sharedFrameMotionClearThresholdMs: Long = 1000L,
    val enableHeadMotionStabilityGate: Boolean = false,
    val onlineJpegQuality: Int = 85,
    val onlineSelectWindowMs: Long = 240L,
    val onlineSelectMaxFrames: Int = 3,
    val onlineSelectPollIntervalMs: Long = 80L,
    val onlineDetectIntervalMs: Long = 500L,
    val onlineDetectConcurrencyLimit: Int = 5,
    val enableOnlineSceneHazardDetection: Boolean = false,
    val onlineSceneDetectIntervalMs: Long = 3000L,
    val remoteFailureFallbackThreshold: Int = 3,
    val enableLocalFallbackLoading: Boolean = false,
    val localNetworkProbeIntervalMs: Long = 3000L,
    val forceOnlineDetailForLocalHazard: Boolean = false,
)

data class NetworkConfig(
    val enterpriseObjectApi: EnterpriseObjectApiConfig = EnterpriseObjectApiConfig(),
    val aiArApi: AiArApiConfig = AiArApiConfig(),
    val deviceGuideDetectApi: AiArApiConfig = AiArApiConfig(
        url = "http://183.147.142.133:50011/ai/ar",
    ),
    val saveResultApi: SaveResultApiConfig = SaveResultApiConfig(),
    val mayHazardVerifyApi: MayHazardVerifyApiConfig = MayHazardVerifyApiConfig(),
)

data class EnterpriseObjectApiConfig(
    val connectTimeoutMs: Long = 15_000L,
    val readTimeoutMs: Long = 30_000L,
    val writeTimeoutMs: Long = 30_000L,
)

data class AiArApiConfig(
    val url: String = "http://183.147.142.133:5000/ai/ar",
    val connectTimeoutMs: Long = 15_000L,
    val readTimeoutMs: Long = 45_000L,
    val writeTimeoutMs: Long = 30_000L,
    val detectTimeoutMs: Long = 1_500L,
)

data class SaveResultApiConfig(
    val primarySaveResultUrl: String = "http://183.147.142.133:7443/hxy/apis/third/smartGlasses/isSave",
    val backupBaseUrl: String = "http://183.147.142.133:7443",
    val connectTimeoutMs: Long = 30_000L,
    val readTimeoutMs: Long = 30_000L,
    val writeTimeoutMs: Long = 30_000L,
) {
    val backupSaveResultUrl: String
        get() = "${backupBaseUrl.trimEnd('/')}/hxy/apis/hazardCheckRecord/saveHazard"
}

data class MayHazardVerifyApiConfig(
    val answerUrl: String = "http://183.147.142.133:8006/has_hazard_answer",
    val connectTimeoutMs: Long = 15_000L,
    val readTimeoutMs: Long = 30_000L,
    val writeTimeoutMs: Long = 30_000L,
)

enum class AutoInferenceMode {
    LOCAL_ONLY,
    ONLINE_ONLY,
    BOTH,
}

enum class AutoHazardRoutingMode {
    SEPARATED,
    ONLINE_ONLY,
    LOCAL_ONLY,
}

enum class InferenceBackend(val code: Int) {
    CPU(0),
    GPU(1),
    TURNIP(2),
}

enum class GpuProfile(val code: Int) {
    SAFE_FP32(0),
    BALANCED_FP16(1),
    NO_PACKING_FP32(2),
}

/**
 * JSONC 解析使用的 nullable override 模型。
 * 配置文件字段缺失或非法时，通过 merge 逻辑回退到代码默认值。
 */
data class InspectionAppConfigOverride(
    val featureFlags: FeatureFlagsConfigOverride? = null,
    val enterpriseScan: EnterpriseScanConfigOverride? = null,
    val enterpriseInfo: EnterpriseInfoConfigOverride? = null,
    val aiInspection: AiInspectionConfigOverride? = null,
    val network: NetworkConfigOverride? = null,
)

data class FeatureFlagsConfigOverride(
    val enableEnterpriseInspectionFlow: Boolean? = null,
)

data class EnterpriseScanConfigOverride(
    val scanIntervalMs: Long? = null,
    val scanFrameTargetSize: Int? = null,
    val enableCameraRecovery: Boolean? = null,
)

data class EnterpriseInfoConfigOverride(
    val maxHazardHistoryDisplayCount: Int? = null,
    val recentInspectionTimeFallbackText: String? = null,
    val layoutMode: EnterpriseInfoLayoutMode? = null,
)

data class AiInspectionConfigOverride(
    val autoInferenceMode: AutoInferenceMode? = null,
    val autoHazardRoutingMode: AutoHazardRoutingMode? = null,
    val captureWarmupMs: Long? = null,
    val autoInferenceRetryDelayMs: Long? = null,
    val autoHazardPresentDelayMs: Long? = null,
    val localLabelCooldownMs: Long? = null,
    val streamThumbnailTargetPx: Int? = null,
    val localSaveSuccessToastMs: Int? = null,
    val backend: InferenceBackend? = null,
    val gpuProfile: GpuProfile? = null,
    val targetInputSize: Int? = null,
    val enableHitCaptureSave: Boolean? = null,
    val enableOnlineAdvicePage: Boolean? = null,
    val staleFrameThresholdMs: Long? = null,
    val sharedFrameMotionClearThresholdMs: Long? = null,
    val enableHeadMotionStabilityGate: Boolean? = null,
    val onlineJpegQuality: Int? = null,
    val onlineSelectWindowMs: Long? = null,
    val onlineSelectMaxFrames: Int? = null,
    val onlineSelectPollIntervalMs: Long? = null,
    val onlineDetectIntervalMs: Long? = null,
    val onlineDetectConcurrencyLimit: Int? = null,
    val enableOnlineSceneHazardDetection: Boolean? = null,
    val onlineSceneDetectIntervalMs: Long? = null,
    val remoteFailureFallbackThreshold: Int? = null,
    val enableLocalFallbackLoading: Boolean? = null,
    val localNetworkProbeIntervalMs: Long? = null,
    val forceOnlineDetailForLocalHazard: Boolean? = null,
)

data class NetworkConfigOverride(
    val enterpriseObjectApi: EnterpriseObjectApiConfigOverride? = null,
    val aiArApi: AiArApiConfigOverride? = null,
    val deviceGuideDetectApi: AiArApiConfigOverride? = null,
    val saveResultApi: SaveResultApiConfigOverride? = null,
    val mayHazardVerifyApi: MayHazardVerifyApiConfigOverride? = null,
)

data class EnterpriseObjectApiConfigOverride(
    val connectTimeoutMs: Long? = null,
    val readTimeoutMs: Long? = null,
    val writeTimeoutMs: Long? = null,
)

data class AiArApiConfigOverride(
    val url: String? = null,
    val connectTimeoutMs: Long? = null,
    val readTimeoutMs: Long? = null,
    val writeTimeoutMs: Long? = null,
    val detectTimeoutMs: Long? = null,
)

data class SaveResultApiConfigOverride(
    val primarySaveResultUrl: String? = null,
    val backupBaseUrl: String? = null,
    val connectTimeoutMs: Long? = null,
    val readTimeoutMs: Long? = null,
    val writeTimeoutMs: Long? = null,
)

data class MayHazardVerifyApiConfigOverride(
    val answerUrl: String? = null,
    val connectTimeoutMs: Long? = null,
    val readTimeoutMs: Long? = null,
    val writeTimeoutMs: Long? = null,
)
