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
    val appVisibility: AppVisibilityConfig = AppVisibilityConfig(),
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
    val autoDetectProvider: AutoDetectProvider = AutoDetectProvider.HTTP,
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
    val enableAutoSleepMonitoring: Boolean = true,
    // 共享相机 zoom 倍率，控制 NV21 帧流的视野范围。1.0=最大视野，值越大画面越近。
    // SDK zoom 分 3 档：<1.9→level1, 1.9~2.5→level2, >2.5→level3
    val sharedCameraZoomRatio: Float = 1.0f,
    /** WiFi 连接确认间隔（毫秒） */
    val wifiConfirmIntervalMs: Long = 500L,
    /** WiFi 连接确认最大重试次数 */
    val wifiConfirmMaxAttempts: Int = 10,
)

data class NetworkConfig(
    val enterpriseObjectApi: EnterpriseObjectApiConfig = EnterpriseObjectApiConfig(),
    val aiAutoApi: AiArApiConfig = AiArApiConfig(
        url = "http://183.147.142.133:10010/ai/auto",
    ),
    val aiDeepApi: AiArApiConfig = AiArApiConfig(
        url = "http://183.147.142.133:10010/ai/deep",
    ),
    val aiGmApi: AiArApiConfig = AiArApiConfig(
        url = "http://183.147.142.133:10012/ai/gm",
    ),
    val aiGeneralApi: AiArApiConfig = AiArApiConfig(
        url = "http://183.147.142.133:10010/ai/general",
    ),
    val aiGeneralDeepApi: AiArApiConfig = AiArApiConfig(
        url = "http://183.147.142.133:10010/ai/general_deep",
    ),
    val aiDeviceApi: AiArApiConfig = AiArApiConfig(
        url = "http://183.147.142.133:10010/ai/device",
        detectTimeoutMs = 3_000L,
    ),
    val aiSuggestionChecksApi: AiArApiConfig = AiArApiConfig(
        url = "http://183.147.142.133:10010/ai/sug_checks",
        detectTimeoutMs = 3_000L,
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
    val url: String = "http://183.147.142.133:10010/ai/auto",
    val connectTimeoutMs: Long = 15_000L,
    val readTimeoutMs: Long = 45_000L,
    val writeTimeoutMs: Long = 30_000L,
    val detectTimeoutMs: Long = 4_000L,
)

data class SaveResultApiConfig(
    val primarySaveResultUrl: String = "http://183.147.142.133:7443/hxy/apis/third/smartGlasses/isSave",
    val backupBaseUrl: String = "http://183.147.142.133:7443",
    val enableBackupUpload: Boolean = false,
    val connectTimeoutMs: Long = 30_000L,
    val readTimeoutMs: Long = 30_000L,
    val writeTimeoutMs: Long = 30_000L,
) {
    val backupSaveResultUrl: String
        get() = "${backupBaseUrl.trimEnd('/')}/hxy/apis/hazardCheckRecord/saveHazard"

    val backupFinishResultUrl: String
        get() = "${backupBaseUrl.trimEnd('/')}/hxy/apis/hazardCheckRecord/hazardIsEnd"
}

data class MayHazardVerifyApiConfig(
    val answerUrl: String = "http://183.147.142.133:8006/has_hazard_answer",
    val connectTimeoutMs: Long = 15_000L,
    val readTimeoutMs: Long = 30_000L,
    val writeTimeoutMs: Long = 30_000L,
)

/**
 * 应用可见性模式。
 * 控制眼镜系统应用列表中显示哪些内置应用。
 */
enum class AppVisibilityMode {
    /** 显示所有内置应用 + 第三方应用（调试用）。 */
    FULL,
    /** 仅显示隐患巡检 + 扫一扫（生产用）。 */
    MINIMAL,
}

/**
 * 应用可见性配置。
 */
data class AppVisibilityConfig(
    val mode: AppVisibilityMode = AppVisibilityMode.FULL,
)

/**
 * JSONC 解析用的 nullable override 模型。
 */
data class AppVisibilityConfigOverride(
    val mode: AppVisibilityMode? = null,
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

enum class AutoDetectProvider {
    HTTP,
    LOCAL_TRIGGER,
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
    val appVisibility: AppVisibilityConfigOverride? = null,
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
    val autoDetectProvider: AutoDetectProvider? = null,
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
    val enableAutoSleepMonitoring: Boolean? = null,
    val sharedCameraZoomRatio: Float? = null,
    val wifiConfirmIntervalMs: Long? = null,
    val wifiConfirmMaxAttempts: Int? = null,
)

data class NetworkConfigOverride(
    val enterpriseObjectApi: EnterpriseObjectApiConfigOverride? = null,
    val aiAutoApi: AiArApiConfigOverride? = null,
    val aiDeepApi: AiArApiConfigOverride? = null,
    val aiGmApi: AiArApiConfigOverride? = null,
    val aiGeneralApi: AiArApiConfigOverride? = null,
    val aiGeneralDeepApi: AiArApiConfigOverride? = null,
    val aiDeviceApi: AiArApiConfigOverride? = null,
    val aiSuggestionChecksApi: AiArApiConfigOverride? = null,
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
    val enableBackupUpload: Boolean? = null,
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
