package com.rokid.glass.hiddenrisk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rokid.glass.AiInspectionMenuActivity
import com.rokid.glass.InspectionEndReportActivity
import com.rokid.glass.InspectionEndReportReturnDestination
import com.rokid.glass.InspectionFeatureFlags
import com.rokid.glass.camera.RokidCameraRecoveryController
import com.rokid.glass.camera.RokidFrameSource
import com.rokid.glass.config.AutoHazardRoutingMode as ConfigAutoHazardRoutingMode
import com.rokid.glass.config.InspectionConfigRepository
import com.rokid.glass.component.AlertBehavior
import com.rokid.glass.component.AlertStatus
import com.rokid.glass.component.AlertStyle
import com.rokid.glass.component.FunctionMenuView
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.component.GlassStatusBarUpdater
import com.rokid.glass.component.RokidCameraPreviewView
import com.rokid.glass.component.StatusAlertModel
import com.rokid.glass.component.StatusAlertOverlayView
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator.CameraOwner
import com.rokid.glass.hiddenrisk.InspectionFrameCaptureService.CapturedFramePayload
import com.rokid.glass.hiddenrisk.InspectionFrameCaptureService.SquareFramePayload
import com.rokid.glass.hiddenrisk.state.TtsState
import com.rokid.glass.input.GlassesWearStateMachine
import com.rokid.glass.input.HeadMotionStabilityTracker
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.utils.AppFileLogger
import com.rokid.glass.utils.BitmapUtils
import com.rokid.glass.utils.SpriteToastUtil
import com.rokid.glass.utils.OfflineTtsPlayer
import com.rokid.glass.utils.SystemStateUtils
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glesse.R
import java.io.InputStreamReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AI 巡检页面。
 * 流程：加载初始化 -> 周期抓拍 -> 远端双在线竞争识别 / 本地备用链路 -> 结果确认/保存。
 */
class AiInspectionActivity : BaseGlassActivity(), RokidSdkManager.Listener {

    override val wearSleepEnabled: Boolean
        get() = true

    override fun shouldEnableWearSleepNow(): Boolean {
        return enableAutoSleepMonitoring &&
            debugSnapshotState == null &&
            isActivityResumed &&
            isWorkflowActive &&
            pageState == PageState.DETECTING &&
            pendingAutoHazardPresentation == null
    }

    private lateinit var layoutLoading: View

    companion object {
        private const val TAG = "AiInspection"
        private const val REQUEST_MEDIA_PERMISSION = 201
        private const val LOCAL_HAZARD_INFO_ASSET = "info.json"
        private const val ADVICE_DISPLAY_PREFIX = "基于上述隐患，建议您重点关注以下问题："
        private const val ADVICE_CARD_FLOAT_ANIMATION_MS = 260L
        private const val UPLOAD_SUCCESS_TOAST_VISIBLE_MS = 3000L
        private const val UPLOAD_SUCCESS_TOAST_FADE_MS = 300L
        private const val SIMULATED_STREAM_CHUNK_CHARS = 12
        private const val SIMULATED_STREAM_CHUNK_DELAY_MS = 35L
        private const val DETECTION_PREVIEW_DRAW_CHECK_DELAY_MS = 700L
        private const val MAX_DETAIL_BODY_LOG_CHARS = 4096

        private val CAPTURE_WARMUP_MS: Long by lazy { InspectionConfigRepository.get().aiInspection.captureWarmupMs }

        private val AUTO_INFERENCE_RETRY_DELAY_MS: Long by lazy { InspectionConfigRepository.get().aiInspection.autoInferenceRetryDelayMs }

        private val AUTO_HAZARD_PRESENT_DELAY_MS: Long by lazy { InspectionConfigRepository.get().aiInspection.autoHazardPresentDelayMs }

        private val LOCAL_LABEL_COOLDOWN_MS: Long by lazy { InspectionConfigRepository.get().aiInspection.localLabelCooldownMs }

        private val STREAM_THUMBNAIL_TARGET_PX: Int by lazy { InspectionConfigRepository.get().aiInspection.streamThumbnailTargetPx }

        private val LOCAL_SAVE_SUCCESS_TOAST_MS: Int by lazy { InspectionConfigRepository.get().aiInspection.localSaveSuccessToastMs }

        private val BACKEND_GPU: Int by lazy { InspectionConfigRepository.get().aiInspection.backend.code }

        private val GPU_PROFILE_BALANCED_FP16: Int by lazy { InspectionConfigRepository.get().aiInspection.gpuProfile.code }

        private val DEFAULT_TARGET_INPUT_SIZE: Int by lazy { InspectionConfigRepository.get().aiInspection.targetInputSize }

        private val ENABLE_HIT_CAPTURE_SAVE: Boolean by lazy { InspectionConfigRepository.get().aiInspection.enableHitCaptureSave }

        private val STALE_FRAME_THRESHOLD_MS: Long by lazy { InspectionConfigRepository.get().aiInspection.staleFrameThresholdMs }

        private val SHARED_FRAME_MOTION_CLEAR_THRESHOLD_MS: Long by lazy { InspectionConfigRepository.get().aiInspection.sharedFrameMotionClearThresholdMs }

        private val ENABLE_HEAD_MOTION_STABILITY_GATE: Boolean by lazy { InspectionConfigRepository.get().aiInspection.enableHeadMotionStabilityGate }

        private val ONLINE_JPEG_QUALITY: Int by lazy { InspectionConfigRepository.get().aiInspection.onlineJpegQuality }

        private val ONLINE_SELECT_WINDOW_MS: Long by lazy { InspectionConfigRepository.get().aiInspection.onlineSelectWindowMs }

        private val ONLINE_SELECT_MAX_FRAMES: Int by lazy { InspectionConfigRepository.get().aiInspection.onlineSelectMaxFrames }

        internal fun decideOnlineLabelCooldown(
            labels: List<String>,
            isCooling: (String) -> Boolean,
        ): OnlineLabelCooldownDecision {
            val normalizedLabels = labels
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            if (normalizedLabels.isEmpty()) {
                return OnlineLabelCooldownDecision(
                    shouldSuppress = false,
                    activeLabels = emptyList(),
                )
            }
            val activeLabels = normalizedLabels.filterNot(isCooling)
            return OnlineLabelCooldownDecision(
                shouldSuppress = activeLabels.isEmpty(),
                activeLabels = activeLabels,
            )
        }

        private val ONLINE_SELECT_POLL_INTERVAL_MS: Long by lazy { InspectionConfigRepository.get().aiInspection.onlineSelectPollIntervalMs }

        private val AUTO_HAZARD_ROUTING_MODE: AutoHazardRoutingMode by lazy {
            when (InspectionConfigRepository.get().aiInspection.autoHazardRoutingMode) {
                ConfigAutoHazardRoutingMode.SEPARATED -> AutoHazardRoutingMode.SEPARATED
                ConfigAutoHazardRoutingMode.ONLINE_ONLY -> AutoHazardRoutingMode.ONLINE_ONLY
                ConfigAutoHazardRoutingMode.LOCAL_ONLY -> AutoHazardRoutingMode.LOCAL_ONLY
            }
        }

        private fun summarizeLogText(text: String): String {
            val normalized = text.replace("\r", "\\r").replace("\n", "\\n")
            return if (normalized.length <= MAX_DETAIL_BODY_LOG_CHARS) {
                normalized
            } else {
                "${normalized.take(MAX_DETAIL_BODY_LOG_CHARS)}...(truncated ${normalized.length - MAX_DETAIL_BODY_LOG_CHARS} chars)"
            }
        }
    }

    private enum class AutoHazardRoutingMode {
        SEPARATED,
        ONLINE_ONLY,
        LOCAL_ONLY,
    }

    private enum class AutoHazardTriggerSource {
        LOCAL,
        ONLINE,
    }

    private enum class AutoHazardResultRoute {
        LOCAL,
        ONLINE,
    }

    /**
     * 页面的可见状态。
     */
    private enum class PageState {
        DETECTING,        // 自动取景识别中
        STREAM_RESPONSE,  // 深度识别隐患，流式回答 + 保存确认
    }

    private data class LocalHazardInfo(
        val item: List<String> = emptyList(),
        val descrip: String = "",
        val hidLevel: String = "",
        val lawBasis: String = "",
        val hidNum: String = "",
        val advice: String = "",
        val modify: String = "",
        val description: String = "",
    ) {
        fun requestDescription(): String {
            return descrip.trim()
                .ifBlank { legacyLineValue("隐患描述") }
                .ifBlank { description.trim() }
        }

        fun requestHidLevel(): String {
            return hidLevel.trim()
                .ifBlank { ResolvedHazardContent.levelCode(legacyLineValue("隐患等级")) }
        }

        fun requestLawBasis(): String {
            return lawBasis.trim()
                .ifBlank { legacyLineValue("法律依据") }
        }

        fun requestHidNum(): String {
            return hidNum.trim()
                .ifBlank { legacyLineValue("隐患编码") }
        }

        fun requestAdvice(): String {
            val trimmedAdvice = advice.trim()
            return when {
                trimmedAdvice.startsWith("整改建议：") -> trimmedAdvice.removePrefix("整改建议：").trim()
                trimmedAdvice.startsWith("整改建议:") -> trimmedAdvice.removePrefix("整改建议:").trim()
                trimmedAdvice.startsWith("建议重点检查：") -> trimmedAdvice.removePrefix("建议重点检查：").trim()
                trimmedAdvice.startsWith("建议重点检查:") -> trimmedAdvice.removePrefix("建议重点检查:").trim()
                else -> trimmedAdvice
            }
        }

        fun requestModify(): String {
            val trimmedModify = modify.trim()
            return when {
                trimmedModify.startsWith("整改建议：") -> trimmedModify.removePrefix("整改建议：").trim()
                trimmedModify.startsWith("整改建议:") -> trimmedModify.removePrefix("整改建议:").trim()
                else -> trimmedModify
            }
        }

        fun toResolvedContent(
            matchedItem: String,
            jpegBytes: ByteArray,
        ): ResolvedHazardContent {
            return ResolvedHazardContent(
                source = HazardSource.LOCAL,
                description = requestDescription(),
                advice = requestAdvice(),
                uploadAdvice = requestModify(),
                hidLevel = requestHidLevel(),
                hidNum = requestHidNum(),
                lawBasis = requestLawBasis(),
                displayTitle = matchedItem,
                jpegBytes = jpegBytes.copyOf(),
                hazards = listOf(toResolvedItem(matchedItem)),
            )
        }

        fun toResolvedItem(matchedItem: String): ResolvedHazardItem {
            return ResolvedHazardItem(
                displayTitle = matchedItem,
                description = requestDescription(),
                advice = requestAdvice(),
                uploadAdvice = requestModify(),
                hidLevel = requestHidLevel(),
                hidNum = requestHidNum(),
                lawBasis = requestLawBasis(),
            )
        }

        private fun legacyLineValue(prefix: String): String {
            val line = description.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("$prefix：") || it.startsWith("$prefix:") }
                .orEmpty()
            return when {
                line.startsWith("$prefix：") -> line.substringAfter("：").trim()
                line.startsWith("$prefix:") -> line.substringAfter(":").trim()
                else -> ""
            }
        }

    }

    private data class LocalHazardMatch(
        val info: LocalHazardInfo,
        val matchedItem: String,
        val cooldownLabel: String,
        val score: Float,
    ) {
        fun toResolvedItem(): ResolvedHazardItem {
            return info.toResolvedItem(matchedItem)
        }
    }

    private enum class LocalResultStage {
        NONE,
        DESCRIPTION,
        ADVICE,
    }

    private sealed class PendingAutoHazardPresentation {
        abstract val detectedAtElapsedMs: Long

        data class Local(
            override val detectedAtElapsedMs: Long,
            val resolved: ResolvedHazardContent,
        ) : PendingAutoHazardPresentation()

        data class Online(
            override val detectedAtElapsedMs: Long,
            val requestId: Long,
            val jpegBytes: ByteArray,
            val cooldownLabels: List<String> = emptyList(),
            val baseResolved: ResolvedHazardContent? = null,
            val resolved: ResolvedHazardContent? = null,
            val streamedText: String = "",
            val firstChunkReceived: Boolean = false,
            val streamPageShown: Boolean = false,
        ) : PendingAutoHazardPresentation()
    }

    private data class OnlineDetectionLaneRuntime(
        var loopRunning: Boolean = false,
        var loopEpoch: Long = 0L,
        var lastFrameTimestamp: Long = 0L,
        var queuedNext: Boolean = false,
        var loopPosted: Boolean = false,
        var frameSelectionInProgress: Boolean = false,
        var nextEarliestStartElapsedMs: Long = 0L,
        val activeRequestIds: MutableSet<Long> = linkedSetOf(),
    ) {
        val requestInFlight: Boolean
            get() = activeRequestIds.isNotEmpty()
    }

    private val onlineOnlyRouteEnabled: Boolean by lazy { AUTO_HAZARD_ROUTING_MODE == AutoHazardRoutingMode.ONLINE_ONLY }

    private val localOnlyRouteEnabled: Boolean by lazy { AUTO_HAZARD_ROUTING_MODE == AutoHazardRoutingMode.LOCAL_ONLY }

    private val onlineDetectIntervalMs: Long by lazy { InspectionConfigRepository.get().aiInspection.onlineDetectIntervalMs }

    private val enableOnlineSceneHazardDetection: Boolean by lazy { InspectionConfigRepository.get().aiInspection.enableOnlineSceneHazardDetection }

    private val onlineSceneDetectIntervalMs: Long by lazy { InspectionConfigRepository.get().aiInspection.onlineSceneDetectIntervalMs }

    private val remoteFailureFallbackThreshold: Int by lazy { InspectionConfigRepository.get().aiInspection.remoteFailureFallbackThreshold }

    private val enableLocalFallbackLoading: Boolean by lazy { InspectionConfigRepository.get().aiInspection.enableLocalFallbackLoading }

    private val localNetworkProbeIntervalMs: Long by lazy { InspectionConfigRepository.get().aiInspection.localNetworkProbeIntervalMs }

    private val forceOnlineDetailForLocalHazard: Boolean by lazy { InspectionConfigRepository.get().aiInspection.forceOnlineDetailForLocalHazard }
    private val enableAutoSleepMonitoring: Boolean by lazy { InspectionConfigRepository.get().aiInspection.enableAutoSleepMonitoring }

    // --- UI ---
    private lateinit var layoutDetection: FrameLayout
    private lateinit var layoutLivePreviewCard: FrameLayout
    private lateinit var viewLivePreview: RokidCameraPreviewView
    private lateinit var statusAlertOverlay: StatusAlertOverlayView
    private lateinit var tvDetectingBottomHint: TextView
    private lateinit var layoutStreamResponse: FrameLayout
    private lateinit var layoutStreamContentContainer: LinearLayout
    private lateinit var layoutStreamThumbnailCard: FrameLayout
    private lateinit var layoutStreamThumbnailPlaceholder: FrameLayout
    private lateinit var streamTopSpacer: View
    private lateinit var streamBottomSpacer: View
    private lateinit var tvStreamContent: TextView
    private lateinit var scrollContent: ScrollView
    private lateinit var ivStreamThumbnail: ImageView
    private lateinit var tvStreamBottomHint: TextView
    private lateinit var tvUploadSuccessToast: TextView
    private lateinit var operationGuideDetecting: FunctionMenuView
    private lateinit var operationGuideStream: FunctionMenuView
    private var currentStreamThumbnail: Bitmap? = null
    // 检测状态UI
    private lateinit var statusBarDetecting: GlassStatusBar
    private lateinit var statusBarStream: GlassStatusBar

    private val uiHandler = Handler(Looper.getMainLooper())
    private val nativeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val imageEncodeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val frameCaptureService by lazy {
        InspectionFrameCaptureService(
            staleFrameThresholdMs = STALE_FRAME_THRESHOLD_MS,
            selectWindowMs = ONLINE_SELECT_WINDOW_MS,
            selectMaxFrames = ONLINE_SELECT_MAX_FRAMES,
            selectPollIntervalMs = ONLINE_SELECT_POLL_INTERVAL_MS,
            jpegQuality = ONLINE_JPEG_QUALITY,
            logger = ::logAudioPressureSnapshot,
        )
    }
    private val autoHazardPresentationCoordinator = AutoHazardPresentationCoordinator(
        delayMs = AUTO_HAZARD_PRESENT_DELAY_MS,
    )
    private val inferenceRunning = AtomicBoolean(false)
    private var wearSnapshot: GlassesWearStateMachine.Snapshot? = null

    private val inputSession by lazy {
        UnifiedInputSession(this, TAG)
    }
    private val motionStabilityTracker by lazy { HeadMotionStabilityTracker(this) }
    private val aiArSseService by lazy { AiArSseService() }
    private val onlineHazardDetectionService by lazy {
        createOnlineHazardDetectionService(OnlineHazardDetectionService.DetectionLane.ITEM)
    }
    private val sceneOnlineHazardDetectionService by lazy {
        createOnlineHazardDetectionService(OnlineHazardDetectionService.DetectionLane.SCENE)
    }

    private var hiddenRiskNcnn: HiddenRiskNcnn? = null
    private var destroyed = false
    private var isActivityResumed = false
    private var isWorkflowActive = false
    private var mediaPermissionRequested = false
    private var modelLoading = false
    private var modelLoaded = false
    private var captureInProgress = false
    private var captureDelayScheduled = false
    private var autoInferenceStartRequested = false
    private var frameStreamInitializing = false
    private var frameStreamReady = false
    private var frameStreamReadyAtElapsedMs = 0L
    private var previewRecreateAttempted = false
    private var cameraSessionGeneration = 0L
    private var cameraRequestToken: Long = -1L
    private var sdkReadyAtElapsedMs = 0L
    private var pendingDetectionStart = false
    private var pageState = PageState.DETECTING
    private var streamingInProgress = false
    private var streamCallbackActive = false
    private var pendingStreamStart = false
    private var manualDeepAnalysisInProgress = false
    private var activeStreamRequestId = 0L
    private val localHazardPushService by lazy { LocalHazardPushService() }
    private var localHazardUploadHandle: RetryRequestHandle? = null
    private var lastAnalysisText = ""
    private var hazardCaptureService: HazardCaptureService? = null
    private var activeHazardContent: ResolvedHazardContent? = null
    private var localResultStage = LocalResultStage.NONE
    private var localSaveSubmitting = false
    private var localSaveRequestPending = false
    private var suggestionChecksRequestPending = false
    private var returnToDetectingWhenSubmitIdle = false
    private var ttsState = TtsState.IDLE
    private var streamAutoScrollLocked = false
    private var streamPanelAnchoredBelowPreview = false
    private var simulatedStreamRunnable: Runnable? = null
    private var simulatedStreamRequestId = 0L
    private var adviceCardAnimating = false
    private var pendingUploadSuccessToast = false
    private var pendingAutoHazardPresentation: PendingAutoHazardPresentation? = null
    private val localLabelCooldownUntilMs = linkedMapOf<String, Long>()
    private val localHazardInfoByItem: Map<String, List<LocalHazardInfo>> by lazy {
        buildLocalHazardInfoByItem(loadLocalHazardInfos())
    }

    private var isMotionStable = false
    private var stableQualifiedAtMillis: Long? = null
    private var autoInferenceEpoch = 0L
    private var localLoopRunning = false
    private var localLoopEpoch = 0L
    private var localLastFrameTimestamp = 0L
    private var localRetryPosted = false
    private val itemOnlineLaneRuntime = OnlineDetectionLaneRuntime()
    private val sceneOnlineLaneRuntime = OnlineDetectionLaneRuntime()
    private var onlineRequestIdSequence = 0L
    private var latestSharedInferenceFrame: SquareFramePayload? = null
    private var lastMotionUnstableElapsedMs: Long? = null
    private var autoPipelineMode = AutoHazardPipelineDecider.PipelineMode.REMOTE_PRIMARY
    private var remoteFailureCount = 0

    // 手动分析流相关
    private var currentManualAnalysisHandle: AiArSseService.RequestHandle? = null
    private var currentSuggestionChecksHandle: AiArSseService.RequestHandle? = null
    private var debugSnapshotState: String? = null

    private val cameraRecoveryController by lazy {
        RokidCameraRecoveryController(
            mode = RokidCameraRecoveryController.RecoveryMode.CONSUMER_TIMEOUT,
            callback = object : RokidCameraRecoveryController.Callback {
                override fun onRecoveryStarted(
                    issue: RokidCameraRecoveryController.RecoveryIssue,
                    attempt: Int,
                    maxAttempts: Int,
                ) {
                    Log.w(TAG, "camera recovery start issue=$issue attempt=$attempt/$maxAttempts")
                    frameStreamInitializing = false
                    frameStreamReady = false
                    frameStreamReadyAtElapsedMs = 0L
                    captureInProgress = false
                    stopAutoInferencePipelines("camera_recovery", clearPendingStreamState = false)
                }

                override fun onRecoverySucceeded() {
                    Log.i(TAG, "camera recovery success")
                    frameStreamReady = true
                    frameStreamReadyAtElapsedMs = SystemClock.elapsedRealtime()
                    if (!destroyed && isActivityResumed && isWorkflowActive && pageState == PageState.DETECTING) {
                        startAutoInferencePipelinesIfNeeded(reason = "camera_recovery", preferImmediate = true)
                    }
                }

                override fun onRecoveryAbandoned(issue: RokidCameraRecoveryController.RecoveryIssue) {
                    Log.e(TAG, "camera recovery abandoned issue=$issue")
                    failWorkflow("相机帧流连续超时，请检查设备")
                }
            },
            restartHandler = { issue, onReady ->
                cameraSessionGeneration = InspectionCameraCoordinator.restart(
                    owner = CameraOwner.AI_INSPECTION,
                    reason = issue.name,
                    needPreview = shouldKeepDetectionPreviewRunning(),
                    previewView = viewLivePreview,
                    onReady = onReady,
                )
            },
        )
    }

    private val statusBarUpdater by lazy { GlassStatusBarUpdater(this) }
    private val motionStabilityListener = object : HeadMotionStabilityTracker.Listener {
        override fun onStabilityChanged(isStable: Boolean, stableSinceMillis: Long?) {
            isMotionStable = isStable
            stableQualifiedAtMillis = stableSinceMillis
            if (!isStable) {
                lastMotionUnstableElapsedMs = SystemClock.elapsedRealtime()
                pruneSharedFrameForMotionIfNeeded()
            }
            if (isStable) {
                Log.i(TAG, "motion stable qualified stableSinceMillis=$stableSinceMillis")
                startAutoInferencePipelinesIfNeeded(reason = "motion_stable", preferImmediate = true)
            }
            refreshDetectionStatus()
        }
    }

    // 本次拍照上传的会话 ID，用于与 save 接口保持一致的指纹
    private var sessionId = ""

    private val captureDelayRunnable = Runnable {
        captureDelayScheduled = false
        if (destroyed || !autoInferenceStartRequested) return@Runnable
        startAutoInferencePipelinesIfNeeded(reason = "warmup_elapsed", preferImmediate = true)
    }
    private val wearRecoveryReadyRunnable = object : Runnable {
        override fun run() {
            if (!isWearRecovering()) return
            if (maybeCompleteWearRecovery()) return
            uiHandler.postDelayed(this, AUTO_INFERENCE_RETRY_DELAY_MS)
        }
    }

    private val pendingAutoHazardPresentationRunnable = Runnable {
        tryPresentPendingAutoHazard()
    }

    private val localLoopRunnable = Runnable {
        localRetryPosted = false
        runLocalInferenceLoop()
    }

    private val onlineLoopRunnable = Runnable {
        itemOnlineLaneRuntime.loopPosted = false
        advanceOnlineInferenceLoop(
            lane = OnlineHazardDetectionService.DetectionLane.ITEM,
            reason = "scheduled",
        )
    }

    private val sceneOnlineLoopRunnable = Runnable {
        sceneOnlineLaneRuntime.loopPosted = false
        advanceOnlineInferenceLoop(
            lane = OnlineHazardDetectionService.DetectionLane.SCENE,
            reason = "scheduled",
        )
    }

    private val localNetworkProbeRunnable = object : Runnable {
        override fun run() {
            localNetworkProbePosted = false
            probeNetworkForRemoteRecovery()
        }
    }

    internal data class OnlineLabelCooldownDecision(
        val shouldSuppress: Boolean,
        val activeLabels: List<String>,
    )
    private var localNetworkProbePosted = false

    private fun createOnlineHazardDetectionService(
        lane: OnlineHazardDetectionService.DetectionLane,
    ): OnlineHazardDetectionService {
        return OnlineHazardDetectionService(
            callback = object : OnlineHazardDetectionService.Callback {
                override fun onDetectionResult(
                    request: OnlineHazardDetectionService.DetectionRequest,
                    hasHazard: Boolean,
                    rawText: String,
                    labels: List<String>,
                ) {
                    handleOnlineDetectionResult(request, hasHazard, rawText, labels)
                }

                override fun onDetectionFailure(
                    request: OnlineHazardDetectionService.DetectionRequest,
                    message: String,
                ) {
                    handleOnlineDetectionFailure(request, message)
                }

                override fun onDetectionDropped(
                    request: OnlineHazardDetectionService.DetectionRequest,
                    reason: String,
                ) {
                    handleOnlineDetectionDropped(request, reason)
                }

                override fun onDeepAnalysisChunk(
                    request: OnlineHazardDetectionService.DetailRequest,
                    accumulatedText: String,
                ) {
                    handleOnlineDetailChunk(request, accumulatedText)
                }

                override fun onDeepAnalysisSuccess(
                    request: OnlineHazardDetectionService.DetailRequest,
                    fullText: String,
                ) {
                    handleOnlineDetailSuccess(request, fullText)
                }

                override fun onDeepAnalysisFailure(
                    request: OnlineHazardDetectionService.DetailRequest,
                    message: String,
                ) {
                    handleOnlineDetailFailure(request, message)
                }
            },
            detectTimeoutMs = when (lane) {
                OnlineHazardDetectionService.DetectionLane.ITEM ->
                    InspectionConfigRepository.get().network.aiAutoApi.detectTimeoutMs
                OnlineHazardDetectionService.DetectionLane.SCENE ->
                    InspectionConfigRepository.get().network.aiGeneralApi.detectTimeoutMs
            },
        )
    }

    private fun onlineLaneRuntime(
        lane: OnlineHazardDetectionService.DetectionLane,
    ): OnlineDetectionLaneRuntime {
        return when (lane) {
            OnlineHazardDetectionService.DetectionLane.ITEM -> itemOnlineLaneRuntime
            OnlineHazardDetectionService.DetectionLane.SCENE -> sceneOnlineLaneRuntime
        }
    }

    private fun onlineLaneService(
        lane: OnlineHazardDetectionService.DetectionLane,
    ): OnlineHazardDetectionService {
        return when (lane) {
            OnlineHazardDetectionService.DetectionLane.ITEM -> onlineHazardDetectionService
            OnlineHazardDetectionService.DetectionLane.SCENE -> sceneOnlineHazardDetectionService
        }
    }

    private fun onlineLaneRunnable(
        lane: OnlineHazardDetectionService.DetectionLane,
    ): Runnable {
        return when (lane) {
            OnlineHazardDetectionService.DetectionLane.ITEM -> onlineLoopRunnable
            OnlineHazardDetectionService.DetectionLane.SCENE -> sceneOnlineLoopRunnable
        }
    }

    private fun onlineDetectIntervalMs(
        lane: OnlineHazardDetectionService.DetectionLane,
    ): Long {
        return when (lane) {
            OnlineHazardDetectionService.DetectionLane.ITEM -> onlineDetectIntervalMs
            OnlineHazardDetectionService.DetectionLane.SCENE -> onlineSceneDetectIntervalMs
        }
    }

    private fun nextOnlineRequestId(): Long {
        onlineRequestIdSequence += 1L
        return onlineRequestIdSequence
    }

    private fun areAllOnlineLanesRunning(): Boolean {
        return itemOnlineLaneRuntime.loopRunning &&
            (!enableOnlineSceneHazardDetection || sceneOnlineLaneRuntime.loopRunning)
    }

    private fun resetOnlineLaneRuntime(runtime: OnlineDetectionLaneRuntime) {
        runtime.loopRunning = false
        runtime.loopEpoch = 0L
        runtime.lastFrameTimestamp = 0L
        runtime.queuedNext = false
        runtime.loopPosted = false
        runtime.frameSelectionInProgress = false
        runtime.nextEarliestStartElapsedMs = 0L
        runtime.activeRequestIds.clear()
    }

    private val hideUploadSuccessToastRunnable = Runnable {
        tvUploadSuccessToast.animate()
            .alpha(0f)
            .setDuration(UPLOAD_SUCCESS_TOAST_FADE_MS)
            .withEndAction {
                tvUploadSuccessToast.visibility = View.GONE
                tvUploadSuccessToast.alpha = 1f
            }
            .start()
    }

    // ==================== 生命周期 ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_inspection)

        layoutLoading = findViewById(R.id.layoutLoading)
        layoutDetection = findViewById(R.id.layoutDetection)
        layoutLivePreviewCard = findViewById(R.id.layoutLivePreviewCard)
        viewLivePreview = findViewById(R.id.viewLivePreview)
        statusAlertOverlay = findViewById(R.id.statusAlertOverlay)
        tvDetectingBottomHint = findViewById(R.id.tvDetectingBottomHint)
        layoutStreamResponse = findViewById(R.id.layoutStreamResponse)
        layoutStreamContentContainer = findViewById(R.id.layoutStreamContentContainer)
        layoutStreamThumbnailCard = findViewById(R.id.layoutStreamThumbnailCard)
        layoutStreamThumbnailPlaceholder = findViewById(R.id.layoutStreamThumbnailPlaceholder)
        streamTopSpacer = findViewById(R.id.streamTopSpacer)
        streamBottomSpacer = findViewById(R.id.streamBottomSpacer)
        tvStreamContent = findViewById(R.id.tvStreamContent)
        scrollContent = findViewById(R.id.scrollContent)
        ivStreamThumbnail = findViewById(R.id.ivStreamThumbnail)
        tvStreamBottomHint = findViewById(R.id.tvStreamBottomHint)
        tvUploadSuccessToast = findViewById(R.id.tvUploadSuccessToast)
        operationGuideDetecting = findViewById(R.id.operationGuideDetecting)
        operationGuideStream = findViewById(R.id.operationGuideStream)
        // 流式结果卡片高度限制在 onMessage / applyDebugSnapshotState 中动态处理
        // 检测状态 UI 初始化
        statusBarDetecting = findViewById(R.id.statusBarDetecting)
        statusBarStream = findViewById(R.id.statusBarStream)

        setupFunctionMenus()
        hideActionPrompts()

        updateConfirmationHints()
        if (ENABLE_HEAD_MOTION_STABILITY_GATE) {
            motionStabilityTracker.addListener(motionStabilityListener)
        }

        debugSnapshotState = intent.getStringExtra("debug_state")
        if (debugSnapshotState != null) {
            applyDebugSnapshotState(debugSnapshotState!!)
            return
        }

        // 从 InspectionSession 获取已初始化的相机帧流；NCNN 模型只在本地备用链路按需加载。
        hiddenRiskNcnn = InspectionSession.hiddenRiskNcnn
        frameStreamReady = InspectionCameraCoordinator.isFrameStreamReady()
        if (frameStreamReady) {
            frameStreamReadyAtElapsedMs = SystemClock.elapsedRealtime()
        }

        // 注册 SDK 监听（用于语音命令）
        RokidSdkManager.addListener(this)

        // 检查初始化状态；正常情况 AiInspectionMenu 已后台预加载完成
        if (!InspectionSession.isInitialized) {
            Log.w(TAG, "InspectionSession 未初始化，内联等待加载（兜底路径）")
            doInlineSessionInit()
            return
        }

        onSessionReady()
    }

    /**
     * Session 就绪后的初始化逻辑（从 onCreate 末尾抽取）。
     */
    private fun onSessionReady() {
        showPage(PageState.DETECTING)
        applyDefaultDetectionStatus()
        statusBarUpdater.refreshNow(statusBarDetecting, statusBarStream)
        OfflineTtsPlayer.play(
            context = this,
            ownerTag = TAG,
            audioResId = R.raw.start_hazard_analysis,
        )
        modelLoaded = InspectionSession.isModelLoaded
        pendingDetectionStart = true
        Log.i(
            TAG,
            "defer detection start until active lifecycle resumed=$isActivityResumed active=$isWorkflowActive frameReady=$frameStreamReady frameOpen=${RokidFrameSource.isFrameStreamOpen()} modelLoaded=$modelLoaded",
        )
    }

    /**
     * 内联初始化 InspectionSession（兜底路径，正常情况下不应走到这里）。
     * 显示内置 layoutLoading，在 nativeExecutor 加载 NCNN 模型，完成后回调 onSessionReady。
     */
    private fun doInlineSessionInit() {
        layoutLoading.visibility = View.VISIBLE
        layoutDetection.visibility = View.GONE
        layoutStreamResponse.visibility = View.GONE

        nativeExecutor.execute {
            try {
                val needsModel = InspectionConfigRepository.get()
                    .aiInspection
                    .autoHazardRoutingMode == ConfigAutoHazardRoutingMode.LOCAL_ONLY
                if (needsModel) {
                    if (!InspectionSession.createNcnnInstance() || !InspectionSession.loadModel(assets)) {
                        runOnUiThread {
                            layoutLoading.visibility = View.GONE
                            statusAlertOverlay.render(StatusAlertModel(
                                status = AlertStatus.ERROR,
                                titleText = getString(R.string.ai_inspection_load_error_title),
                                messageText = InspectionSession.errorMessage
                                    ?: getString(R.string.ai_inspection_loading_error_default),
                                behavior = AlertBehavior(autoDismissMs = null, showCountdownBar = false),
                                style = AlertStyle(iconResId = R.drawable.hidden_risk_alert),
                            ))
                        }
                        return@execute
                    }
                }
                InspectionSession.markInitialized()
                runOnUiThread { onSessionReady() }
            } catch (e: Exception) {
                Log.e(TAG, "doInlineSessionInit: unexpected error", e)
                runOnUiThread {
                    layoutLoading.visibility = View.GONE
                    statusAlertOverlay.render(StatusAlertModel(
                        status = AlertStatus.ERROR,
                        titleText = getString(R.string.ai_inspection_load_error_title),
                        messageText = getString(R.string.ai_inspection_loading_error_default),
                        behavior = AlertBehavior(autoDismissMs = null, showCountdownBar = false),
                        style = AlertStyle(iconResId = R.drawable.hidden_risk_alert),
                    ))
                }
            }
        }
    }

    /**
     * 立即开始检测（对象已预初始化）
     */
    private fun startDetectionImmediately() {
        autoInferenceStartRequested = false
        cameraRecoveryController.resetRecoveryAttempts()
        initFrameStreamAndTransition()
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        Log.i(
            TAG,
            "onResume pageState=$pageState active=$isWorkflowActive pendingDetectionStart=$pendingDetectionStart frameReady=$frameStreamReady frameOpen=${RokidFrameSource.isFrameStreamOpen()}",
        )
        inputSession.attach()
        statusBarUpdater.start(statusBarDetecting, statusBarStream)
        if (debugSnapshotState != null) {
            refreshInputActions()
            return
        }
        if (ENABLE_HEAD_MOTION_STABILITY_GATE) {
            motionStabilityTracker.start()
        } else {
            markHeadMotionStabilityGateSatisfied()
        }
        updateWearMonitoringEnabled()
        cameraRecoveryController.start()
        refreshInputActions()
        if (pageState == PageState.DETECTING || shouldKeepDetectionPreviewRunning(pageState)) {
            cameraRecoveryController.setRecoveryEnabled(true)
            if (pendingDetectionStart) {
                startDetectionImmediately()
                pendingDetectionStart = false
            } else {
                initFrameStreamAndTransition()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isWorkflowActive = true
        Log.i(
            TAG,
            "onStart pageState=$pageState resumed=$isActivityResumed pendingDetectionStart=$pendingDetectionStart frameReady=$frameStreamReady frameOpen=${RokidFrameSource.isFrameStreamOpen()}",
        )
        refreshInputActions()
    }

    override fun onPause() {
        isActivityResumed = false
        statusBarUpdater.stop()
        inputSession.detach()
        if (debugSnapshotState != null) {
            super.onPause()
            return
        }
        if (ENABLE_HEAD_MOTION_STABILITY_GATE) {
            motionStabilityTracker.stop()
        }
        uiHandler.removeCallbacks(wearRecoveryReadyRunnable)
        cameraRecoveryController.setRecoveryEnabled(false)
        cameraRecoveryController.notifyConsumerWaitStopped()
        stopDetectionPreview()
        cancelSimulatedStreamRendering()
        stopAutoInferencePipelines("onPause")
        manualDeepAnalysisInProgress = false
        hideStatusAlertOverlay()
        // 关闭当前 SSE 连接
        currentManualAnalysisHandle?.cancel()
        currentManualAnalysisHandle = null
        frameStreamInitializing = false
        frameStreamReady = false
        frameStreamReadyAtElapsedMs = 0L
        cameraSessionGeneration = 0L
        cameraRecoveryController.stop()
        InspectionCameraCoordinator.pauseTemporarily(CameraOwner.AI_INSPECTION, reason = "ai_on_pause")
        super.onPause()
    }

    override fun onStop() {
        isWorkflowActive = false
        if (debugSnapshotState != null) {
            super.onStop()
            return
        }
        cancelSimulatedStreamRendering()
        stopAutoInferencePipelines("onStop")
        manualDeepAnalysisInProgress = false
        hideStatusAlertOverlay()
        // 关闭当前 SSE 连接
        currentManualAnalysisHandle?.cancel()
        currentManualAnalysisHandle = null
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        streamCallbackActive = false
        manualDeepAnalysisInProgress = false
        localHazardUploadHandle?.cancel()
        localHazardUploadHandle = null
        currentSuggestionChecksHandle?.cancel()
        currentSuggestionChecksHandle = null
        uiHandler.removeCallbacks(hideUploadSuccessToastRunnable)
        cancelSimulatedStreamRendering()
        OfflineTtsPlayer.release(TAG)
        inputSession.release()
        if (debugSnapshotState != null) {
            statusBarUpdater.stop()
            super.onDestroy()
            return
        }
        if (ENABLE_HEAD_MOTION_STABILITY_GATE) {
            motionStabilityTracker.removeListener(motionStabilityListener)
            motionStabilityTracker.stop()
        }
        stopAutoInferencePipelines("onDestroy")
        hideStatusAlertOverlay()
        stopDetectionPreview()
        frameStreamInitializing = false
        frameStreamReady = false
        frameStreamReadyAtElapsedMs = 0L
        cameraSessionGeneration = 0L
        cameraRecoveryController.setRecoveryEnabled(false)
        cameraRecoveryController.notifyConsumerWaitStopped()
        cameraRecoveryController.stop()
        InspectionCameraCoordinator.releaseForNavigation(CameraOwner.AI_INSPECTION, reason = "ai_on_destroy")
        RokidSdkManager.removeListener(this)
        // 注意：不单独释放 RokidFrameSource 和 hiddenRiskNcnn，由 InspectionSession 管理生命周期
        nativeExecutor.shutdown()
        imageEncodeExecutor.shutdown()
        runCatching { nativeExecutor.awaitTermination(2, TimeUnit.SECONDS) }
        runCatching { imageEncodeExecutor.awaitTermination(2, TimeUnit.SECONDS) }
        hazardCaptureService?.shutdown()
        onlineHazardDetectionService.shutdown()
        // 关闭当前 SSE 连接
        currentManualAnalysisHandle?.cancel()
        currentManualAnalysisHandle = null
        clearStreamThumbnailState()
        statusBarUpdater.stop()
        // 释放 OkHttp 空闲连接，避免服务器端残留 ESTABLISHED 连接
        aiArSseService.releaseConnections()
        super.onDestroy()
    }

    // ==================== 输入事件 ====================

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        if (isWearInteractionBlocked()) {
            return true
        }
        return inputSession.dispatchTouch(keyEvent) || super.onGlassKeyEvent(keyEvent)
    }

    // ==================== SDK 回调 ====================

    override fun onSdkStateChanged(state: RokidSdkManager.SdkState) {
        Log.i(TAG, "sdk state=$state error=${RokidSdkManager.lastErrorMessage ?: "N/A"}")
        sdkReadyAtElapsedMs = if (state == RokidSdkManager.SdkState.READY) {
            sdkReadyAtElapsedMs.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
        } else {
            0L
        }
        uiHandler.post {
            refreshInputActions()
            maybeAdvanceWorkflow()
        }
    }

    // ==================== 权限 ====================

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MEDIA_PERMISSION) return
        val granted =
            grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            maybeAdvanceWorkflow()
        } else {
            failWorkflow("缺少相机或媒体读取权限")
        }
    }

    private fun ensureMediaPermissionOrStart() {
        if (!hasRequiredPermissions()) {
            if (!mediaPermissionRequested) {
                mediaPermissionRequested = true
                ActivityCompat.requestPermissions(
                    this,
                    requiredPermissions(),
                    REQUEST_MEDIA_PERMISSION
                )
            }
            return
        }
        maybeAdvanceWorkflow()
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    // ==================== 工作流 ====================

    private fun maybeAdvanceWorkflow() {
        if (destroyed || !isActivityResumed || !isWorkflowActive) return
        if (!hasRequiredPermissions()) return

        when (RokidSdkManager.state) {
            RokidSdkManager.SdkState.FAILED -> {
                failWorkflow(RokidSdkManager.lastErrorMessage ?: "Rokid SDK 初始化失败")
                return
            }

            RokidSdkManager.SdkState.READY -> Unit
            else -> return
        }

        if (autoInferenceStartRequested) {
            startAutoInferencePipelinesIfNeeded(reason = "workflow_ready", preferImmediate = true)
            return
        }

        if (!captureInProgress && !inferenceRunning.get() && pageState == PageState.DETECTING) {
            initFrameStreamAndTransition()
        }
    }

    private fun startLocalFallbackModelLoadIfNeeded(reason: String) {
        if (modelLoaded) {
            autoPipelineMode = AutoHazardPipelineDecider.PipelineMode.LOCAL_FALLBACK
            startAutoInferencePipelinesIfNeeded(reason = "$reason:model_ready", preferImmediate = true)
            return
        }
        if (modelLoading) return

        val local = ensureNativeEngine() ?: run {
            Log.e(TAG, "HiddenRiskNcnn unavailable for local fallback reason=$reason")
            return
        }

        modelLoading = true
        autoPipelineMode = AutoHazardPipelineDecider.PipelineMode.LOCAL_FALLBACK_LOADING
        stopOnlinePipelineForFallback(reason)

        if (!submitNativeTask {
                local.setDebugCompareEnabled(false)
                val success = runCatching {
                    local.loadModel(
                        assets,
                        BACKEND_GPU,
                        GPU_PROFILE_BALANCED_FP16,
                        DEFAULT_TARGET_INPUT_SIZE
                    )
                }.onFailure { e -> Log.e(TAG, "loadModel failed", e) }
                    .getOrDefault(false)

                uiHandler.post {
                    modelLoading = false
                    if (destroyed) return@post
                    val decision = AutoHazardPipelineDecider.decideAfterLocalModelLoaded(success)
                    autoPipelineMode = decision.mode
                    if (!success) {
                        Log.e(TAG, "local fallback model load failed reason=$reason")
                        scheduleLocalNetworkProbe()
                        return@post
                    }
                    modelLoaded = true
                    startAutoInferencePipelinesIfNeeded(reason = "$reason:model_loaded", preferImmediate = true)
                    scheduleLocalNetworkProbe()
                }
            }) {
            modelLoading = false
            Log.e(TAG, "local fallback model task submit failed reason=$reason")
            scheduleLocalNetworkProbe()
        }
    }

    private fun initFrameStreamAndTransition() {
        Log.i(
            TAG,
            "initFrameStreamAndTransition pageState=$pageState resumed=$isActivityResumed active=$isWorkflowActive initializing=$frameStreamInitializing frameReady=$frameStreamReady frameOpen=${RokidFrameSource.isFrameStreamOpen()} warm=${RokidFrameSource.isCroppedFrameStreamWarm()}",
        )
        Log.i(
            TAG,
            "diagnostic initFrameStreamAndTransition caller=${Throwable().stackTrace.getOrNull(1)?.methodName ?: "unknown"} frameStreamInitializing=$frameStreamInitializing frameStreamReady=$frameStreamReady autoInferenceStartRequested=$autoInferenceStartRequested localLoopRunning=$localLoopRunning localRetryPosted=$localRetryPosted inferenceRunning=${inferenceRunning.get()} pageState=$pageState cameraSessionGeneration=$cameraSessionGeneration",
        )
        if (!isActivityResumed || !isWorkflowActive) {
            Log.i(
                TAG,
                "defer initFrameStreamAndTransition resumed=$isActivityResumed active=$isWorkflowActive pageState=$pageState",
            )
            return
        }
        if (wearSnapshot?.state == GlassesWearStateMachine.State.SLEEP) {
            Log.i(TAG, "skip frame stream while glasses are removed")
            return
        }
        if (frameStreamInitializing) return

        frameStreamInitializing = true
        val needPreview = shouldKeepDetectionPreviewRunning(pageState)
        cameraRequestToken = InspectionCameraCoordinator.acquireForActivity(
            owner = CameraOwner.AI_INSPECTION,
            needPreview = needPreview,
            previewView = viewLivePreview,
            enableRecovery = pageState == PageState.DETECTING,
        ) { success ->
            frameStreamInitializing = false
            cameraSessionGeneration = InspectionCameraCoordinator.getGeneration()
            frameStreamReady = success
            frameStreamReadyAtElapsedMs = if (success) SystemClock.elapsedRealtime() else 0L
            if (destroyed) {
                InspectionCameraCoordinator.releaseForNavigation(CameraOwner.AI_INSPECTION, reason = "ai_destroyed_after_ready")
                return@acquireForActivity
            }
            if (!isActivityResumed || !isWorkflowActive) {
                frameStreamReady = false
                frameStreamReadyAtElapsedMs = 0L
                InspectionCameraCoordinator.pauseTemporarily(CameraOwner.AI_INSPECTION, reason = "ai_inactive_after_ready")
                Log.i(
                    TAG,
                    "release frame stream after late callback resumed=$isActivityResumed active=$isWorkflowActive pageState=$pageState",
                )
                return@acquireForActivity
            }
            if (!success) {
                failWorkflow("相机帧流初始化失败")
                return@acquireForActivity
            }
            Log.i(
                TAG,
                "frame stream ready generation=$cameraSessionGeneration autoStartRequested=$autoInferenceStartRequested pageState=$pageState state=${InspectionCameraCoordinator.getState()}",
            )
            Log.i(
                TAG,
                "diagnostic frame_stream_ready generation=$cameraSessionGeneration localLoopRunning=$localLoopRunning localRetryPosted=$localRetryPosted inferenceRunning=${inferenceRunning.get()} autoInferenceStartRequested=$autoInferenceStartRequested frameStreamReady=$frameStreamReady cameraSessionGeneration=$cameraSessionGeneration",
            )
            if (!viewLivePreview.isPreviewStarted() && !previewRecreateAttempted) {
                recreateDetectionPreviewView(reason = "frame_stream_ready_preview_not_started")
                return@acquireForActivity
            }
            if (pageState == PageState.DETECTING || pageState == PageState.STREAM_RESPONSE) {
                startDetectionPreviewIfNeeded()
            }
            if (autoInferenceStartRequested) {
                startAutoInferencePipelinesIfNeeded(reason = "frame_stream_ready", preferImmediate = true)
            } else if (pageState == PageState.DETECTING) {
                startAutoInferencePipelinesIfNeeded(reason = "frame_stream_ready", preferImmediate = true)
            }
            transitionToDetection()
            refreshInputActions()
        }
    }

    private fun transitionToDetection() {
        Log.i(
            TAG,
            "transitionToDetection pageState=$pageState resumed=$isActivityResumed active=$isWorkflowActive frameReady=$frameStreamReady frameOpen=${RokidFrameSource.isFrameStreamOpen()} previewStarted=${viewLivePreview.isPreviewStarted()}",
        )
        Log.i(
            TAG,
            "diagnostic transitionToDetection localLoopRunning=$localLoopRunning localRetryPosted=$localRetryPosted inferenceRunning=${inferenceRunning.get()} autoInferenceStartRequested=$autoInferenceStartRequested modelLoaded=$modelLoaded hiddenRiskNcnnPresent=${hiddenRiskNcnn != null} cameraSessionGeneration=$cameraSessionGeneration",
        )
        autoInferenceStartRequested = false
        startAutoInferencePipelinesIfNeeded(reason = "transition_to_detection", preferImmediate = true)
        refreshDetectionStatus()
    }

    private fun returnToDetecting() {
        localHazardUploadHandle?.cancel()
        localHazardUploadHandle = null
        currentSuggestionChecksHandle?.cancel()
        currentSuggestionChecksHandle = null
        localSaveRequestPending = false
        suggestionChecksRequestPending = false
        returnToDetectingWhenSubmitIdle = false
        localSaveSubmitting = false
        uiHandler.removeCallbacks(hideUploadSuccessToastRunnable)
        currentManualAnalysisHandle?.cancel()
        currentManualAnalysisHandle = null
        streamCallbackActive = false
        streamingInProgress = false
        pendingStreamStart = false
        manualDeepAnalysisInProgress = false
        cancelSimulatedStreamRendering()
        clearPendingAutoHazardPresentation()
        stopAutoInferencePipelines("return_to_detecting")
        clearLocalHazardResultState(clearPendingUploadToast = false)
        activeStreamRequestId++
        hideStatusAlertOverlay()
        cameraRecoveryController.resetRecoveryAttempts()
        showPage(PageState.DETECTING)
        showPendingUploadSuccessToastIfNeeded()
        applyDefaultDetectionStatus()
        initFrameStreamAndTransition()
    }

    private fun returnToDetectingPreservingLocalUpload(stopReason: String) {
        currentSuggestionChecksHandle?.cancel()
        currentSuggestionChecksHandle = null
        suggestionChecksRequestPending = false
        returnToDetectingWhenSubmitIdle = false
        localSaveSubmitting = false
        uiHandler.removeCallbacks(hideUploadSuccessToastRunnable)
        currentManualAnalysisHandle?.cancel()
        currentManualAnalysisHandle = null
        streamCallbackActive = false
        streamingInProgress = false
        pendingStreamStart = false
        manualDeepAnalysisInProgress = false
        cancelSimulatedStreamRendering()
        clearPendingAutoHazardPresentation()
        stopAutoInferencePipelines(stopReason)
        streamPanelAnchoredBelowPreview = false
        activeHazardContent = null
        localResultStage = LocalResultStage.NONE
        ttsState = TtsState.IDLE
        activeStreamRequestId++
        hideStatusAlertOverlay()
        cameraRecoveryController.resetRecoveryAttempts()
        showPage(PageState.DETECTING)
        showPendingUploadSuccessToastIfNeeded()
        applyDefaultDetectionStatus()
        refreshInputActions()
        initFrameStreamAndTransition()
    }

    private fun returnToDetectingAfterEmptySuggestionChecks() {
        returnToDetectingPreservingLocalUpload(stopReason = "empty_sug_checks")
    }

    private fun returnToDetectingFromAdvice() {
        if (localSaveRequestPending) {
            AppFileLogger.i(TAG, "advice confirm returns to detecting while local upload keeps running")
            returnToDetectingPreservingLocalUpload(stopReason = "return_to_detecting_keep_local_upload")
            return
        }
        returnToDetecting()
    }

    private fun returnDirectlyToHome() {
        localHazardUploadHandle?.cancel()
        localHazardUploadHandle = null
        currentSuggestionChecksHandle?.cancel()
        currentSuggestionChecksHandle = null
        localSaveRequestPending = false
        suggestionChecksRequestPending = false
        returnToDetectingWhenSubmitIdle = false
        localSaveSubmitting = false
        uiHandler.removeCallbacks(hideUploadSuccessToastRunnable)
        currentManualAnalysisHandle?.cancel()
        currentManualAnalysisHandle = null
        streamCallbackActive = false
        streamingInProgress = false
        pendingStreamStart = false
        manualDeepAnalysisInProgress = false
        clearPendingAutoHazardPresentation()
        stopAutoInferencePipelines("return_home")
        clearLocalHazardResultState()
        activeStreamRequestId++
        localLabelCooldownUntilMs.clear()
        hideStatusAlertOverlay()
        refreshInputActions()
        InspectionCameraCoordinator.releaseForNavigation(CameraOwner.AI_INSPECTION, reason = "ai_nav_menu")
        startActivity(Intent(this, AiInspectionMenuActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun finishInspectionWithReport() {
        localHazardUploadHandle?.cancel()
        localHazardUploadHandle = null
        uiHandler.removeCallbacks(hideUploadSuccessToastRunnable)
        currentManualAnalysisHandle?.cancel()
        currentManualAnalysisHandle = null
        streamCallbackActive = false
        streamingInProgress = false
        pendingStreamStart = false
        manualDeepAnalysisInProgress = false
        clearPendingAutoHazardPresentation()
        stopAutoInferencePipelines("finish_inspection")
        activeStreamRequestId++
        localLabelCooldownUntilMs.clear()
        hideStatusAlertOverlay()
        refreshInputActions()
        InspectionWorkflowSession.recordAnalysis(lastAnalysisText, sessionId)
        InspectionCameraCoordinator.releaseForNavigation(CameraOwner.AI_INSPECTION, reason = "ai_nav_end_report")
        startActivity(
            InspectionEndReportActivity.createIntent(
                this,
                InspectionEndReportReturnDestination.HAZARD_ANALYSIS_HOME,
            ),
        )
        finish()
    }

    // ==================== 拍照与推理 ====================

    private fun startAutoInferencePipelinesIfNeeded(reason: String, preferImmediate: Boolean) {
        if (isAutoHazardPresentationPending()) {
            return
        }
        autoInferenceStartRequested = true
        if (!shouldRunAutoInferencePipelines()) {
            return
        }
        if (!frameStreamReady) {
            initFrameStreamAndTransition()
            return
        }

        val readyElapsedMs = frameStreamReadyAtElapsedMs.takeIf { it > 0L } ?: sdkReadyAtElapsedMs
        val warmupRemainingMs = when {
            readyElapsedMs <= 0L -> CAPTURE_WARMUP_MS
            else -> (CAPTURE_WARMUP_MS - (SystemClock.elapsedRealtime() - readyElapsedMs)).coerceAtLeast(0L)
        }
        if (warmupRemainingMs > 0L) {
            uiHandler.removeCallbacks(captureDelayRunnable)
            captureDelayScheduled = true
            uiHandler.postDelayed(captureDelayRunnable, warmupRemainingMs)
            return
        }
        startAutoInferencePipelinesNow(reason = reason, preferImmediate = preferImmediate)
    }

    private fun startAutoInferencePipelinesNow(reason: String, preferImmediate: Boolean) {
        if (!shouldRunAutoInferencePipelines()) {
            return
        }
        autoInferenceStartRequested = false
        val initialDelayMs = if (preferImmediate) 0L else AUTO_INFERENCE_RETRY_DELAY_MS
        if (!ensureAutoPipelineModeForStart(reason)) {
            return
        }
        val startDecision = AutoInferenceLoopDecider.decidePipelineStart(
            localEnabled = autoPipelineMode == AutoHazardPipelineDecider.PipelineMode.LOCAL_FALLBACK,
            onlineEnabled = autoPipelineMode == AutoHazardPipelineDecider.PipelineMode.REMOTE_PRIMARY,
        )
        if (startDecision.startLocal && !localLoopRunning) {
            localLoopRunning = true
            localLoopEpoch = autoInferenceEpoch
            postLocalInferenceLoop(delayMs = initialDelayMs, reason = reason)
            scheduleLocalNetworkProbe()
        }
        if (startDecision.startOnline) {
            startOnlineDetectionLaneIfNeeded(
                lane = OnlineHazardDetectionService.DetectionLane.ITEM,
                delayMs = initialDelayMs,
                reason = reason,
            )
            if (enableOnlineSceneHazardDetection) {
                startOnlineDetectionLaneIfNeeded(
                    lane = OnlineHazardDetectionService.DetectionLane.SCENE,
                    delayMs = initialDelayMs,
                    reason = reason,
                )
            }
        }
    }

    private fun ensureAutoPipelineModeForStart(reason: String): Boolean {
        if (localOnlyRouteEnabled && autoPipelineMode == AutoHazardPipelineDecider.PipelineMode.REMOTE_PRIMARY) {
            startLocalFallbackModelLoadIfNeeded(reason = "$reason:local_only")
            return false
        }
        return when (autoPipelineMode) {
            AutoHazardPipelineDecider.PipelineMode.REMOTE_PRIMARY -> {
                if (SystemStateUtils.isNetworkAvailable(this)) {
                    true
                } else {
                    val decision = AutoHazardPipelineDecider.decideStart(networkAvailable = false)
                    applyAutoPipelineDecision(decision, reason = "$reason:network_unavailable")
                    false
                }
            }
            AutoHazardPipelineDecider.PipelineMode.LOCAL_FALLBACK_LOADING -> false
            AutoHazardPipelineDecider.PipelineMode.LOCAL_FALLBACK -> {
                if (modelLoaded && !modelLoading) {
                    true
                } else {
                    startLocalFallbackModelLoadIfNeeded(reason = "$reason:local_not_ready")
                    false
                }
            }
        }
    }

    private fun applyAutoPipelineDecision(
        decision: AutoHazardPipelineDecider.PipelineDecision,
        reason: String,
    ) {
        AppFileLogger.i(
            TAG,
            "auto pipeline decision reason=$reason mode=${decision.mode} startRemote=${decision.startRemote} startLocal=${decision.startLocal} loadLocal=${decision.loadLocalModel}",
        )
        if (decision.resetRemoteFailures) {
            remoteFailureCount = 0
        }
        when (decision.mode) {
            AutoHazardPipelineDecider.PipelineMode.REMOTE_PRIMARY -> switchToRemotePrimary(reason)
            AutoHazardPipelineDecider.PipelineMode.LOCAL_FALLBACK_LOADING -> {
                autoPipelineMode = decision.mode
                if (decision.loadLocalModel) {
                    startLocalFallbackModelLoadIfNeeded(reason)
                }
            }
            AutoHazardPipelineDecider.PipelineMode.LOCAL_FALLBACK -> {
                autoPipelineMode = decision.mode
                scheduleLocalNetworkProbe()
                if (decision.startLocal) {
                    startAutoInferencePipelinesIfNeeded(reason = reason, preferImmediate = true)
                }
            }
        }
    }

    private fun switchToRemotePrimary(reason: String) {
        if (autoPipelineMode == AutoHazardPipelineDecider.PipelineMode.REMOTE_PRIMARY &&
            areAllOnlineLanesRunning() &&
            !localLoopRunning
        ) {
            return
        }
        AppFileLogger.i(TAG, "switch to remote primary reason=$reason")
        uiHandler.removeCallbacks(localNetworkProbeRunnable)
        localNetworkProbePosted = false
        localLoopRunning = false
        localRetryPosted = false
        inferenceRunning.set(false)
        uiHandler.removeCallbacks(localLoopRunnable)
        autoPipelineMode = AutoHazardPipelineDecider.PipelineMode.REMOTE_PRIMARY
        remoteFailureCount = 0
        if (pageState == PageState.DETECTING) {
            startAutoInferencePipelinesIfNeeded(reason = "$reason:remote_primary", preferImmediate = true)
        }
    }

    private fun stopOnlinePipelineForFallback(reason: String) {
        AppFileLogger.i(TAG, "stop online pipeline for local fallback reason=$reason")
        resetOnlineLaneRuntime(itemOnlineLaneRuntime)
        resetOnlineLaneRuntime(sceneOnlineLaneRuntime)
        uiHandler.removeCallbacks(onlineLoopRunnable)
        uiHandler.removeCallbacks(sceneOnlineLoopRunnable)
        onlineHazardDetectionService.cancelActiveDetection()
        sceneOnlineHazardDetectionService.cancelActiveDetection()
    }

    private fun scheduleLocalNetworkProbe() {
        if (destroyed ||
            localOnlyRouteEnabled ||
            autoPipelineMode == AutoHazardPipelineDecider.PipelineMode.REMOTE_PRIMARY ||
            localNetworkProbePosted
        ) {
            return
        }
        localNetworkProbePosted = true
        uiHandler.postDelayed(
            localNetworkProbeRunnable,
            localNetworkProbeIntervalMs.coerceAtLeast(AUTO_INFERENCE_RETRY_DELAY_MS),
        )
    }

    private fun probeNetworkForRemoteRecovery() {
        if (destroyed ||
            localOnlyRouteEnabled ||
            pageState != PageState.DETECTING ||
            autoPipelineMode == AutoHazardPipelineDecider.PipelineMode.REMOTE_PRIMARY
        ) {
            return
        }
        val decision = AutoHazardPipelineDecider.decideLocalNetworkProbe(
            networkAvailable = SystemStateUtils.isNetworkAvailable(this),
        )
        if (decision.mode == AutoHazardPipelineDecider.PipelineMode.REMOTE_PRIMARY) {
            applyAutoPipelineDecision(decision, reason = "local_network_probe")
            return
        }
        scheduleLocalNetworkProbe()
    }

    private fun stopAutoInferencePipelines(
        reason: String,
        clearPendingStreamState: Boolean = true,
        cancelOnlineDetails: Boolean = true,
    ) {
        logAudioPressureSnapshot(
            stage = "stop_auto_inference_pipelines:start",
            extra = "reason=$reason clearPendingStreamState=$clearPendingStreamState cancelOnlineDetails=$cancelOnlineDetails",
        )
        AppFileLogger.i(TAG, "stop auto inference pipelines reason=$reason cancelOnlineDetails=$cancelOnlineDetails")
        autoInferenceStartRequested = false
        captureDelayScheduled = false
        localRetryPosted = false
        localLoopRunning = false
        resetOnlineLaneRuntime(itemOnlineLaneRuntime)
        resetOnlineLaneRuntime(sceneOnlineLaneRuntime)
        localNetworkProbePosted = false
        localLastFrameTimestamp = 0L
        clearLatestSharedInferenceFrame(reason = "stop_auto_inference:$reason")
        lastMotionUnstableElapsedMs = null
        autoInferenceEpoch += 1
        cancelSimulatedStreamRendering()
        clearPendingAutoHazardPresentation()
        uiHandler.removeCallbacks(captureDelayRunnable)
        uiHandler.removeCallbacks(localLoopRunnable)
        uiHandler.removeCallbacks(onlineLoopRunnable)
        uiHandler.removeCallbacks(sceneOnlineLoopRunnable)
        uiHandler.removeCallbacks(localNetworkProbeRunnable)
        InspectionCameraCoordinator.setConsumerWaiting(CameraOwner.AI_INSPECTION, waiting = false)
        cameraRecoveryController.notifyConsumerWaitStopped()
        if (cancelOnlineDetails) {
            onlineHazardDetectionService.cancelAll()
            sceneOnlineHazardDetectionService.cancelAll()
        } else {
            onlineHazardDetectionService.cancelActiveDetection()
            sceneOnlineHazardDetectionService.cancelActiveDetection()
        }
        if (clearPendingStreamState) {
            pendingStreamStart = false
        }
        logAudioPressureSnapshot(
            stage = "stop_auto_inference_pipelines:end",
            extra = "reason=$reason clearPendingStreamState=$clearPendingStreamState cancelOnlineDetails=$cancelOnlineDetails",
        )
    }

    private fun shouldRunAutoInferencePipelines(): Boolean {
        if (destroyed || !isActivityResumed || !isWorkflowActive) return false
        if (isWearInteractionBlocked()) return false
        if (isAutoHazardPresentationPending()) return false
        if (!hasRequiredPermissions()) return false
        if (RokidSdkManager.state != RokidSdkManager.SdkState.READY) return false
        if (autoPipelineMode == AutoHazardPipelineDecider.PipelineMode.LOCAL_FALLBACK_LOADING) return false
        if (autoPipelineMode == AutoHazardPipelineDecider.PipelineMode.LOCAL_FALLBACK && (!modelLoaded || modelLoading)) return false
        if (pendingStreamStart || streamingInProgress || streamCallbackActive) return false
        if (frameStreamInitializing) return false
        if (pageState != PageState.DETECTING) return false
        if (ENABLE_HEAD_MOTION_STABILITY_GATE && (!isMotionStable || stableQualifiedAtMillis == null)) return false
        return true
    }

    private fun markHeadMotionStabilityGateSatisfied() {
        isMotionStable = true
        stableQualifiedAtMillis = System.currentTimeMillis()
        lastMotionUnstableElapsedMs = null
    }

    private fun postLocalInferenceLoop(delayMs: Long, reason: String) {
        Log.d(
            TAG,
            "diagnostic postLocalInferenceLoop requested delayMs=$delayMs reason=$reason epoch=$localLoopEpoch localLoopRunning=$localLoopRunning destroyed=$destroyed localRetryPosted=$localRetryPosted inferenceRunning=${inferenceRunning.get()} thread=${Thread.currentThread().name}",
        )
        if (!localLoopRunning || destroyed || localRetryPosted) {
            return
        }
        Log.d(TAG, "post local inference loop delayMs=$delayMs reason=$reason")
        localRetryPosted = true
        uiHandler.postDelayed(localLoopRunnable, delayMs.coerceAtLeast(0L))
    }

    private fun runLocalInferenceLoop() {
        val epoch = localLoopEpoch
        Log.d(
            TAG,
            "diagnostic runLocalInferenceLoop enter epoch=$epoch localLoopRunning=$localLoopRunning localRetryPosted=$localRetryPosted inferenceRunning=${inferenceRunning.get()} modelLoaded=$modelLoaded hiddenRiskNcnnPresent=${hiddenRiskNcnn != null} pageState=$pageState thread=${Thread.currentThread().name}",
        )
        if (!isLocalLoopActive(epoch)) {
            return
        }
        val local = hiddenRiskNcnn ?: run {
            if (startPendingStreamAnalysis()) {
                return
            }
            postLocalInferenceLoop(delayMs = AUTO_INFERENCE_RETRY_DELAY_MS, reason = "native_engine_missing")
            return
        }

        InspectionCameraCoordinator.setConsumerWaiting(CameraOwner.AI_INSPECTION, waiting = true)
        cameraRecoveryController.notifyConsumerWaitStarted()
        captureInProgress = true
        val frame = copyLatestSquareFrameForLocalOrNull(localLastFrameTimestamp)
        captureInProgress = false
        if (frame == null) {
            Log.d(
                TAG,
                "diagnostic runLocalInferenceLoop frame_unavailable epoch=$epoch lastFrameTs=$localLastFrameTimestamp frameStreamReady=$frameStreamReady frameOpen=${RokidFrameSource.isFrameStreamOpen()}",
            )
            if (startPendingStreamAnalysis()) {
                return
            }
            postLocalInferenceLoop(delayMs = AUTO_INFERENCE_RETRY_DELAY_MS, reason = "local_frame_unavailable")
            return
        }
        if (!inferenceRunning.compareAndSet(false, true)) {
            Log.d(
                TAG,
                "diagnostic runLocalInferenceLoop busy epoch=$epoch frameTs=${frame.timestamp} thread=${Thread.currentThread().name}",
            )
            postLocalInferenceLoop(delayMs = AUTO_INFERENCE_RETRY_DELAY_MS, reason = "local_inference_busy")
            return
        }
        localLastFrameTimestamp = frame.timestamp
        updateLatestSharedInferenceFrame(frame)
        val localInput = BitmapUtils.resizeSquareNv21(
            nv21 = frame.nv21,
            width = frame.width,
            height = frame.height,
            targetSize = DEFAULT_TARGET_INPUT_SIZE,
        ) ?: run {
            inferenceRunning.set(false)
            if (startPendingStreamAnalysis()) {
                return
            }
            postLocalInferenceLoop(delayMs = 0L, reason = "local_resize_failed")
            return
        }

        if (!submitNativeTask {
                val nativeStartElapsedMs = SystemClock.elapsedRealtime()
                Log.i(
                    TAG,
                    "diagnostic submitNv21 before epoch=$epoch frameTs=${frame.timestamp} input=${DEFAULT_TARGET_INPUT_SIZE}x$DEFAULT_TARGET_INPUT_SIZE modelLoaded=$modelLoaded hiddenRiskNcnnPresent=${hiddenRiskNcnn != null} thread=${Thread.currentThread().name}",
                )
                val success = runCatching {
                    local.submitNv21(
                        localInput,
                        DEFAULT_TARGET_INPUT_SIZE,
                        DEFAULT_TARGET_INPUT_SIZE,
                    )
                }.onFailure { error ->
                    Log.e(TAG, "submitNv21 failed", error)
                }.getOrDefault(false)
                val snapshot = runCatching { local.getLatestInferenceStats() }.getOrNull()
                val nativeElapsedMs = SystemClock.elapsedRealtime() - nativeStartElapsedMs
                val inferenceMs = snapshot?.inferenceTimeMs ?: -1L
                val detectionCount = snapshot?.detectionCount ?: 0
                val localMatches = snapshot
                    ?.takeIf { success && detectionCount > 0 }
                    ?.let(::findLocalHazardMatches)
                    .orEmpty()
                uiHandler.post {
                    inferenceRunning.set(false)
                    Log.i(
                        TAG,
                        "diagnostic submitNv21 after epoch=$epoch frameTs=${frame.timestamp} success=$success nativeElapsedMs=$nativeElapsedMs detectionCount=$detectionCount inferenceMs=$inferenceMs thread=${Thread.currentThread().name}",
                    )
                    Log.d(
                        TAG,
                        "local inference success=$success detectionCount=$detectionCount nativeElapsedMs=$nativeElapsedMs inferenceMs=$inferenceMs",
                    )
                    if (!isLocalLoopActive(epoch)) {
                        return@post
                    }
                    handleLocalInferenceCompleted(
                        epoch = epoch,
                        frame = frame,
                        success = success,
                        snapshot = snapshot,
                        localMatches = localMatches,
                    )
                }
            }) {
            inferenceRunning.set(false)
            if (startPendingStreamAnalysis()) {
                return
            }
            postLocalInferenceLoop(delayMs = AUTO_INFERENCE_RETRY_DELAY_MS, reason = "local_submit_rejected")
        }
    }

    private fun handleLocalInferenceCompleted(
        epoch: Long,
        frame: SquareFramePayload,
        success: Boolean,
        snapshot: NativeInferenceStats?,
        localMatches: List<LocalHazardMatch>,
    ) {
        if (!isLocalLoopActive(epoch)) {
            return
        }
        val detectionCount = snapshot?.detectionCount ?: 0
        if (success) {
            InspectionWorkflowSession.updateSummary { summary ->
                summary.copy(
                    analyzedCount = summary.analyzedCount + 1,
                    noHazardCount = summary.noHazardCount + if (detectionCount == 0) 1 else 0,
                )
            }
        }

        val filteredLocalMatches = if (success && localMatches.isNotEmpty()) {
            filterLocalMatchesByCooldown(
                localMatches = localMatches,
                nowElapsedMs = SystemClock.elapsedRealtime(),
            )
        } else {
            emptyList()
        }
        if (filteredLocalMatches.isNotEmpty()) {
            stopAutoInferencePipelines("accept_local_hazard_result")
            handleAutoDetectedLocalHazardResult(
                localMatches = filteredLocalMatches,
                frame = frame,
                snapshot = snapshot,
            )
            return
        }
        if (startPendingStreamAnalysis()) {
            return
        }
        if (AutoInferenceLoopDecider.shouldContinueLocalLoop(filteredLocalMatches.isNotEmpty())) {
            postLocalInferenceLoop(delayMs = 0L, reason = "local_continue")
        }
    }

    private fun queueAutoDetectedLocalHazardPresentation(
        localMatches: List<LocalHazardMatch>,
        frame: SquareFramePayload,
        snapshot: NativeInferenceStats?,
    ) {
        logAudioPressureSnapshot(
            stage = "queue_local_hazard_presentation:enqueue",
            extra = "matchCount=${localMatches.size} detectionCount=${snapshot?.detectionCount ?: 0} frameTs=${frame.timestamp}",
        )
        try {
            imageEncodeExecutor.execute {
                logAudioPressureSnapshot(
                    stage = "queue_local_hazard_presentation:worker_start",
                    extra = "matchCount=${localMatches.size} frameTs=${frame.timestamp}",
                )
                val payload = buildCapturedFramePayload(frame)
                val jpegBytes = payload?.jpegBytes ?: ByteArray(0)
                if (ENABLE_HIT_CAPTURE_SAVE && (snapshot?.detectionCount ?: 0) > 0 && jpegBytes.isNotEmpty()) {
                    ensureHazardCaptureService().saveHazardCapture(jpegBytes, snapshot)
                }
                val resolved = buildLocalResolvedContent(
                    localMatches = localMatches,
                    jpegBytes = jpegBytes,
                ) ?: return@execute
                logAudioPressureSnapshot(
                    stage = "queue_local_hazard_presentation:worker_ready",
                    extra = "jpegBytes=${jpegBytes.size} resolvedTitle=${resolved.displayTitle}",
                )
                uiHandler.post {
                    if (destroyed || pageState != PageState.DETECTING || pendingAutoHazardPresentation != null) {
                        return@post
                    }
                    postPendingLocalHazardPresentation(resolved)
                }
            }
        } catch (error: RejectedExecutionException) {
            Log.w(TAG, "local hazard encode rejected", error)
            logAudioPressureSnapshot(
                stage = "queue_local_hazard_presentation:rejected",
                extra = "matchCount=${localMatches.size} message=${error.message}",
            )
            val resolved = buildLocalResolvedContent(
                localMatches = localMatches,
                jpegBytes = ByteArray(0),
            ) ?: return
            postPendingLocalHazardPresentation(resolved)
        }
    }

    private fun postPendingLocalHazardPresentation(resolved: ResolvedHazardContent) {
        val detectedAtElapsedMs = SystemClock.elapsedRealtime()
        pendingAutoHazardPresentation = buildPendingLocalHazardPresentation(
            detectedAtElapsedMs = detectedAtElapsedMs,
            resolved = resolved,
        )
        logAudioPressureSnapshot(
            stage = "queue_local_hazard_presentation:posted",
            extra = "detectedAtElapsedMs=$detectedAtElapsedMs resolvedTitle=${resolved.displayTitle}",
        )
        refreshPendingHazardAlertOverlay()
        schedulePendingAutoHazardPresentationCheck(detectedAtElapsedMs)
        refreshInputActions()
    }

    private fun startOnlineDetectionLaneIfNeeded(
        lane: OnlineHazardDetectionService.DetectionLane,
        delayMs: Long,
        reason: String,
    ) {
        val runtime = onlineLaneRuntime(lane)
        if (runtime.loopRunning) {
            return
        }
        runtime.loopRunning = true
        runtime.loopEpoch = autoInferenceEpoch
        postOnlineInferenceLoop(lane = lane, delayMs = delayMs, reason = reason)
    }

    private fun postOnlineInferenceLoop(
        lane: OnlineHazardDetectionService.DetectionLane,
        delayMs: Long,
        reason: String,
    ) {
        val runtime = onlineLaneRuntime(lane)
        if (!runtime.loopRunning || destroyed || runtime.loopPosted) {
            return
        }
        Log.d(TAG, "post online inference loop lane=${lane.logName} delayMs=$delayMs reason=$reason")
        runtime.loopPosted = true
        uiHandler.postDelayed(onlineLaneRunnable(lane), delayMs.coerceAtLeast(0L))
    }

    private fun advanceOnlineInferenceLoop(
        lane: OnlineHazardDetectionService.DetectionLane,
        reason: String,
    ) {
        val runtime = onlineLaneRuntime(lane)
        val epoch = runtime.loopEpoch
        if (!isOnlineLoopActive(lane, epoch)) {
            return
        }
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val advanceDecision = AutoInferenceLoopDecider.decideOnlineLoopAdvance(
            queuedNext = false,
            nowElapsedMs = nowElapsedMs,
            nextEarliestStartElapsedMs = runtime.nextEarliestStartElapsedMs,
            loopAlreadyPosted = runtime.loopPosted,
        )
        if (advanceDecision.queueNext) {
            Log.d(TAG, "online queued next lane=${lane.logName} activeRequestIds=${runtime.activeRequestIds}")
            runtime.queuedNext = true
            return
        }
        advanceDecision.delayMs?.let { delayMs ->
            postOnlineInferenceLoop(lane = lane, delayMs = delayMs, reason = "online_wait_interval")
            return
        }
        if (runtime.frameSelectionInProgress) {
            return
        }
        if (nowElapsedMs < runtime.nextEarliestStartElapsedMs) {
            postOnlineInferenceLoop(
                lane = lane,
                delayMs = runtime.nextEarliestStartElapsedMs - nowElapsedMs,
                reason = "online_before_interval",
            )
            return
        }
        runtime.frameSelectionInProgress = true
        InspectionCameraCoordinator.setConsumerWaiting(CameraOwner.AI_INSPECTION, waiting = true)
        cameraRecoveryController.notifyConsumerWaitStarted()
        try {
            imageEncodeExecutor.execute {
                val payload = buildOnlineDetectionPayloadOrNull(
                    lane = lane,
                    lastTimestampExclusive = runtime.lastFrameTimestamp,
                )
                uiHandler.post {
                    runtime.frameSelectionInProgress = false
                    if (!isOnlineLoopActive(lane, epoch)) {
                        return@post
                    }
                    if (payload == null) {
                        if (startPendingStreamAnalysis()) {
                            return@post
                        }
                        postOnlineInferenceLoop(
                            lane = lane,
                            delayMs = AUTO_INFERENCE_RETRY_DELAY_MS,
                            reason = "online_frame_unavailable",
                        )
                        return@post
                    }
                    runtime.lastFrameTimestamp = payload.timestamp
                    InspectionCameraCoordinator.reportFrameConsumed(CameraOwner.AI_INSPECTION, payload.timestamp)
                    InspectionCameraCoordinator.setConsumerWaiting(CameraOwner.AI_INSPECTION, waiting = false)
                    cameraRecoveryController.reportFrameConsumed(payload.timestamp)
                    cameraRecoveryController.notifyConsumerWaitStopped()
                    val requestId = nextOnlineRequestId()
                    runtime.activeRequestIds += requestId
                    runtime.queuedNext = false
                    val startedAtElapsedMs = SystemClock.elapsedRealtime()
                    val detectIntervalMs = onlineDetectIntervalMs(lane)
                    runtime.nextEarliestStartElapsedMs = startedAtElapsedMs + detectIntervalMs
                    AppFileLogger.i(
                        TAG,
                        "start online detect lane=${lane.logName} requestId=$requestId reason=$reason activeRequestIds=${runtime.activeRequestIds} activePoolSize=${runtime.activeRequestIds.size} nextEarliest=${runtime.nextEarliestStartElapsedMs}",
                    )
                    onlineLaneService(lane).submitDetection(
                        OnlineHazardDetectionService.DetectionRequest(
                            epoch = epoch,
                            requestId = requestId,
                            jpegBytes = payload.jpegBytes.copyOf(),
                            lane = lane,
                            frameTimestamp = payload.timestamp,
                            frameCapturedAtElapsedMs = payload.receivedAtElapsedMs,
                            framePayloadBuiltAtElapsedMs = payload.payloadBuiltAtElapsedMs,
                        ),
                    )
                    postOnlineInferenceLoop(
                        lane = lane,
                        delayMs = detectIntervalMs,
                        reason = "online_window_elapsed",
                    )
                }
            }
        } catch (error: RejectedExecutionException) {
            runtime.frameSelectionInProgress = false
            Log.w(TAG, "online frame select rejected lane=${lane.logName}", error)
            postOnlineInferenceLoop(
                lane = lane,
                delayMs = AUTO_INFERENCE_RETRY_DELAY_MS,
                reason = "online_select_rejected",
            )
        }
    }

    private fun handleOnlineDetectionResult(
        request: OnlineHazardDetectionService.DetectionRequest,
        hasHazard: Boolean,
        rawText: String,
        labels: List<String>,
    ) {
        val runtime = onlineLaneRuntime(request.lane)
        val decision = OnlineHazardCompetitionDecider.decide(
            requestId = request.requestId,
            activeRequestIds = runtime.activeRequestIds,
            outcome = if (hasHazard) {
                OnlineHazardCompetitionDecider.Outcome.POSITIVE
            } else {
                OnlineHazardCompetitionDecider.Outcome.NEGATIVE
            },
        )
        if (decision.shouldIgnore) {
            return
        }
        runtime.activeRequestIds.remove(request.requestId)
        logAudioPressureSnapshot(
            stage = "handle_online_detection_result",
            extra = "lane=${request.lane.logName} requestId=${request.requestId} hasHazard=$hasHazard activeRequestIds=${runtime.activeRequestIds} rawTextLength=${rawText.length} labelCount=${labels.size} jpegBytes=${request.jpegBytes.size}",
        )
        AppFileLogger.i(
            TAG,
            "online detect result lane=${request.lane.logName} requestId=${request.requestId} hasHazard=$hasHazard labels=${labels.joinToString()} rawText=${rawText.trim()}",
        )
        remoteFailureCount = 0
        if (decision.shouldStopAllLanes) {
            val cooldownDecision = decideOnlineLabelCooldown(
                labels = labels,
                isCooling = { label -> isLocalLabelCooling(label, SystemClock.elapsedRealtime()) },
            )
            if (cooldownDecision.shouldSuppress) {
                AppFileLogger.i(
                    TAG,
                    "online detect suppressed by label cooldown lane=${request.lane.logName} requestId=${request.requestId} labels=${labels.joinToString()}",
                )
                continueOnlineInferenceAfterCompletion(request.lane)
                return
            }
            stopAutoInferencePipelines(
                reason = "accept_online_hazard_result",
                cancelOnlineDetails = false,
            )
            handleAutoDetectedOnlineHazardResult(
                request.copy(cooldownLabels = cooldownDecision.activeLabels),
            )
            return
        }
        if (decision.shouldContinueCurrentLane) {
            continueOnlineInferenceAfterCompletion(request.lane)
        }
    }

    private fun handleOnlineDetectionFailure(
        request: OnlineHazardDetectionService.DetectionRequest,
        message: String,
    ) {
        val runtime = onlineLaneRuntime(request.lane)
        val decision = OnlineHazardCompetitionDecider.decide(
            requestId = request.requestId,
            activeRequestIds = runtime.activeRequestIds,
            outcome = OnlineHazardCompetitionDecider.Outcome.FAILURE,
        )
        if (decision.shouldIgnore) {
            return
        }
        runtime.activeRequestIds.remove(request.requestId)
        AppFileLogger.w(
            TAG,
            "online detect failed lane=${request.lane.logName} requestId=${request.requestId} epoch=${request.epoch} jpegBytes=${request.jpegBytes.size} autoMode=$autoPipelineMode message=$message",
        )
        if (decision.shouldCountRemoteFailure &&
            handleRemoteDetectionFailureForFallback(reason = "${request.lane.logName}_failure:$message")
        ) {
            return
        }
        if (decision.shouldContinueCurrentLane) {
            continueOnlineInferenceAfterCompletion(request.lane)
        }
    }

    private fun handleOnlineDetectionDropped(
        request: OnlineHazardDetectionService.DetectionRequest,
        reason: String,
    ) {
        val runtime = onlineLaneRuntime(request.lane)
        val decision = OnlineHazardCompetitionDecider.decide(
            requestId = request.requestId,
            activeRequestIds = runtime.activeRequestIds,
            outcome = OnlineHazardCompetitionDecider.Outcome.FAILURE,
        )
        if (decision.shouldIgnore) {
            return
        }
        runtime.activeRequestIds.remove(request.requestId)
        AppFileLogger.i(TAG, "online detect dropped lane=${request.lane.logName} requestId=${request.requestId} reason=$reason")
        if (reason == OnlineHazardDetectionService.REASON_BUSY) {
            if (decision.shouldContinueCurrentLane) {
                continueOnlineInferenceAfterCompletion(request.lane)
            }
            return
        }
        if (decision.shouldCountRemoteFailure &&
            handleRemoteDetectionFailureForFallback(reason = "${request.lane.logName}_dropped:$reason")
        ) {
            return
        }
        if (decision.shouldContinueCurrentLane) {
            continueOnlineInferenceAfterCompletion(request.lane)
        }
    }

    private fun handleRemoteDetectionFailureForFallback(reason: String): Boolean {
        if (autoPipelineMode != AutoHazardPipelineDecider.PipelineMode.REMOTE_PRIMARY) {
            return false
        }
        if (!enableLocalFallbackLoading) {
            AppFileLogger.i(TAG, "skip local fallback loading because config disabled reason=$reason")
            return false
        }
        remoteFailureCount += 1
        val decision = AutoHazardPipelineDecider.decideAfterRemoteFailure(
            currentFailureCount = remoteFailureCount,
            threshold = remoteFailureFallbackThreshold,
        )
        AppFileLogger.w(
            TAG,
            "remote detect failure count=$remoteFailureCount threshold=$remoteFailureFallbackThreshold reason=$reason decision=${decision.mode}",
        )
        if (decision.mode != AutoHazardPipelineDecider.PipelineMode.LOCAL_FALLBACK_LOADING) {
            return false
        }
        applyAutoPipelineDecision(decision, reason = "remote_$reason")
        return true
    }

    private fun continueOnlineInferenceAfterCompletion(
        lane: OnlineHazardDetectionService.DetectionLane,
    ) {
        if (startPendingStreamAnalysis()) {
            return
        }
        val runtime = onlineLaneRuntime(lane)
        if (!runtime.loopRunning || pageState != PageState.DETECTING) {
            return
        }
        val advanceDecision = AutoInferenceLoopDecider.decideOnlineLoopAdvance(
            queuedNext = runtime.queuedNext,
            nowElapsedMs = SystemClock.elapsedRealtime(),
            nextEarliestStartElapsedMs = runtime.nextEarliestStartElapsedMs,
            loopAlreadyPosted = runtime.loopPosted,
        )
        if (advanceDecision.startNow) {
            postOnlineInferenceLoop(
                lane = lane,
                delayMs = 0L,
                reason = "online_queued_after_complete",
            )
            return
        }
        advanceDecision.delayMs?.let { delayMs ->
            postOnlineInferenceLoop(
                lane = lane,
                delayMs = delayMs,
                reason = "online_wait_next_window",
            )
        }
    }

    private fun isLocalLoopActive(epoch: Long): Boolean {
        return !destroyed &&
            pageState == PageState.DETECTING &&
            localLoopRunning &&
            localLoopEpoch == epoch &&
            autoInferenceEpoch == epoch
    }

    private fun isOnlineLoopActive(
        lane: OnlineHazardDetectionService.DetectionLane,
        epoch: Long,
    ): Boolean {
        val runtime = onlineLaneRuntime(lane)
        return !destroyed &&
            pageState == PageState.DETECTING &&
            runtime.loopRunning &&
            runtime.loopEpoch == epoch &&
            autoInferenceEpoch == epoch
    }

    /**
     * 更新最近一次本地推理使用的原始裁切方图。
     * 该缓存视为只读，供自动在线检测优先复用。
     */
    private fun updateLatestSharedInferenceFrame(frame: SquareFramePayload) {
        latestSharedInferenceFrame = frame
        lastMotionUnstableElapsedMs?.let { unstableElapsedMs ->
            if (frame.receivedAtElapsedMs >= unstableElapsedMs) {
                lastMotionUnstableElapsedMs = null
            }
        }
        Log.i(
            TAG,
            "shared frame updated ts=${frame.timestamp} size=${frame.width}x${frame.height} source=${frame.sourceWidth}x${frame.sourceHeight}",
        )
    }

    private fun clearLatestSharedInferenceFrame(reason: String) {
        if (latestSharedInferenceFrame != null) {
            Log.i(TAG, "shared frame cleared reason=$reason")
        }
        latestSharedInferenceFrame = null
    }

    private fun pruneSharedFrameForMotionIfNeeded(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        val frame = latestSharedInferenceFrame ?: return
        val decision = SharedInferenceFrameDecider.decide(
            frameTimestamp = frame.timestamp,
            frameReceivedAtElapsedMs = frame.receivedAtElapsedMs,
            lastTimestampExclusive = Long.MIN_VALUE,
            nowElapsedMs = nowElapsedMs,
            staleFrameThresholdMs = STALE_FRAME_THRESHOLD_MS,
            lastMotionUnstableElapsedMs = lastMotionUnstableElapsedMs,
            motionClearThresholdMs = SHARED_FRAME_MOTION_CLEAR_THRESHOLD_MS,
        )
        if (decision.shouldClearSharedFrame) {
            clearLatestSharedInferenceFrame(decision.reason)
        }
    }

    private fun copyLatestSquareFrameForLocalOrNull(lastTimestampExclusive: Long): SquareFramePayload? {
        if (!frameStreamReady || !RokidFrameSource.isFrameStreamWarm()) {
            return null
        }
        val frame = frameCaptureService.copyLatestSquareFrameOrNull(lastTimestampExclusive) ?: return null
        InspectionCameraCoordinator.reportFrameConsumed(CameraOwner.AI_INSPECTION, frame.timestamp)
        InspectionCameraCoordinator.setConsumerWaiting(CameraOwner.AI_INSPECTION, waiting = false)
        cameraRecoveryController.reportFrameConsumed(frame.timestamp)
        cameraRecoveryController.notifyConsumerWaitStopped()
        return frame
    }

    /**
     * 将统一方图编码为在线链路使用的 JPEG。
     * 本地使用 NV21 缩放后直接推理，在线 lane 使用 JPEG 上传。
     */
    private fun buildCapturedFramePayload(frame: SquareFramePayload): CapturedFramePayload? {
        return frameCaptureService.buildCapturedFramePayload(frame)
    }

    /**
     * 在线检测 lane 直接取最新画面并立即编码，不再等待窗口选帧。
     */
    private fun buildOnlineDetectionPayloadOrNull(
        lane: OnlineHazardDetectionService.DetectionLane,
        lastTimestampExclusive: Long,
    ): CapturedFramePayload? {
        val frame = frameCaptureService.copyLatestSquareFrameOrNull(lastTimestampExclusive) ?: return null
        val payload = buildCapturedFramePayload(frame)
        payload?.let {
            Log.i(
                TAG,
                "selected online frame lane=${lane.logName} strategy=latest ts=${it.timestamp} sharpness=${"%.2f".format(it.sharpnessScore)} crop=${it.cropRect} output=${it.width}x${it.height} bytes=${it.jpegBytes.size}",
            )
        }
        return payload
    }

    // ==================== 隐患处理流程 ====================

    private fun hideStatusAlertOverlay() {
        if (isWearInteractionBlocked()) {
            return
        }
        statusAlertOverlay.reset()
    }

    private fun updateWearMonitoringEnabled() {
        val enabled = shouldEnableWearSleepNow()
        if (!enabled) {
            uiHandler.removeCallbacks(wearRecoveryReadyRunnable)
        }
        updateWearSleepEligibility(enabled)
    }

    override fun onWearStateChanged(snapshot: GlassesWearStateMachine.Snapshot?) {
        val previousState = wearSnapshot?.state
        wearSnapshot = snapshot
        refreshInputActions()
        when (snapshot?.state) {
            GlassesWearStateMachine.State.SLEEP -> showWearSleep()
            GlassesWearStateMachine.State.WAKE -> beginWearRecovery()
            GlassesWearStateMachine.State.ACTIVE -> {
                if (previousState == GlassesWearStateMachine.State.WAKE) {
                    finishWearRecovery()
                }
            }
            else -> Unit
        }
    }

    private fun showWearSleep() {
        if (pageState != PageState.DETECTING || destroyed) {
            updateWearSleepEligibility(false)
            return
        }
        uiHandler.removeCallbacks(wearRecoveryReadyRunnable)
        stopAutoInferencePipelines("wear_sleep", clearPendingStreamState = false)
        cameraRecoveryController.setRecoveryEnabled(false)
        cameraRecoveryController.notifyConsumerWaitStopped()
        stopDetectionPreview()
        frameStreamInitializing = false
        frameStreamReady = false
        frameStreamReadyAtElapsedMs = 0L
        cameraSessionGeneration = 0L
        InspectionCameraCoordinator.pauseTemporarily(CameraOwner.AI_INSPECTION, reason = "ai_wear_sleep")
        tvDetectingBottomHint.visibility = View.GONE
        operationGuideDetecting.visibility = View.GONE
        statusAlertOverlay.render(
            StatusAlertModel(
                status = AlertStatus.WARNING,
                titleText = getString(R.string.inspection_wear_removed_title),
                messageText = getString(R.string.inspection_wear_removed_message),
                behavior = AlertBehavior(autoDismissMs = null, showCountdownBar = false),
                style = AlertStyle(iconResId = R.drawable.hidden_risk_alert),
            ),
        )
    }

    private fun beginWearRecovery() {
        if (destroyed || !isActivityResumed || pageState != PageState.DETECTING) {
            updateWearMonitoringEnabled()
            return
        }
        statusAlertOverlay.render(
            StatusAlertModel(
                status = AlertStatus.WARNING,
                titleText = getString(R.string.inspection_wear_recovering_title),
                messageText = getString(R.string.inspection_wear_recovering_message),
                behavior = AlertBehavior(autoDismissMs = null, showCountdownBar = false),
                style = AlertStyle(iconResId = R.drawable.hidden_risk_alert),
            ),
        )
        cameraRecoveryController.resetRecoveryAttempts()
        cameraRecoveryController.setRecoveryEnabled(true)
        applyDetectionPreviewVisibility()
        initFrameStreamAndTransition()
        scheduleWearRecoveryReadyCheck()
    }

    private fun scheduleWearRecoveryReadyCheck() {
        uiHandler.removeCallbacks(wearRecoveryReadyRunnable)
        uiHandler.post(wearRecoveryReadyRunnable)
    }

    private fun maybeCompleteWearRecovery(): Boolean {
        if (!isWearRecovering() || !frameStreamReady || !RokidFrameSource.isFrameStreamWarm()) {
            return false
        }
        val readyAt = frameStreamReadyAtElapsedMs.takeIf { it > 0L } ?: return false
        if (SystemClock.elapsedRealtime() - readyAt < CAPTURE_WARMUP_MS) {
            return false
        }
        reportWearRecoveryReady()
        return true
    }

    private fun finishWearRecovery() {
        uiHandler.removeCallbacks(wearRecoveryReadyRunnable)
        statusAlertOverlay.reset()
        showPendingUploadSuccessToastIfNeeded()
        applyDefaultDetectionStatus()
        updateDetectingBottomHintVisibility()
        updateFunctionMenuVisibility()
        startAutoInferencePipelinesIfNeeded(reason = "wear_recovery_ready", preferImmediate = true)
    }
    private fun refreshPendingHazardAlertOverlay() {
        if (pageState != PageState.DETECTING || pendingAutoHazardPresentation == null) {
            hideStatusAlertOverlay()
            updateDetectingBottomHintVisibility()
            return
        }
        updateDetectingBottomHintVisibility()
        statusAlertOverlay.render(
            StatusAlertModel(
                status = AlertStatus.WARNING,
                titleText = "",
                messageText = getString(R.string.ai_inspection_pending_hazard_alert),
                behavior = AlertBehavior(autoDismissMs = null, showCountdownBar = false),
                style = AlertStyle(
                    iconResId = R.drawable.hidden_risk_alert,
                ),
            ),
        )
        playHazardAlertIfNeeded()
    }

    private fun updateDetectingBottomHintVisibility() {
        tvDetectingBottomHint.setText(
            if (manualDeepAnalysisInProgress) {
                R.string.ai_inspection_manual_deep_analysis_pending
            } else {
                R.string.ai_inspection_detecting_bottom_hint
            },
        )
        tvDetectingBottomHint.visibility =
            if (pageState == PageState.DETECTING &&
                pendingAutoHazardPresentation == null &&
                !isWearInteractionBlocked()
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun applyDefaultDetectionStatus() {
        // 检测页不再显示状态监测文案，内部状态仅用于自动抓拍和自动分析调度。
    }

    private fun refreshDetectionStatus() {
        // 检测状态仅保留内部状态机，不再向检测页渲染文案。
        updateDetectingBottomHintVisibility()
    }

    // ==================== UI 页面切换 ====================

    private fun startDetectionPreviewIfNeeded() {
        if (!isActivityResumed || destroyed) {
            Log.i(
                TAG,
                "skip start left-top live preview resumed=$isActivityResumed destroyed=$destroyed pageState=$pageState",
            )
            return
        }
        if (!frameStreamReady || !RokidFrameSource.isFrameStreamOpen()) {
            Log.i(
                TAG,
                "defer start left-top live preview frameStreamReady=$frameStreamReady frameStreamOpen=${RokidFrameSource.isFrameStreamOpen()} pageState=$pageState",
            )
            return
        }
        Log.i(
            TAG,
            "request left-top live preview pageState=$pageState previewStarted=${viewLivePreview.isPreviewStarted()} generation=${InspectionCameraCoordinator.getGeneration()}",
        )
        applyDetectionPreviewVisibility()
        InspectionCameraCoordinator.updatePreview(
            owner = CameraOwner.AI_INSPECTION,
            needPreview = shouldKeepDetectionPreviewRunning(),
            previewView = viewLivePreview,
        ) { success ->
            if (!success) {
                Log.w(TAG, "left-top live preview start failed")
                return@updatePreview
            }
            if (!viewLivePreview.isPreviewStarted() && !previewRecreateAttempted) {
                recreateDetectionPreviewView(reason = "update_preview_not_started")
                return@updatePreview
            }
            applyDetectionPreviewVisibility()
            Log.i(
                TAG,
                "left-top live preview ready pageState=$pageState generation=${InspectionCameraCoordinator.getGeneration()}",
            )
            scheduleDetectionPreviewDrawCheck(InspectionCameraCoordinator.getGeneration())
        }
    }

    private fun scheduleDetectionPreviewDrawCheck(generation: Long) {
        uiHandler.postDelayed(
            {
                if (
                    destroyed ||
                    !isActivityResumed ||
                    generation != InspectionCameraCoordinator.getGeneration() ||
                    !shouldKeepDetectionPreviewRunning()
                ) {
                    return@postDelayed
                }
                if (viewLivePreview.isPreviewStarted() && !viewLivePreview.isPreviewFrameDrawn()) {
                    Log.w(
                        TAG,
                        "left-top live preview has no drawn frame generation=$generation state=${InspectionCameraCoordinator.getState()}",
                    )
                    if (!previewRecreateAttempted) {
                        recreateDetectionPreviewView(reason = "preview_no_drawn_frame")
                    }
                }
            },
            DETECTION_PREVIEW_DRAW_CHECK_DELAY_MS,
        )
    }

    private fun recreateDetectionPreviewView(reason: String) {
        previewRecreateAttempted = true
        Log.i(
            TAG,
            "recreateDetectionPreviewView reason=$reason state=${InspectionCameraCoordinator.getState()} previewStarted=${viewLivePreview.isPreviewStarted()} frameDrawn=${viewLivePreview.isPreviewFrameDrawn()} pageState=$pageState",
        )
        InspectionCameraCoordinator.updatePreview(
            owner = CameraOwner.AI_INSPECTION,
            needPreview = false,
            previewView = null,
        ) {
            val oldPreview = viewLivePreview
            val oldIndex = layoutLivePreviewCard.indexOfChild(oldPreview).takeIf { it >= 0 }
                ?: layoutLivePreviewCard.childCount
            val oldLayoutParams = oldPreview.layoutParams
            val oldVisibility = oldPreview.visibility
            oldPreview.detachPreview()
            layoutLivePreviewCard.removeView(oldPreview)
            val newPreview = RokidCameraPreviewView(this).apply {
                id = R.id.viewLivePreview
                visibility = oldVisibility
                layoutParams = oldLayoutParams ?: FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            layoutLivePreviewCard.addView(newPreview, oldIndex)
            viewLivePreview = newPreview
            applyDetectionPreviewVisibility()
            InspectionCameraCoordinator.updatePreview(
                owner = CameraOwner.AI_INSPECTION,
                needPreview = shouldKeepDetectionPreviewRunning(),
                previewView = viewLivePreview,
            ) rebindPreview@{ success ->
                frameStreamReady = success
                frameStreamReadyAtElapsedMs = if (success) SystemClock.elapsedRealtime() else 0L
                if (!success) {
                    Log.w(TAG, "recreated left-top live preview bind failed reason=$reason")
                    return@rebindPreview
                }
                Log.i(
                    TAG,
                    "recreated left-top live preview ready reason=$reason generation=${InspectionCameraCoordinator.getGeneration()}",
                )
                scheduleDetectionPreviewDrawCheck(InspectionCameraCoordinator.getGeneration())
            }
        }
    }

    private fun shouldKeepDetectionPreviewRunning(state: PageState = pageState): Boolean {
        return state == PageState.DETECTING ||
            (state == PageState.STREAM_RESPONSE && !isFixedResultPanelMode())
    }

    private fun shouldShowDetectionPreviewCard(state: PageState = pageState): Boolean {
        return state == PageState.DETECTING ||
            (state == PageState.STREAM_RESPONSE && !isFixedResultPanelMode())
    }

    private fun applyDetectionPreviewVisibility() {
        val shouldShow = shouldShowDetectionPreviewCard()
        layoutLivePreviewCard.visibility = if (shouldShow) View.VISIBLE else View.INVISIBLE
        viewLivePreview.visibility = if (shouldShow) View.VISIBLE else View.INVISIBLE
    }

    private fun stopDetectionPreview(stopRenderer: Boolean = true) {
        Log.i(
            TAG,
            "stop left-top live preview pageState=$pageState stopRenderer=$stopRenderer previewStarted=${viewLivePreview.isPreviewStarted()}",
        )
        layoutLivePreviewCard.visibility = View.INVISIBLE
        viewLivePreview.visibility = View.INVISIBLE
        previewRecreateAttempted = false
        InspectionCameraCoordinator.updatePreview(
            owner = CameraOwner.AI_INSPECTION,
            needPreview = false,
            previewView = null,
        )
    }

    private fun showPage(state: PageState) {
        pageState = state
        if (state != PageState.STREAM_RESPONSE) {
            streamPanelAnchoredBelowPreview = false
        }
        cameraRecoveryController.setRecoveryEnabled(
            debugSnapshotState == null &&
                state == PageState.DETECTING &&
                isActivityResumed &&
                isWorkflowActive,
        )
        if (state != PageState.DETECTING) {
            cameraRecoveryController.notifyConsumerWaitStopped()
        }
        layoutLoading.visibility = View.GONE
        layoutDetection.visibility = if (state == PageState.DETECTING) View.VISIBLE else View.GONE
        layoutStreamResponse.visibility =
            if (state == PageState.STREAM_RESPONSE) View.VISIBLE else View.GONE
        val shouldShowLivePreview =
            state == PageState.DETECTING || state == PageState.STREAM_RESPONSE
        val shouldKeepPreviewRunning = shouldKeepDetectionPreviewRunning(state)
        AppFileLogger.i(
            TAG,
            "showPage state=$state shouldShowLivePreview=$shouldShowLivePreview shouldKeepPreviewRunning=$shouldKeepPreviewRunning resumed=$isActivityResumed workflowActive=$isWorkflowActive",
        )
        if (shouldShowLivePreview) {
            startDetectionPreviewIfNeeded()
        } else {
            stopDetectionPreview()
        }
        if (debugSnapshotState == null && isActivityResumed && isWorkflowActive) {
            InspectionCameraCoordinator.updatePreview(
                owner = CameraOwner.AI_INSPECTION,
                needPreview = shouldKeepPreviewRunning,
                previewView = if (shouldKeepPreviewRunning) viewLivePreview else null,
            )
        }
        if (state != PageState.DETECTING) {
            updateWearSleepEligibility(false)
            hideStatusAlertOverlay()
        }
        if (state == PageState.DETECTING) {
            clearStreamResponseUiState()
            refreshDetectionStatus()
            refreshPendingHazardAlertOverlay()
        }
        hideActionPrompts()
        updateFunctionMenuVisibility()
        refreshInputActions()
        updateWearMonitoringEnabled()
    }

    private fun applyDebugSnapshotState(state: String) {
        when (state) {
            "analyzing" -> {
                showPage(PageState.DETECTING)
            }
            "result" -> {
                showPage(PageState.STREAM_RESPONSE)
                localResultStage = LocalResultStage.DESCRIPTION
                setStreamContentAndResetViewport(
                    intent.getStringExtra("debug_text")
                        ?: getString(R.string.ai_inspection_debug_result_text),
                )
                renderLocalDescriptionPrompt()
                // 调试模式：显示一个测试缩略图
                showDebugThumbnail()
            }
            "sync" -> {
                showPage(PageState.STREAM_RESPONSE)
                localResultStage = LocalResultStage.ADVICE
                setStreamContentAndResetViewport(getString(R.string.ai_inspection_debug_result_text))
                renderLocalAdvicePrompt()
            }
            else -> {
                showPage(PageState.DETECTING)
            }
        }
        refreshInputActions()
    }

    private fun buildInputActions(): List<UnifiedInputSession.InputActionSpec> {
        return listOf(
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_detecting_stream_analysis"),
                label = getString(R.string.ai_inspection_input_label_detecting_analysis),
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK),
                    voiceTrigger(R.string.ai_inspection_voice_analysis, "fen xi"),
                    voiceTrigger(R.string.ai_inspection_voice_analysis_deep, "shen du fen xi"),
                ),
                enabled = { canHandleDetectingInput() },
            ) {
                requestStreamingAnalysis()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_detecting_back_to_menu"),
                label = getString(R.string.ai_inspection_input_label_detecting_return),
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK),
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK),
                    voiceTrigger(R.string.ai_inspection_voice_return, "fan hui"),
                    voiceTrigger(R.string.ai_inspection_voice_cancel_alias, "qu xiao"),
                ),
                enabled = { canHandleDetectingInput() },
            ) {
                returnDirectlyToHome()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_detecting_finish"),
                label = getString(R.string.ai_inspection_input_label_detecting_finish),
                triggers = listOf(
                    voiceTrigger(R.string.ai_inspection_voice_finish, "jie shu ren wu"),
                    voiceTrigger(R.string.ai_inspection_voice_finish_accent_alias, "jie su ren wu"),
                ),
                enabled = { canHandleDetectingInput() },
            ) {
                finishInspectionWithReport()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_detecting_device_guide"),
                label = getString(R.string.ai_entry_menu_guide),
                triggers = listOf(UnifiedInputSession.InputTrigger.Voice("设备指引", "she bei zhi yin")),
                enabled = { canHandleDetectingInput() },
            ) {
                InspectionCameraCoordinator.releaseForNavigation(CameraOwner.AI_INSPECTION, reason = "ai_nav_device_guide")
                startActivity(Intent(this, DeviceGuideActivity::class.java))
                finish()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_detecting_hazard_record"),
                label = getString(R.string.ai_entry_menu_record),
                triggers = listOf(UnifiedInputSession.InputTrigger.Voice("隐患拍照", "yin huan pai zhao")),
                enabled = { canHandleDetectingInput() },
            ) {
                Log.i(
                    TAG,
                    "navigateToHazardRecord pageState=$pageState resumed=$isActivityResumed active=$isWorkflowActive frameReady=$frameStreamReady frameOpen=${RokidFrameSource.isFrameStreamOpen()} frameWarm=${RokidFrameSource.isFrameStreamWarm()} previewStarted=${viewLivePreview.isPreviewStarted()}",
                )
                InspectionCameraCoordinator.releaseForNavigation(CameraOwner.AI_INSPECTION, reason = "ai_nav_hazard_record")
                startActivity(Intent(this, HazardRecordActivity::class.java))
                finish()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Confirm,
                label = getString(R.string.ai_inspection_input_label_confirm),
                triggers = buildConfirmTriggers(),
                enabled = {
                    pageState == PageState.STREAM_RESPONSE &&
                        !streamingInProgress &&
                        !localSaveSubmitting &&
                        localResultStage == LocalResultStage.NONE
                },
            ) {
                returnToDetecting()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Confirm,
                label = getString(R.string.ai_inspection_input_label_confirm),
                triggers = buildConfirmTriggers(),
                enabled = {
                    pageState == PageState.STREAM_RESPONSE &&
                        !streamingInProgress &&
                        !localSaveSubmitting &&
                        localResultStage == LocalResultStage.DESCRIPTION
                },
            ) {
                handleStreamConfirmAction()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Confirm,
                label = getString(R.string.ai_inspection_input_label_confirm),
                triggers = buildConfirmTriggers(),
                enabled = {
                    pageState == PageState.STREAM_RESPONSE &&
                        !streamingInProgress &&
                        localResultStage == LocalResultStage.ADVICE
                },
            ) {
                returnToDetectingFromAdvice()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Cancel,
                label = getString(R.string.ai_inspection_input_label_return),
                triggers = buildReturnTriggers(),
                enabled = {
                    pageState == PageState.STREAM_RESPONSE &&
                        !localSaveSubmitting &&
                        localResultStage == LocalResultStage.NONE
                },
            ) {
                handleStreamCancelAction()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Cancel,
                label = getString(R.string.ai_inspection_input_label_return),
                triggers = buildReturnTriggers(),
                enabled = {
                    pageState == PageState.STREAM_RESPONSE &&
                        !streamingInProgress &&
                        !localSaveSubmitting &&
                        localResultStage == LocalResultStage.DESCRIPTION
                },
            ) {
                returnToDetecting()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Cancel,
                label = getString(R.string.ai_inspection_input_label_return),
                triggers = buildReturnTriggers(),
                enabled = {
                    pageState == PageState.STREAM_RESPONSE &&
                        !streamingInProgress &&
                        localResultStage == LocalResultStage.ADVICE
                },
            ) {
                returnToDetectingFromAdvice()
            },
        )
    }

    private fun refreshInputActions() {
        inputSession.updateActions(buildInputActions())
    }

    private fun canHandleDetectingInput(): Boolean {
        return pageState == PageState.DETECTING &&
            !isAutoHazardPresentationPending() &&
            !isWearInteractionBlocked()
    }

    private fun isWearInteractionBlocked(): Boolean {
        return wearSnapshot?.state in setOf(GlassesWearStateMachine.State.SLEEP, GlassesWearStateMachine.State.WAKE)
    }

    private fun isWearRecovering(): Boolean = wearSnapshot?.state == GlassesWearStateMachine.State.WAKE

    private fun voiceTrigger(@StringRes textRes: Int, pinyin: String): UnifiedInputSession.InputTrigger {
        return UnifiedInputSession.InputTrigger.Voice(getString(textRes), pinyin)
    }

    private fun buildConfirmTriggers(): List<UnifiedInputSession.InputTrigger> {
        return listOf(
            UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK),
            voiceTrigger(R.string.ai_inspection_voice_confirm, "que ren"),
            voiceTrigger(R.string.ai_inspection_voice_confirm_alias, "que ding"),
            voiceTrigger(R.string.ai_inspection_voice_continue_alias, "ji xu"),
        )
    }

    private fun buildReturnTriggers(): List<UnifiedInputSession.InputTrigger> {
        return listOf(
            UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK),
            UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK),
            voiceTrigger(R.string.ai_inspection_voice_return, "fan hui"),
            voiceTrigger(R.string.ai_inspection_voice_cancel_alias, "qu xiao"),
        )
    }

    private fun updateConfirmationHints() {
        tvStreamBottomHint.setText(R.string.ai_inspection_description_bottom_hint)
        hideActionPrompts()
    }

    private fun isFixedResultPanelMode(): Boolean {
        val hasLocalResult =
            localResultStage == LocalResultStage.DESCRIPTION || localResultStage == LocalResultStage.ADVICE
        return pageState == PageState.STREAM_RESPONSE &&
            (streamPanelAnchoredBelowPreview ||
                (!streamingInProgress && hasLocalResult))
    }

    private fun previewBottomOffsetPx(): Int {
        if (!isFixedResultPanelMode() || localResultStage == LocalResultStage.ADVICE) return 0
        return resources.getDimensionPixelSize(R.dimen.inspection_result_thumbnail_margin_top) +
            resources.getDimensionPixelSize(R.dimen.inspection_result_thumbnail_card_size) +
            resources.getDimensionPixelSize(R.dimen.inspection_result_thumbnail_spacing_bottom)
    }

    private fun applyDefaultStreamPanelLayout() {
        val topParams = streamTopSpacer.layoutParams as LinearLayout.LayoutParams
        topParams.height = 0
        topParams.weight = 0f
        streamTopSpacer.layoutParams = topParams
        streamTopSpacer.visibility = View.GONE

        val bottomParams = streamBottomSpacer.layoutParams as LinearLayout.LayoutParams
        bottomParams.height = 0
        bottomParams.weight = 1f
        streamBottomSpacer.layoutParams = bottomParams
        streamBottomSpacer.visibility = View.VISIBLE
    }

    private fun applyFixedResultStreamPanelLayout() {
        val topParams = streamTopSpacer.layoutParams as LinearLayout.LayoutParams
        if (localResultStage == LocalResultStage.ADVICE) {
            topParams.height = 0
            topParams.weight = 1f
        } else {
            topParams.height = previewBottomOffsetPx()
            topParams.weight = 0f
        }
        streamTopSpacer.layoutParams = topParams
        streamTopSpacer.visibility = View.VISIBLE

        val bottomParams = streamBottomSpacer.layoutParams as LinearLayout.LayoutParams
        bottomParams.height = 0
        bottomParams.weight = if (localResultStage == LocalResultStage.ADVICE) 0f else 1f
        streamBottomSpacer.layoutParams = bottomParams
        streamBottomSpacer.visibility = if (localResultStage == LocalResultStage.ADVICE) View.GONE else View.VISIBLE
    }

    private fun applyCurrentStreamPanelLayout() {
        applyDetectionPreviewVisibility()
        val shouldShowThumbnail = isFixedResultPanelMode() && localResultStage == LocalResultStage.DESCRIPTION
        layoutStreamThumbnailCard.visibility =
            if (shouldShowThumbnail) View.VISIBLE else View.GONE
        layoutStreamThumbnailPlaceholder.visibility =
            if (shouldShowThumbnail && ivStreamThumbnail.visibility != View.VISIBLE) View.VISIBLE else View.GONE
        tvStreamBottomHint.visibility =
            if (isFixedResultPanelMode() && localResultStage == LocalResultStage.DESCRIPTION) View.VISIBLE else View.GONE
        if (isFixedResultPanelMode()) {
            applyFixedResultStreamPanelLayout()
        } else {
            applyDefaultStreamPanelLayout()
        }
    }

    private fun clearStreamThumbnailState() {
        ivStreamThumbnail.setImageBitmap(null)
        ivStreamThumbnail.visibility = View.GONE
        layoutStreamThumbnailPlaceholder.visibility =
            if (isFixedResultPanelMode() && localResultStage == LocalResultStage.DESCRIPTION) View.VISIBLE else View.GONE
        currentStreamThumbnail?.takeIf { !it.isRecycled }?.recycle()
        currentStreamThumbnail = null
        layoutStreamThumbnailCard.visibility =
            if (isFixedResultPanelMode() && localResultStage == LocalResultStage.DESCRIPTION) View.VISIBLE else View.GONE
    }

    private fun clearStreamResponseUiState() {
        clearStreamThumbnailState()
        hideUploadSuccessToast(immediate = true)
        scrollContent.animate().cancel()
        scrollContent.translationY = 0f
        scrollContent.alpha = 1f
        adviceCardAnimating = false
        streamAutoScrollLocked = false
        tvStreamContent.text = ""
        applyDefaultStreamPanelLayout()
        tvStreamBottomHint.visibility = View.GONE
        tvStreamBottomHint.setText(R.string.ai_inspection_description_bottom_hint)
        scrollContent.layoutParams = scrollContent.layoutParams.apply {
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        scrollContent.requestLayout()
        scrollContent.post {
            scrollContent.scrollTo(0, 0)
            scrollContent.fullScroll(View.FOCUS_UP)
        }
        hideActionPrompts()
    }

    private fun setStreamContentAndResetViewport(text: String) {
        streamAutoScrollLocked = false
        tvStreamContent.text = ensureAdviceDisplayPrefix(text)
        applyCurrentStreamPanelLayout()
        adjustStreamScrollHeight()
        scrollContent.post {
            scrollContent.scrollTo(0, 0)
            scrollContent.fullScroll(View.FOCUS_UP)
        }
    }

    private fun updateStreamingText(partialText: String) {
        val previousScrollY = scrollContent.scrollY
        tvStreamContent.text = ensureAdviceDisplayPrefix(partialText)
        applyCurrentStreamPanelLayout()
        adjustStreamScrollHeight()
        scrollContent.post {
            val maxScrollY = maxStreamScrollY()
            when {
                streamAutoScrollLocked -> {
                    scrollContent.scrollTo(0, previousScrollY.coerceAtMost(maxScrollY))
                }

                maxScrollY > 0 -> {
                    streamAutoScrollLocked = true
                    scrollContent.scrollTo(0, previousScrollY.coerceAtMost(maxScrollY))
                }

                else -> {
                    scrollContent.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    private fun ensureAdviceDisplayPrefix(text: String): String {
        if (localResultStage != LocalResultStage.ADVICE) {
            return text
        }
        val trimmedText = text.trimStart()
        return when {
            trimmedText.isBlank() -> ADVICE_DISPLAY_PREFIX
            trimmedText.startsWith(ADVICE_DISPLAY_PREFIX) -> text
            else -> "$ADVICE_DISPLAY_PREFIX\n$trimmedText"
        }
    }

    private fun startSimulatedStreamRendering(
        fullText: String,
        targetStage: LocalResultStage = LocalResultStage.DESCRIPTION,
    ) {
        cancelSimulatedStreamRendering()
        val requestId = ++simulatedStreamRequestId
        val chunks = SimulatedStreamTextChunker.prefixChunks(
            text = fullText,
            chunkSize = SIMULATED_STREAM_CHUNK_CHARS,
        )
        var chunkIndex = 0
        val renderRunnable = object : Runnable {
            override fun run() {
                if (
                    destroyed ||
                    requestId != simulatedStreamRequestId ||
                    pageState != PageState.STREAM_RESPONSE ||
                    localResultStage != targetStage
                ) {
                    return
                }
                updateStreamingText(chunks[chunkIndex])
                chunkIndex += 1
                if (chunkIndex < chunks.size) {
                    uiHandler.postDelayed(this, SIMULATED_STREAM_CHUNK_DELAY_MS)
                    return
                }
                simulatedStreamRunnable = null
                streamingInProgress = false
                streamCallbackActive = false
                when (targetStage) {
                    LocalResultStage.DESCRIPTION -> renderLocalDescriptionPrompt()
                    LocalResultStage.ADVICE -> renderLocalAdvicePrompt()
                    LocalResultStage.NONE -> hideActionPrompts()
                }
                refreshInputActions()
            }
        }
        simulatedStreamRunnable = renderRunnable
        uiHandler.post(renderRunnable)
    }

    private fun cancelSimulatedStreamRendering() {
        simulatedStreamRunnable?.let(uiHandler::removeCallbacks)
        simulatedStreamRunnable = null
        simulatedStreamRequestId += 1
    }

    private fun maxStreamScrollY(): Int {
        val contentView = scrollContent.getChildAt(0) ?: return 0
        return (contentView.height - scrollContent.height).coerceAtLeast(0)
    }

    private fun advanceStreamViewportByPage(): Boolean {
        val maxScrollY = maxStreamScrollY()
        if (maxScrollY <= scrollContent.scrollY) {
            return false
        }
        val pageHeight = scrollContent.height.takeIf { it > 0 }
            ?: (resources.displayMetrics.heightPixels / 2).coerceAtLeast(1)
        val targetScrollY = (scrollContent.scrollY + pageHeight).coerceAtMost(maxScrollY)
        scrollContent.post {
            scrollContent.scrollTo(0, targetScrollY)
        }
        return true
    }

    private fun failWorkflow(message: String) {
        AppFileLogger.e(TAG, "workflow failed: $message")
        // 简化错误处理，仅记录日志，不显示错误页面
        // 因为加载页面已剥离到 InspectionLoadingActivity
    }

    // ==================== 工具方法 ====================

    private fun ensureNativeEngine(): HiddenRiskNcnn? {
        hiddenRiskNcnn?.let { return it }
        return runCatching { HiddenRiskNcnn() }
            .onFailure { e -> Log.e(TAG, "HiddenRiskNcnn init failed", e) }
            .getOrNull()
            ?.also { hiddenRiskNcnn = it }
    }

    private fun ensureHazardCaptureService(): HazardCaptureService {
        hazardCaptureService?.let { return it }
        return HazardCaptureService(this).also {
            hazardCaptureService = it
        }
    }

    private fun submitNativeTask(task: () -> Unit): Boolean {
        return try {
            nativeExecutor.execute(task)
            true
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "native task rejected", e)
            false
        }
    }

    // ==================== 本地隐患知识结果 ====================

    /**
     * 自动识别命中后，统一根据代码级结果路由开关决定最终落到本地页还是在线页。
     */
    private fun resolveAutoHazardRoute(triggerSource: AutoHazardTriggerSource): AutoHazardResultRoute {
        return when (AUTO_HAZARD_ROUTING_MODE) {
            AutoHazardRoutingMode.SEPARATED -> when (triggerSource) {
                AutoHazardTriggerSource.LOCAL -> AutoHazardResultRoute.LOCAL
                AutoHazardTriggerSource.ONLINE -> AutoHazardResultRoute.ONLINE
            }
            AutoHazardRoutingMode.ONLINE_ONLY -> AutoHazardResultRoute.ONLINE
            AutoHazardRoutingMode.LOCAL_ONLY -> AutoHazardResultRoute.LOCAL
        }
    }

    private fun handleAutoDetectedLocalHazardResult(
        localMatches: List<LocalHazardMatch>,
        frame: SquareFramePayload,
        snapshot: NativeInferenceStats?,
    ) {
        when (val finalRoute = resolveAutoHazardRoute(AutoHazardTriggerSource.LOCAL)) {
            AutoHazardResultRoute.LOCAL -> {
                logAutoHazardRoute(
                    triggerSource = AutoHazardTriggerSource.LOCAL,
                    finalRoute = finalRoute,
                    extra = "matchCount=${localMatches.size} detectionCount=${snapshot?.detectionCount ?: 0}",
                )
                queueAutoDetectedLocalHazardPresentation(
                    localMatches = localMatches,
                    frame = frame,
                    snapshot = snapshot,
                )
            }

            AutoHazardResultRoute.ONLINE -> {
                logAutoHazardRoute(
                    triggerSource = AutoHazardTriggerSource.LOCAL,
                    finalRoute = finalRoute,
                    extra = "matchCount=${localMatches.size} detectionCount=${snapshot?.detectionCount ?: 0}",
                )
                queueAutoDetectedOnlineHazardPresentationFromLocalTrigger(
                    frame = frame,
                    snapshot = snapshot,
                )
            }
        }
    }

    private fun handleAutoDetectedOnlineHazardResult(request: OnlineHazardDetectionService.DetectionRequest) {
        when (val finalRoute = resolveAutoHazardRoute(AutoHazardTriggerSource.ONLINE)) {
            AutoHazardResultRoute.ONLINE -> {
                logAutoHazardRoute(
                    triggerSource = AutoHazardTriggerSource.ONLINE,
                    finalRoute = finalRoute,
                    extra = "requestId=${request.requestId} jpegBytes=${request.jpegBytes.size}",
                )
                queueAutoDetectedOnlineHazardPresentation(request)
            }

            AutoHazardResultRoute.LOCAL -> {
                logAutoHazardRoute(
                    triggerSource = AutoHazardTriggerSource.ONLINE,
                    finalRoute = finalRoute,
                    extra = "requestId=${request.requestId} jpegBytes=${request.jpegBytes.size}",
                )
                queueAutoDetectedGenericLocalHazardPresentationFromOnline(request)
            }
        }
    }

    private fun logAutoHazardRoute(
        triggerSource: AutoHazardTriggerSource,
        finalRoute: AutoHazardResultRoute,
        extra: String = "",
    ) {
        val details = if (extra.isBlank()) "" else " $extra"
        Log.i(
            TAG,
            "auto hazard route triggerSource=$triggerSource routingMode=$AUTO_HAZARD_ROUTING_MODE finalRoute=$finalRoute$details",
        )
    }

    private fun findLocalHazardMatches(snapshot: NativeInferenceStats): List<LocalHazardMatch> {
        if (localHazardInfoByItem.isEmpty()) {
            return emptyList()
        }
        val detectedScoresByLabel = buildDetectedScoresByLabel(snapshot)
        if (detectedScoresByLabel.isEmpty()) {
            return emptyList()
        }
        val matches = localHazardInfoByItem
            .flatMap { (itemName, infos) ->
                val itemMatch = LocalHazardItemMatcher.match(
                    itemName = itemName,
                    detectedScoresByLabel = detectedScoresByLabel,
                ) ?: return@flatMap emptyList()
                infos.map { info ->
                    LocalHazardMatch(
                        info = info,
                        matchedItem = itemMatch.matchedItem,
                        cooldownLabel = itemMatch.cooldownLabel,
                        score = itemMatch.score,
                    )
                }
            }
        return LocalHazardResultDeduper.dedupeByHidNumKeepingHighestScore(
            matches = matches,
            hidNumOf = { it.info.requestHidNum() },
            scoreOf = { it.score },
        ).sortedByDescending { it.score }
    }

    private fun buildDetectedScoresByLabel(snapshot: NativeInferenceStats): Map<String, Float> {
        val scoresByLabel = linkedMapOf<String, Float>()
        snapshot.detections.forEach { detection ->
            sequenceOf(detection.label, HiddenRiskMultiOverlayRenderer.labelFor(detection))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { label ->
                    val previousScore = scoresByLabel[label]
                    if (previousScore == null || detection.score > previousScore) {
                        scoresByLabel[label] = detection.score
                    }
                }
        }
        return scoresByLabel
    }

    private fun buildLocalResolvedContent(
        localMatches: List<LocalHazardMatch>,
        jpegBytes: ByteArray,
    ): ResolvedHazardContent? {
        val startElapsedMs = SystemClock.elapsedRealtime()
        logAudioPressureSnapshot(
            stage = "build_local_resolved_content:start",
            extra = "matchCount=${localMatches.size} jpegBytes=${jpegBytes.size}",
        )
        if (localMatches.isEmpty()) {
            logAudioPressureSnapshot(
                stage = "build_local_resolved_content:empty",
                extra = "elapsedMs=${SystemClock.elapsedRealtime() - startElapsedMs}",
            )
            return null
        }
        val resolvedHazards = localMatches.map { it.toResolvedItem() }
        val primaryHazard = resolvedHazards.first()
        val content = ResolvedHazardContent(
            source = HazardSource.LOCAL,
            description = primaryHazard.description,
            advice = primaryHazard.advice,
            uploadAdvice = primaryHazard.uploadAdvice,
            hidLevel = primaryHazard.hidLevel,
            hidNum = primaryHazard.hidNum,
            lawBasis = primaryHazard.lawBasis,
            displayTitle = primaryHazard.displayTitle,
            jpegBytes = jpegBytes.copyOf(),
            hazards = resolvedHazards,
            localCooldownLabels = localMatches.map { it.cooldownLabel },
        )
        logAudioPressureSnapshot(
            stage = "build_local_resolved_content:end",
            extra = "title=${content.displayTitle} hazardCount=${resolvedHazards.size} elapsedMs=${SystemClock.elapsedRealtime() - startElapsedMs}",
        )
        return content
    }

    /**
     * ONLINE_ONLY 下，复用本地自动识别使用的同一帧图像，直接切到在线详情链路。
     */
    private fun queueAutoDetectedOnlineHazardPresentationFromLocalTrigger(
        frame: SquareFramePayload,
        snapshot: NativeInferenceStats?,
    ) {
        logAudioPressureSnapshot(
            stage = "queue_online_hazard_from_local:start",
            extra = "frameTs=${frame.timestamp} detectionCount=${snapshot?.detectionCount ?: 0}",
        )
        try {
            imageEncodeExecutor.execute {
                val payload = buildCapturedFramePayload(frame)
                if (payload == null || payload.jpegBytes.isEmpty()) {
                    uiHandler.post {
                        handleAutoHazardRouteFailure(
                            reason = "local_to_online_payload_unavailable",
                            toastRes = R.string.ai_inspection_online_image_encode_failed,
                        )
                    }
                    return@execute
                }
                val jpegBytes = payload.jpegBytes
                if (ENABLE_HIT_CAPTURE_SAVE && (snapshot?.detectionCount ?: 0) > 0) {
                    ensureHazardCaptureService().saveHazardCapture(jpegBytes, snapshot)
                }
                uiHandler.post {
                    if (destroyed || pageState != PageState.DETECTING || pendingAutoHazardPresentation != null) {
                        return@post
                    }
                    val request = OnlineHazardDetectionService.DetectionRequest(
                        epoch = autoInferenceEpoch,
                        requestId = nextOnlineRequestId(),
                        jpegBytes = jpegBytes.copyOf(),
                        frameTimestamp = payload.timestamp,
                        frameCapturedAtElapsedMs = payload.receivedAtElapsedMs,
                        framePayloadBuiltAtElapsedMs = payload.payloadBuiltAtElapsedMs,
                    )
                    queueAutoDetectedOnlineHazardPresentation(request)
                }
            }
        } catch (error: RejectedExecutionException) {
            Log.w(TAG, "local to online encode rejected", error)
            handleAutoHazardRouteFailure(
                reason = "local_to_online_encode_rejected",
                toastRes = R.string.ai_inspection_online_image_encode_submit_failed,
            )
        }
    }

    /**
     * LOCAL_ONLY 下，在线命中仅使用在线检测已判定“有隐患”的事实，构造最小本地结果页。
     */
    private fun queueAutoDetectedGenericLocalHazardPresentationFromOnline(
        request: OnlineHazardDetectionService.DetectionRequest,
    ) {
        val resolved = buildGenericLocalResolvedContentFromOnline(request)
        postPendingLocalHazardPresentation(resolved)
    }

    private fun buildGenericLocalResolvedContentFromOnline(
        request: OnlineHazardDetectionService.DetectionRequest,
    ): ResolvedHazardContent {
        val displayTitle = getString(R.string.ai_inspection_hazard_found)
        val description = "在线检测已判定当前画面存在隐患，请到现场复核并继续处理。"
        val advice = "请结合现场环境复核风险点，并按现场规范采取处置措施。"
        val hazard = ResolvedHazardItem(
            displayTitle = displayTitle,
            description = description,
            advice = advice,
            uploadAdvice = "",
            hidLevel = "",
            hidNum = "",
            lawBasis = "",
        )
        return ResolvedHazardContent(
            source = HazardSource.LOCAL,
            description = description,
            advice = advice,
            uploadAdvice = "",
            hidLevel = "",
            hidNum = "",
            lawBasis = "",
            displayTitle = displayTitle,
            jpegBytes = request.jpegBytes.copyOf(),
            hazards = listOf(hazard),
        )
    }

    private fun handleAutoHazardRouteFailure(
        reason: String,
        @StringRes toastRes: Int? = null,
    ) {
        Log.w(
            TAG,
            "auto hazard route failed reason=$reason routingMode=$AUTO_HAZARD_ROUTING_MODE pageState=$pageState onlineOnly=$onlineOnlyRouteEnabled localOnly=$localOnlyRouteEnabled",
        )
        if (destroyed || pageState != PageState.DETECTING) {
            return
        }
        hideStatusAlertOverlay()
        clearPendingAutoHazardPresentation()
        startAutoInferencePipelinesIfNeeded(reason = reason, preferImmediate = true)
        toastRes?.let { resId ->
            SpriteToastUtil.showSpriteToastOld(
                this,
                getString(resId),
                R.drawable.ic_warning_triangle,
                LOCAL_SAVE_SUCCESS_TOAST_MS,
                false,
            )
        }
    }

    private fun buildLocalHazardUploadItems(hazardContent: ResolvedHazardContent): List<LocalHazardPushService.HidDangerItem> {
        return LocalHazardUploadItemBuilder.build(hazardContent)
    }

    private fun buildSavedHazardRecordItems(
        hazardContent: ResolvedHazardContent,
    ): List<InspectionWorkflowSession.SavedHazardItem> {
        return buildLocalHazardUploadItems(hazardContent).map { item ->
            InspectionWorkflowSession.SavedHazardItem(
                hidNum = item.hidNum,
                hidLevel = item.hidLevel,
                description = item.descrip,
                advice = item.advice,
            )
        }
    }

    private fun pruneExpiredLocalCooldowns(nowElapsedMs: Long) {
        val iterator = localLabelCooldownUntilMs.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value <= nowElapsedMs) {
                iterator.remove()
            }
        }
    }

    private fun isLocalLabelCooling(label: String, nowElapsedMs: Long): Boolean {
        val normalizedLabel = label.trim()
        if (normalizedLabel.isBlank()) {
            return false
        }
        val cooldownUntilMs = localLabelCooldownUntilMs[normalizedLabel] ?: return false
        return cooldownUntilMs > nowElapsedMs
    }

    private fun filterLocalMatchesByCooldown(
        localMatches: List<LocalHazardMatch>,
        nowElapsedMs: Long,
    ): List<LocalHazardMatch> {
        if (localMatches.isEmpty()) {
            return emptyList()
        }
        pruneExpiredLocalCooldowns(nowElapsedMs)
        return localMatches.filterNot { match ->
            isLocalLabelCooling(match.cooldownLabel, nowElapsedMs)
        }
    }

    private fun markLocalLabelsCooldown(
        localMatches: List<LocalHazardMatch>,
        nowElapsedMs: Long,
    ) {
        markLocalLabelsCooldownByName(
            labels = localMatches.map { it.cooldownLabel },
            nowElapsedMs = nowElapsedMs,
        )
    }

    private fun markLocalLabelsCooldownByName(
        labels: List<String>,
        nowElapsedMs: Long,
    ) {
        if (labels.isEmpty()) {
            return
        }
        pruneExpiredLocalCooldowns(nowElapsedMs)
        val cooldownUntilMs = nowElapsedMs + LOCAL_LABEL_COOLDOWN_MS
        labels
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { label ->
                localLabelCooldownUntilMs[label] = cooldownUntilMs
            }
    }

    private fun handleOnlineDetailSuccess(
        request: OnlineHazardDetectionService.DetailRequest,
        fullText: String,
    ) {
        if (request.epoch != autoInferenceEpoch) {
            return
        }
        val jpegBytes = request.jpegBytes
        val parseStartElapsedMs = SystemClock.elapsedRealtime()
        logAudioPressureSnapshot(
            stage = "handle_online_detail_success:start",
            extra = "requestId=${request.requestId} textLength=${fullText.length} jpegBytes=${jpegBytes.size}",
        )
        Log.i(
            TAG,
            "online detail fullText lane=${request.lane.logName} requestId=${request.requestId} text=${summarizeLogText(fullText)}",
        )
        val resolved = runCatching {
            AiArHazardDetailParser.parse(
                text = fullText,
                jpegBytes = jpegBytes,
            )
        }.getOrElse { error ->
            logAudioPressureSnapshot(
                stage = "handle_online_detail_success:parse_failed",
                extra = "requestId=${request.requestId} elapsedMs=${SystemClock.elapsedRealtime() - parseStartElapsedMs} message=${error.message}",
            )
            handleOnlineDetailFailure(request, error.message ?: getString(R.string.ai_inspection_online_detail_parse_failed))
            return
        }
        logAudioPressureSnapshot(
            stage = "handle_online_detail_success:parsed",
            extra = "requestId=${request.requestId} elapsedMs=${SystemClock.elapsedRealtime() - parseStartElapsedMs} title=${resolved.displayTitle} structured=${resolved.hasStructuredFields()} noHazard=${resolved.isOnlineNoHazardResult()} hidNum=${resolved.hidNum} hidLevel=${resolved.hidLevel} lawBasis=${resolved.lawBasis}",
        )
        val pending = pendingAutoHazardPresentation as? PendingAutoHazardPresentation.Online ?: return
        if (pending.requestId != request.requestId) {
            return
        }
        if (!resolved.hasStructuredFields() || resolved.isOnlineNoHazardResult()) {
            Log.i(
                TAG,
                "online detail ignored because result is no hazard requestId=${request.requestId} structured=${resolved.hasStructuredFields()} noHazard=${resolved.isOnlineNoHazardResult()}",
            )
            handleOnlineNoHazardResult(request.requestId)
            return
        }
        val finalResolved = pending.baseResolved?.let { localResolved ->
            resolved.copy(
                source = localResolved.source,
                displayTitle = localResolved.displayTitle,
                jpegBytes = localResolved.jpegBytes.copyOf(),
                localCooldownLabels = localResolved.localCooldownLabels,
            )
        } ?: resolved.copy(localCooldownLabels = pending.cooldownLabels)
        clearPendingAutoHazardPresentation()
        presentOnlineHazardWithSimulatedStream(finalResolved)
    }

    private fun handleOnlineDetailChunk(
        request: OnlineHazardDetectionService.DetailRequest,
        accumulatedText: String,
    ) {
        if (request.epoch != autoInferenceEpoch) {
            return
        }
        val pending = pendingAutoHazardPresentation as? PendingAutoHazardPresentation.Online ?: return
        if (pending.requestId != request.requestId) {
            return
        }
        val normalizedText = accumulatedText.trim()
        if (!pending.firstChunkReceived && normalizedText.isNotEmpty()) {
            logAudioPressureSnapshot(
                stage = "handle_online_detail_chunk:first_chunk",
                extra = "requestId=${request.requestId} textLength=${accumulatedText.length}",
            )
        }
        val updatedPending = pending.copy(
            streamedText = accumulatedText,
            firstChunkReceived = pending.firstChunkReceived || normalizedText.isNotEmpty(),
        )
        pendingAutoHazardPresentation = updatedPending
        refreshInputActions()
    }

    private fun handleOnlineDetailFailure(
        request: OnlineHazardDetectionService.DetailRequest,
        message: String,
    ) {
        if (request.epoch != autoInferenceEpoch) {
            return
        }
        val pending = pendingAutoHazardPresentation as? PendingAutoHazardPresentation.Online ?: return
        if (pending.requestId != request.requestId) {
            return
        }
        AppFileLogger.e(TAG, "online detail failed requestId=${request.requestId} message=$message")
        returnToDetecting()
        SpriteToastUtil.showSpriteToastOld(
            this,
            message.ifBlank { getString(R.string.ai_inspection_online_detail_fetch_failed) },
            R.drawable.ic_warning_triangle,
            LOCAL_SAVE_SUCCESS_TOAST_MS,
            false,
        )
    }

    private fun queueAutoDetectedOnlineHazardPresentation(
        request: OnlineHazardDetectionService.DetectionRequest,
    ) {
        logAudioPressureSnapshot(
            stage = "queue_online_hazard_presentation:start",
            extra = "requestId=${request.requestId} jpegBytes=${request.jpegBytes.size}",
        )
        val jpegBytes = request.jpegBytes
        if (jpegBytes.isEmpty()) {
            Log.w(TAG, "queueAutoDetectedOnlineHazardPresentation missing jpeg requestId=${request.requestId}")
            logAudioPressureSnapshot(
                stage = "queue_online_hazard_presentation:missing_jpeg",
                extra = "requestId=${request.requestId}",
            )
            return
        }
        val detectedAtElapsedMs = SystemClock.elapsedRealtime()
        // JPEG 在生成后按只读数据传递，自动流式展示和详情请求复用同一份数组，避免额外 LOS 拷贝。
        val sharedJpegBytes = jpegBytes
        pendingAutoHazardPresentation = PendingAutoHazardPresentation.Online(
            detectedAtElapsedMs = detectedAtElapsedMs,
            requestId = request.requestId,
            jpegBytes = sharedJpegBytes,
            cooldownLabels = request.cooldownLabels,
        )
        streamingInProgress = true
        logAudioPressureSnapshot(
            stage = "queue_online_hazard_presentation:pending_ready",
            extra = "requestId=${request.requestId} detectedAtElapsedMs=$detectedAtElapsedMs jpegBytes=${sharedJpegBytes.size}",
        )
        refreshPendingHazardAlertOverlay()
        schedulePendingAutoHazardPresentationCheck(detectedAtElapsedMs)
        refreshInputActions()
        onlineHazardDetectionService.requestDeepAnalysis(
            OnlineHazardDetectionService.DetailRequest(
                epoch = autoInferenceEpoch,
                requestId = request.requestId,
                jpegBytes = sharedJpegBytes,
                lane = request.lane,
            ),
        )
        logAudioPressureSnapshot(
            stage = "queue_online_hazard_presentation:details_requested",
            extra = "requestId=${request.requestId}",
        )
    }

    private fun buildPendingLocalHazardPresentation(
        detectedAtElapsedMs: Long,
        resolved: ResolvedHazardContent,
    ): PendingAutoHazardPresentation {
        if (!forceOnlineDetailForLocalHazard) {
            return PendingAutoHazardPresentation.Local(
                detectedAtElapsedMs = detectedAtElapsedMs,
                resolved = resolved,
            )
        }
        val requestId = nextOnlineRequestId()
        val sharedJpegBytes = resolved.jpegBytes.copyOf()
        streamingInProgress = true
        onlineHazardDetectionService.requestDeepAnalysis(
            OnlineHazardDetectionService.DetailRequest(
                epoch = autoInferenceEpoch,
                requestId = requestId,
                jpegBytes = sharedJpegBytes,
            ),
        )
        logAudioPressureSnapshot(
            stage = "queue_local_hazard_presentation:details_requested",
            extra = "requestId=$requestId title=${resolved.displayTitle} jpegBytes=${sharedJpegBytes.size}",
        )
        return PendingAutoHazardPresentation.Online(
            detectedAtElapsedMs = detectedAtElapsedMs,
            requestId = requestId,
            jpegBytes = sharedJpegBytes,
            baseResolved = resolved,
        )
    }

    private fun handleOnlineNoHazardResult(requestId: Long) {
        Log.i(TAG, "online no hazard result wait audio before detecting requestId=$requestId")
        clearPendingAutoHazardPresentation()
        hideStatusAlertOverlay()
        streamCallbackActive = false
        streamingInProgress = false
        pendingStreamStart = false
        manualDeepAnalysisInProgress = false
        stopAutoInferencePipelines("online_no_hazard_wait_audio", clearPendingStreamState = false)
        refreshInputActions()
        OfflineTtsPlayer.play(
            context = this,
            ownerTag = TAG,
            audioResId = R.raw.no_hazard,
            onComplete = {
                uiHandler.post {
                    if (!destroyed) {
                        returnToDetecting()
                    }
                }
            },
            onError = {
                uiHandler.post {
                    if (!destroyed) {
                        returnToDetecting()
                    }
                }
            },
        )
    }

    private fun presentOnlineHazardWithSimulatedStream(result: ResolvedHazardContent) {
        hideStatusAlertOverlay()
        presentResolvedHazardContent(result, simulateTextStream = true)
    }

    private fun presentResolvedHazardContent(
        result: ResolvedHazardContent,
        simulateTextStream: Boolean = false,
    ) {
        logAudioPressureSnapshot(
            stage = "present_resolved_hazard_content:start",
            extra = "source=${result.source} title=${result.displayTitle} jpegBytes=${result.jpegBytes.size} simulateTextStream=$simulateTextStream",
        )
        stopAutoInferencePipelines("present_hazard_result")
        currentManualAnalysisHandle?.cancel()
        currentManualAnalysisHandle = null
        activeStreamRequestId += 1
        streamingInProgress = simulateTextStream
        streamCallbackActive = false
        pendingStreamStart = false
        activeHazardContent = result
        localResultStage = LocalResultStage.DESCRIPTION
        streamPanelAnchoredBelowPreview = simulateTextStream
        localSaveSubmitting = false
        sessionId = ""
        if (result.localCooldownLabels.isNotEmpty() || result.source == HazardSource.LOCAL) {
            val cooldownLabels = if (result.localCooldownLabels.isNotEmpty()) {
                result.localCooldownLabels
            } else {
                result.resolvedHazards()
                    .map { it.displayTitle.trim() }
                    .filter { it.isNotBlank() }
            }
            markLocalLabelsCooldownByName(
                labels = cooldownLabels,
                nowElapsedMs = SystemClock.elapsedRealtime(),
            )
        }
        showPage(PageState.STREAM_RESPONSE)
        clearStreamResponseUiState()
        if (simulateTextStream) {
            OfflineTtsPlayer.play(
                context = this,
                ownerTag = TAG,
                audioResId = R.raw.has_hazard,
            )
        }
        InspectionWorkflowSession.recordCapture(result.jpegBytes)
        if (result.jpegBytes.isNotEmpty()) {
            setStreamThumbnail(result.jpegBytes)
        }
        val descriptionText = result.descriptionPageText()
        if (simulateTextStream) {
            setStreamContentAndResetViewport("")
        } else {
            setStreamContentAndResetViewport(descriptionText)
        }
        lastAnalysisText = descriptionText
        InspectionWorkflowSession.recordDetection(result.displayTitle, descriptionText)
        InspectionWorkflowSession.recordAnalysis(lastAnalysisText)
        if (simulateTextStream) {
            hideActionPrompts()
            layoutStreamResponse.post {
                if (
                    !destroyed &&
                    pageState == PageState.STREAM_RESPONSE &&
                    localResultStage == LocalResultStage.DESCRIPTION &&
                    activeHazardContent === result
                ) {
                    startSimulatedStreamRendering(descriptionText)
                }
            }
        } else {
            renderLocalDescriptionPrompt()
        }
        refreshInputActions()
        logAudioPressureSnapshot(
            stage = "present_resolved_hazard_content:end",
            extra = "source=${result.source} title=${result.displayTitle} localResultStage=$localResultStage",
        )
    }

    private fun schedulePendingAutoHazardPresentationCheck(detectedAtElapsedMs: Long) {
        uiHandler.removeCallbacks(pendingAutoHazardPresentationRunnable)
        val remainingDelayMs = autoHazardPresentationCoordinator.remainingDelayMs(
            detectedAtElapsedMs = detectedAtElapsedMs,
            nowElapsedMs = SystemClock.elapsedRealtime(),
        )
        Log.i(
            TAG,
            "schedule pending hazard presentation remainingDelayMs=$remainingDelayMs detectedAtElapsedMs=$detectedAtElapsedMs",
        )
        uiHandler.postDelayed(pendingAutoHazardPresentationRunnable, remainingDelayMs)
    }

    private fun tryPresentPendingAutoHazard() {
        val pending = pendingAutoHazardPresentation ?: return
        when (pending) {
            is PendingAutoHazardPresentation.Local -> {
                val canPresent = autoHazardPresentationCoordinator.canPresent(
                    detectedAtElapsedMs = pending.detectedAtElapsedMs,
                    isReady = true,
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                )
                if (!canPresent) {
                    Log.i(
                        TAG,
                        "pending local hazard waiting delay detectedAtElapsedMs=${pending.detectedAtElapsedMs}",
                    )
                    schedulePendingAutoHazardPresentationCheck(pending.detectedAtElapsedMs)
                    return
                }
                Log.i(
                    TAG,
                    "present pending hazard now source=${pending.resolved.source} detectedAtElapsedMs=${pending.detectedAtElapsedMs}",
                )
                clearPendingAutoHazardPresentation()
                presentResolvedHazardContent(pending.resolved)
            }

            is PendingAutoHazardPresentation.Online -> {
                val remainingDelayMs = autoHazardPresentationCoordinator.remainingDelayMs(
                    detectedAtElapsedMs = pending.detectedAtElapsedMs,
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                )
                if (remainingDelayMs > 0L) {
                    Log.i(
                        TAG,
                        "pending online hazard waiting delay remainingDelayMs=$remainingDelayMs requestId=${pending.requestId}",
                    )
                    schedulePendingAutoHazardPresentationCheck(pending.detectedAtElapsedMs)
                    return
                }
                pending.resolved?.let { resolved ->
                    Log.i(
                        TAG,
                        "present pending hazard now source=${resolved.source} detectedAtElapsedMs=${pending.detectedAtElapsedMs}",
                    )
                    clearPendingAutoHazardPresentation()
                    presentResolvedHazardContent(resolved)
                    return
                }
                Log.i(
                    TAG,
                    "pending online hazard waiting final detail requestId=${pending.requestId} firstChunkReceived=${pending.firstChunkReceived}",
                )
            }
        }
    }

    /**
     * 自动在线链路到达展示窗口后，只要已经收到首个有效 chunk，就先进入 description 页做原文增量渲染。
     * 最终详情解析成功后，再用标准化 description 页内容覆盖当前流式文本。
     */
    private fun presentPendingOnlineStreamIfReady(pending: PendingAutoHazardPresentation.Online) {
        val remainingDelayMs = autoHazardPresentationCoordinator.remainingDelayMs(
            detectedAtElapsedMs = pending.detectedAtElapsedMs,
            nowElapsedMs = SystemClock.elapsedRealtime(),
        )
        if (remainingDelayMs > 0L || !pending.firstChunkReceived) {
            return
        }
        if (pending.streamPageShown) {
            if (pageState == PageState.STREAM_RESPONSE && pending.streamedText.isNotEmpty()) {
                updateStreamingText(pending.streamedText)
            }
            return
        }
        streamPanelAnchoredBelowPreview = true
        showPage(PageState.STREAM_RESPONSE)
        clearStreamResponseUiState()
        setStreamThumbnail(pending.jpegBytes)
        if (pending.streamedText.isNotEmpty()) {
            updateStreamingText(pending.streamedText)
        }
        hideActionPrompts()
        pendingAutoHazardPresentation = pending.copy(streamPageShown = true)
    }

    private fun playHazardAlertIfNeeded() {
        logAudioPressureSnapshot(
            stage = "play_hazard_alert_if_needed:enter",
            extra = "ttsState=$ttsState",
        )
        if (ttsState != TtsState.IDLE) {
            return
        }
        ttsState = TtsState.PLAYING_ALERT
        val played = OfflineTtsPlayer.play(
            context = this,
            ownerTag = TAG,
            audioResId = R.raw.hazard_alert,
        )
        if (played) {
            ttsState = TtsState.ALERT_PLAYED
        }
    }

    private fun handleStreamConfirmAction() {
        AppFileLogger.i(
            TAG,
            "stream confirm stage=$localResultStage pageState=$pageState streaming=$streamingInProgress submitting=$localSaveSubmitting hasContent=${activeHazardContent != null} scrollY=${scrollContent.scrollY} maxScrollY=${maxStreamScrollY()}",
        )
        when (localResultStage) {
            LocalResultStage.DESCRIPTION -> {
                if (advanceStreamViewportByPage()) {
                    AppFileLogger.i(TAG, "stream confirm consumed by description viewport advance")
                    return
                }
                if (activeHazardContent == null) {
                    AppFileLogger.w(TAG, "stream confirm description without active content, return detecting")
                    returnToDetecting()
                    return
                }
                AppFileLogger.i(TAG, "stream confirm description submit local hazard")
                submitLocalHazardAndShowAdvice()
            }
            LocalResultStage.ADVICE -> {
                if (advanceStreamViewportByPage()) {
                    AppFileLogger.i(TAG, "stream confirm consumed by advice viewport advance")
                    return
                }
                AppFileLogger.i(TAG, "stream confirm advice completed, return detecting")
                returnToDetecting()
            }
            LocalResultStage.NONE -> returnToDetecting()
        }
    }

    private fun handleStreamCancelAction() {
        when (localResultStage) {
            LocalResultStage.DESCRIPTION -> returnToDetecting()
            LocalResultStage.ADVICE -> returnToDetecting()
            LocalResultStage.NONE -> returnToDetecting()
        }
    }

    private fun submitLocalHazardAndShowAdvice() {
        if (localSaveSubmitting) {
            AppFileLogger.i(TAG, "local hazard submit ignored because already submitting")
            return
        }
        val hazardContent = activeHazardContent ?: run {
            AppFileLogger.w(TAG, "local hazard submit skipped because active content is null")
            return
        }
        val hazardCode = hazardContent.primaryHazard()?.hidNum?.trim().orEmpty()
        val shouldRequestSuggestionChecks = hazardCode.isNotBlank()
        val enterprisePayload = InspectionWorkflowSession.enterpriseQrPayload
        val jpegBytes = hazardContent.jpegBytes.takeIf { it.isNotEmpty() }
        val uploadItems = buildLocalHazardUploadItems(hazardContent)
        val failureMessage = when {
            !InspectionFeatureFlags.isEnterpriseInspectionFlowEnabled() -> "企业巡检链路未启用"
            enterprisePayload == null -> "缺少企业巡检信息"
            enterprisePayload.apiBaseUrl.isBlank() -> "缺少上传地址"
            enterprisePayload.authCode.isBlank() -> "缺少授权信息"
            enterprisePayload.objectId.isBlank() -> "缺少对象信息"
            enterprisePayload.userId.isBlank() -> "缺少用户信息"
            jpegBytes == null -> "隐患图片缺失"
            uploadItems.isEmpty() -> "隐患信息缺失"
            else -> null
        }
        AppFileLogger.i(
            TAG,
            "local hazard submit prepare source=${hazardContent.source} title=${hazardContent.displayTitle} hazardCode=$hazardCode jpegBytes=${jpegBytes?.size ?: 0} uploadItems=${uploadItems.size} failure=${failureMessage ?: "none"}",
        )
        localSaveSubmitting = true
        localSaveRequestPending = false
        suggestionChecksRequestPending = shouldRequestSuggestionChecks
        returnToDetectingWhenSubmitIdle = !shouldRequestSuggestionChecks
        refreshInputActions()

        if (failureMessage != null) {
            AppFileLogger.e(TAG, "local hazard submit skipped before sug_checks message=$failureMessage")
        } else {
            val recordKey = buildLocalHazardAutoSaveTaskKey(hazardContent)
            InspectionWorkflowSession.recordSavedHazardAttempt(
                recordKey = recordKey,
                jpegBytes = jpegBytes,
                hazardItems = buildSavedHazardRecordItems(hazardContent),
                saveOutcome = InspectionWorkflowSession.SaveOutcome.PENDING,
            )
            localSaveRequestPending = true
            localHazardUploadHandle?.cancel()
            localHazardUploadHandle = localHazardPushService.pushLocalHazard(
                baseUrl = enterprisePayload!!.apiBaseUrl,
                authCode = enterprisePayload.authCode,
                objectId = enterprisePayload.objectId,
                userId = enterprisePayload.userId,
                customParam = enterprisePayload.extraField,
                jpegBytes = jpegBytes!!,
                hidDanger = uploadItems,
                callback = object : LocalHazardPushService.Callback {
                    override fun onSuccess() {
                        if (destroyed) return
                        AppFileLogger.i(
                            TAG,
                            "local hazard submit success source=${hazardContent.source} stage=$localResultStage title=${hazardContent.displayTitle}",
                        )
                        localHazardUploadHandle = null
                        localSaveRequestPending = false
                        InspectionWorkflowSession.updateSavedHazardAttemptOutcome(
                            recordKey = recordKey,
                            saveOutcome = InspectionWorkflowSession.SaveOutcome.SUCCESS,
                        )
                        pendingUploadSuccessToast = true
                        if (pageState == PageState.DETECTING) {
                            showPendingUploadSuccessToastIfNeeded()
                        }
                        finishAdviceSubmitIfIdle()
                    }

                    override fun onFailure(message: String) {
                        if (destroyed) return
                        AppFileLogger.e(TAG, "local hazard submit failure message=$message")
                        localHazardUploadHandle = null
                        localSaveRequestPending = false
                        InspectionWorkflowSession.updateSavedHazardAttemptOutcome(
                            recordKey = recordKey,
                            saveOutcome = InspectionWorkflowSession.SaveOutcome.FAILED,
                        )
                        finishAdviceSubmitIfIdle()
                    }
                },
            )
        }

        if (shouldRequestSuggestionChecks) {
            requestSuggestionChecksAdvice(hazardContent, hazardCode)
        } else {
            AppFileLogger.w(TAG, "sug_checks skipped because primary hazard code is blank")
            finishAdviceSubmitIfIdle()
        }
    }

    private fun finishAdviceSubmitIfIdle() {
        if (!localSaveRequestPending && !suggestionChecksRequestPending) {
            if (returnToDetectingWhenSubmitIdle) {
                returnToDetectingWhenSubmitIdle = false
                returnToDetecting()
                return
            }
            localSaveSubmitting = false
            refreshInputActions()
        }
    }

    private fun requestSuggestionChecksAdvice(
        hazardContent: ResolvedHazardContent,
        hazardCode: String,
    ) {
        currentSuggestionChecksHandle?.cancel()
        currentSuggestionChecksHandle = aiArSseService.fetchSuggestionChecks(
            hazardCode = hazardCode,
            callback = object : AiArSseService.SuggestionChecksCallback {
                override fun onSuccess(handle: AiArSseService.RequestHandle, content: String) {
                    if (destroyed || currentSuggestionChecksHandle != handle) return
                    AppFileLogger.i(
                        TAG,
                        "sug_checks advice success taskId=${handle.taskId} hazardCode=$hazardCode contentLength=${content.length}",
                    )
                    currentSuggestionChecksHandle = null
                    suggestionChecksRequestPending = false
                    if (content.isBlank()) {
                        AppFileLogger.i(
                            TAG,
                            "sug_checks advice empty, return detecting without cancel local upload taskId=${handle.taskId} hazardCode=$hazardCode",
                        )
                        returnToDetectingAfterEmptySuggestionChecks()
                        return
                    }
                    showSuggestionChecksAdvice(hazardContent, content)
                    finishAdviceSubmitIfIdle()
                }

                override fun onFailure(handle: AiArSseService.RequestHandle, message: String) {
                    if (destroyed || currentSuggestionChecksHandle != handle) return
                    AppFileLogger.e(
                        TAG,
                        "sug_checks advice failure taskId=${handle.taskId} hazardCode=$hazardCode message=$message",
                    )
                    currentSuggestionChecksHandle = null
                    suggestionChecksRequestPending = false
                    returnToDetecting()
                    finishAdviceSubmitIfIdle()
                }
            },
        )
    }

    private fun showSuggestionChecksAdvice(
        hazardContent: ResolvedHazardContent,
        adviceText: String,
    ) {
        if (pageState != PageState.STREAM_RESPONSE || localResultStage != LocalResultStage.DESCRIPTION) {
            AppFileLogger.w(TAG, "sug_checks advice ignored because pageState=$pageState stage=$localResultStage")
            return
        }
        localResultStage = LocalResultStage.ADVICE
        streamPanelAnchoredBelowPreview = true
        streamingInProgress = true
        streamCallbackActive = true
        pendingStreamStart = false
        setStreamContentAndResetViewport("")
        if (ttsState == TtsState.ALERT_PLAYED) {
            ttsState = TtsState.PLAYING_ADVICE
            val played = OfflineTtsPlayer.play(
                context = this,
                ownerTag = TAG,
                audioResId = R.raw.hazard_advice_intro,
            )
            if (played) {
                ttsState = TtsState.DONE
            }
        }
        val descriptionText = hazardContent.displayDescription()
        lastAnalysisText = listOf(descriptionText, adviceText)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n\n")
        InspectionWorkflowSession.recordAnalysis(lastAnalysisText)
        val displayAdviceText = buildAdviceDisplayText(adviceText)
        renderLocalAdvicePrompt()
        hideActionPrompts()
        updateFunctionMenuVisibility()
        refreshInputActions()
        startSimulatedStreamRendering(
            fullText = displayAdviceText,
            targetStage = LocalResultStage.ADVICE,
        )
    }

    private fun buildAdviceDisplayText(adviceText: String): String {
        val trimmedAdvice = adviceText.trim()
        return if (trimmedAdvice.isBlank()) {
            ADVICE_DISPLAY_PREFIX
        } else {
            "$ADVICE_DISPLAY_PREFIX\n$trimmedAdvice"
        }
    }

    private fun buildLocalHazardAutoSaveTaskKey(hazardContent: ResolvedHazardContent): String {
        val hazardKey = hazardContent.resolvedHazards()
            .joinToString(separator = "||") { hazard ->
                listOf(
                    hazard.displayTitle,
                    hazard.description,
                    hazard.advice,
                    hazard.uploadAdvice,
                    hazard.hidNum,
                    hazard.hidLevel,
                    hazard.lawBasis,
                ).joinToString(separator = "|")
            }
        return listOf(
            hazardContent.source.name,
            hazardContent.displayTitle,
            hazardKey,
            hazardContent.jpegBytes.contentHashCode().toString(),
        ).joinToString(separator = "|")
    }

    /**
     * 统一输出语音卡顿排查所需的时序与内存快照，避免埋点日志格式分散。
     */
    private fun logAudioPressureSnapshot(stage: String, extra: String = "") {
        val runtime = Runtime.getRuntime()
        val freeMemory = runtime.freeMemory()
        val totalMemory = runtime.totalMemory()
        val maxMemory = runtime.maxMemory()
        val usedMemory = totalMemory - freeMemory
        val payload = buildString {
            append("audio_diag stage=").append(stage)
            append(" elapsedMs=").append(SystemClock.elapsedRealtime())
            append(" thread=").append(Thread.currentThread().name)
            append(" pageState=").append(pageState)
            append(" captureInProgress=").append(captureInProgress)
            append(" inferenceRunning=").append(inferenceRunning.get())
            append(" itemOnlineFrameSelectionInProgress=").append(itemOnlineLaneRuntime.frameSelectionInProgress)
            append(" itemOnlineActiveRequests=").append(itemOnlineLaneRuntime.activeRequestIds.size)
            append(" sceneOnlineFrameSelectionInProgress=").append(sceneOnlineLaneRuntime.frameSelectionInProgress)
            append(" sceneOnlineActiveRequests=").append(sceneOnlineLaneRuntime.activeRequestIds.size)
            append(" streamingInProgress=").append(streamingInProgress)
            append(" pendingStreamStart=").append(pendingStreamStart)
            append(" heapUsed=").append(usedMemory)
            append(" heapFree=").append(freeMemory)
            append(" heapTotal=").append(totalMemory)
            append(" heapMax=").append(maxMemory)
            if (extra.isNotBlank()) {
                append(" ").append(extra)
            }
        }
        Log.i(TAG, payload)
    }

    private fun showLocalSaveError(message: String) {
        localSaveSubmitting = false
        Log.e(TAG, "local save failed: $message")
        renderLocalDescriptionPrompt()
        hideActionPrompts()
        tvStreamBottomHint.text = message.ifBlank { getString(R.string.ai_inspection_local_save_failed) }
        tvStreamBottomHint.visibility = View.VISIBLE
        updateFunctionMenuVisibility()
        refreshInputActions()
    }

    private fun renderLocalDescriptionGuide() {
        updateFunctionMenuVisibility()
    }

    private fun renderLocalDescriptionPrompt() {
        renderLocalDescriptionGuide()
        tvStreamBottomHint.setText(R.string.ai_inspection_description_bottom_hint)
        tvStreamBottomHint.visibility = View.VISIBLE
        applyCurrentStreamPanelLayout()
    }

    private fun renderLocalAdvicePrompt() {
        tvStreamBottomHint.visibility = View.GONE
        applyCurrentStreamPanelLayout()
        startAdviceCardAnimation()
        updateFunctionMenuVisibility()
    }

    private fun setupFunctionMenus() {
        val menuContent = getString(R.string.inspection_function_menu_content)
        operationGuideDetecting.setMenu(content = menuContent)
        operationGuideStream.setMenu(content = menuContent)
    }

    private fun updateFunctionMenuVisibility() {
        operationGuideDetecting.visibility =
            if (pageState == PageState.DETECTING && !isWearInteractionBlocked()) View.VISIBLE else View.GONE
        operationGuideStream.visibility = View.GONE
    }

    private fun startAdviceCardAnimation() {
        if (localResultStage != LocalResultStage.ADVICE) return
        scrollContent.animate().cancel()
        adviceCardAnimating = true
        hideUploadSuccessToast(immediate = true)
        scrollContent.translationY = resources.getDimensionPixelSize(R.dimen.inspection_advice_card_float_offset).toFloat()
        scrollContent.alpha = 0f
        scrollContent.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(ADVICE_CARD_FLOAT_ANIMATION_MS)
            .withEndAction {
                adviceCardAnimating = false
                showPendingUploadSuccessToastIfNeeded()
            }
            .start()
    }

    private fun showPendingUploadSuccessToastIfNeeded() {
        if (!pendingUploadSuccessToast) return
        if (adviceCardAnimating) return
        pendingUploadSuccessToast = false
        showUploadSuccessToast()
    }

    private fun showUploadSuccessToast() {
        if (adviceCardAnimating) {
            pendingUploadSuccessToast = true
            return
        }
        uiHandler.removeCallbacks(hideUploadSuccessToastRunnable)
        tvUploadSuccessToast.animate().cancel()
        tvUploadSuccessToast.alpha = 1f
        tvUploadSuccessToast.visibility = View.VISIBLE
        uiHandler.postDelayed(hideUploadSuccessToastRunnable, UPLOAD_SUCCESS_TOAST_VISIBLE_MS)
    }

    private fun hideUploadSuccessToast(immediate: Boolean) {
        uiHandler.removeCallbacks(hideUploadSuccessToastRunnable)
        tvUploadSuccessToast.animate().cancel()
        if (immediate) {
            tvUploadSuccessToast.visibility = View.GONE
            tvUploadSuccessToast.alpha = 1f
        }
    }

    private fun hideActionPrompts() {
        tvStreamBottomHint.visibility = View.GONE
    }

    private fun clearLocalHazardResultState(clearPendingUploadToast: Boolean = true) {
        cancelSimulatedStreamRendering()
        clearPendingAutoHazardPresentation()
        streamPanelAnchoredBelowPreview = false
        activeHazardContent = null
        localResultStage = LocalResultStage.NONE
        localSaveSubmitting = false
        localSaveRequestPending = false
        suggestionChecksRequestPending = false
        returnToDetectingWhenSubmitIdle = false
        if (clearPendingUploadToast) {
            pendingUploadSuccessToast = false
        }
        ttsState = TtsState.IDLE
    }

    private fun isAutoHazardPresentationPending(): Boolean {
        return pendingAutoHazardPresentation != null
    }

    private fun clearPendingAutoHazardPresentation() {
        uiHandler.removeCallbacks(pendingAutoHazardPresentationRunnable)
        pendingAutoHazardPresentation = null
        if (ttsState == TtsState.PLAYING_ALERT) {
            ttsState = TtsState.IDLE
        }
        refreshPendingHazardAlertOverlay()
    }

    private fun buildLocalHazardInfoByItem(infos: List<LocalHazardInfo>): Map<String, List<LocalHazardInfo>> {
        val indexed = linkedMapOf<String, MutableList<LocalHazardInfo>>()
        infos.forEach { info ->
            info.item
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { itemName ->
                    indexed.getOrPut(itemName) { mutableListOf() }.add(info)
                }
        }
        indexed.forEach { (itemName, itemInfos) ->
            if (itemInfos.size > 1) {
                Log.i(
                    TAG,
                    "local hazard item key=$itemName variants=${itemInfos.size}",
                )
            }
        }
        return indexed.mapValues { (_, itemInfos) -> itemInfos.toList() }
    }

    private fun loadLocalHazardInfos(): List<LocalHazardInfo> {
        return runCatching {
            assets.open(LOCAL_HAZARD_INFO_ASSET).use { input ->
                InputStreamReader(input, Charsets.UTF_8).use { reader ->
                    val type = object : TypeToken<List<LocalHazardInfo>>() {}.type
                    Gson().fromJson<List<LocalHazardInfo>>(reader, type).orEmpty()
                        .filter { info -> info.item.any { it.trim().isNotBlank() } }
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "load local hazard info failed asset=$LOCAL_HAZARD_INFO_ASSET", error)
        }.getOrDefault(emptyList())
    }

    // ==================== SSE 流式分析相关方法 ====================

    /**
     * 拍照并通过 SSE 接口发送数据
     */
    private fun captureAndSendToSSE() {
        beginStreamingRequest()
        val requestId = activeStreamRequestId
        if (!shouldDeliverStreamRequest(requestId)) {
            return
        }
        try {
            imageEncodeExecutor.execute {
                val payload = buildOnlineDetectionPayloadOrNull(
                    lane = OnlineHazardDetectionService.DetectionLane.ITEM,
                    lastTimestampExclusive = Long.MIN_VALUE,
                )
                if (payload == null) {
                    Log.e(TAG, "当前 SDK 在线选帧失败")
                    handleSSEError(getString(R.string.ai_inspection_online_image_encode_failed))
                    return@execute
                }
                encodePayloadToBase64AndSend(requestId, payload)
            }
        } catch (error: RejectedExecutionException) {
            Log.w(TAG, "image encode task rejected", error)
            handleSSEError(getString(R.string.ai_inspection_online_image_encode_submit_failed))
        }
    }

    /**
     * 请求进入流式分析。
     * 若自动检测正在占用相机，则等待本轮检测完成后重新抓当前最新一帧进入流式。
     * 注意：这里不是复用自动检测周期里的 SquareFramePayload，而是重新从最新原始帧里选帧。
     * 因此手动流式分析链路允许与自动检测链路出现“时间点不同”的图像差异。
     */
    private fun requestStreamingAnalysis() {
        if (streamingInProgress || streamCallbackActive) {
            return
        }
        stopAutoInferencePipelines("request_streaming")
        manualDeepAnalysisInProgress = true
        clearLocalHazardResultState()
        pendingStreamStart = true
        refreshDetectionStatus()
        if (captureInProgress || inferenceRunning.get()) {
            Log.i(
                TAG,
                "stream request queued captureInProgress=$captureInProgress inferenceRunning=${inferenceRunning.get()}",
            )
            return
        }
        startPendingStreamAnalysis()
    }

    /**
     * 在当前检测结束后启动流式分析，开始前重新抓取当前最新一帧。
     */
    private fun startPendingStreamAnalysis(): Boolean {
        if (!pendingStreamStart || destroyed) {
            return false
        }
        if (streamingInProgress || streamCallbackActive) {
            pendingStreamStart = false
            return true
        }
        if (captureInProgress || inferenceRunning.get()) {
            return false
        }
        pendingStreamStart = false
        stopAutoInferencePipelines("start_streaming")
        captureAndSendToSSE()
        return true
    }

    private fun encodePayloadToBase64AndSend(requestId: Long, payload: CapturedFramePayload) {
        runCatching {
            Base64.encodeToString(payload.jpegBytes, Base64.NO_WRAP)
        }.onSuccess { base64Image ->
            uiHandler.post {
                if (!shouldDeliverStreamRequest(requestId)) {
                    return@post
                }
                val frozenJpeg = payload.jpegBytes.copyOf()
                activeHazardContent = ResolvedHazardContent(
                    source = HazardSource.ONLINE,
                    description = "",
                    advice = "",
                    hidLevel = "",
                    hidNum = "",
                    lawBasis = "",
                    displayTitle = getString(R.string.ai_inspection_online_display_title),
                    jpegBytes = frozenJpeg,
                )
                sendImageToAiAr(base64Image)
            }
        }.onFailure { error ->
            Log.e(
                TAG,
                "JPEG Base64 编码失败 width=${payload.width} height=${payload.height} ts=${payload.timestamp}",
                error,
            )
            handleSSEError(getString(R.string.ai_inspection_online_image_encode_failed))
        }
    }

    // ==================== 流式结果缩略图 ====================

    private fun setStreamThumbnail(jpegBytes: ByteArray) {
        val thumbnail = decodeSampledBitmap(
            jpegBytes = jpegBytes,
            targetWidth = STREAM_THUMBNAIL_TARGET_PX,
            targetHeight = STREAM_THUMBNAIL_TARGET_PX,
        ) ?: return
        uiHandler.post {
            layoutStreamThumbnailCard.visibility = View.VISIBLE
            layoutStreamThumbnailPlaceholder.visibility = View.GONE
            ivStreamThumbnail.setImageBitmap(thumbnail)
            ivStreamThumbnail.visibility = View.VISIBLE
            currentStreamThumbnail?.takeIf { !it.isRecycled }?.recycle()
            currentStreamThumbnail = thumbnail
            applyCurrentStreamPanelLayout()
            adjustStreamScrollHeight()
        }
    }

    private fun decodeSampledBitmap(
        jpegBytes: ByteArray,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = calculateInSampleSize(
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
            )
        }
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)
    }

    private fun calculateInSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        var sampleSize = 1
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return sampleSize
        }
        while (sourceWidth / (sampleSize * 2) >= targetWidth &&
            sourceHeight / (sampleSize * 2) >= targetHeight
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /**
     * 动态调整流式结果卡片高度：
     * descrip/advice 固定在结果缩略图下沿向下展开，其他状态维持底部半屏布局。
     */
    private fun adjustStreamScrollHeight() {
        scrollContent.post {
            val contentView = scrollContent.getChildAt(0) ?: return@post
            val maxH = if (isFixedResultPanelMode()) {
                val containerHeight = layoutStreamContentContainer.height
                    .takeIf { it > 0 }
                    ?: layoutStreamResponse.height
                    .takeIf { it > 0 }
                    ?: resources.displayMetrics.heightPixels
                val statusHeight = statusBarStream.height
                    .takeIf { it > 0 }
                    ?: statusBarStream.measuredHeight
                if (localResultStage == LocalResultStage.ADVICE) {
                    val safetySpacing = resources.getDimensionPixelSize(R.dimen.inspection_bottom_prompt_status_spacing)
                    (containerHeight - statusHeight - safetySpacing).coerceAtLeast(0)
                } else {
                    val bottomHintHeight = tvStreamBottomHint.height
                        .takeIf { it > 0 }
                        ?: tvStreamBottomHint.measuredHeight
                    (containerHeight - previewBottomOffsetPx() - bottomHintHeight - statusHeight).coerceAtLeast(0)
                }
            } else {
                resources.displayMetrics.heightPixels / 2
            }
            val desiredHeight = minOf(contentView.height, maxH)
            val params = scrollContent.layoutParams as LinearLayout.LayoutParams
            params.height = desiredHeight
            scrollContent.layoutParams = params
        }
    }

    /**
     * 调试模式：生成一个彩色测试缩略图，验证 UI 布局。
     */
    private fun showDebugThumbnail() {
        val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.parseColor("#00AA00"))
        }
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 20f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.drawText(getString(R.string.ai_inspection_debug_thumbnail_text), 60f, 65f, paint)
        layoutStreamThumbnailCard.visibility = View.VISIBLE
        layoutStreamThumbnailPlaceholder.visibility = View.GONE
        ivStreamThumbnail.setImageBitmap(bitmap)
        ivStreamThumbnail.visibility = View.VISIBLE
        currentStreamThumbnail?.takeIf { !it.isRecycled }?.recycle()
        currentStreamThumbnail = bitmap
        applyCurrentStreamPanelLayout()
        adjustStreamScrollHeight()
    }

    private fun beginStreamingRequest() {
        currentManualAnalysisHandle?.cancel()
        currentManualAnalysisHandle = null
        manualDeepAnalysisInProgress = true
        refreshDetectionStatus()
        activeStreamRequestId += 1
        OfflineTtsPlayer.play(
            context = this,
            ownerTag = TAG,
            audioResId = R.raw.manual_deep,
        )
        streamPanelAnchoredBelowPreview = false
        activeHazardContent = null
        streamingInProgress = true
        streamCallbackActive = true
        hideActionPrompts()
        refreshInputActions()
    }

    private fun shouldDeliverStreamRequest(requestId: Long): Boolean {
        return !destroyed &&
            isActivityResumed &&
            isWorkflowActive &&
            (pageState == PageState.STREAM_RESPONSE || manualDeepAnalysisInProgress) &&
            streamingInProgress &&
            streamCallbackActive &&
            requestId == activeStreamRequestId
    }

    /**
     * 通过在线识别接口发送图像数据。
     */
    private fun sendImageToAiAr(base64Image: String) {
        currentManualAnalysisHandle?.cancel()
        currentManualAnalysisHandle = aiArSseService.requestDeepAnalysis(
            base64Image = base64Image,
            useGmWhenPlaceCodeMissing = true,
            onChunk = { partialText ->
                Log.d(TAG, "manual ai/ar chunk length=${partialText.length}")
            },
            callback = object : AiArSseService.DetailCallback {
                override fun onOpened(handle: AiArSseService.RequestHandle) {
                    Log.d(TAG, "manual ai/ar opened taskId=${handle.taskId}")
                }

                override fun onSuccess(handle: AiArSseService.RequestHandle, fullText: String) {
                    Log.d(TAG, "manual ai/ar closed taskId=${handle.taskId}")
                    uiHandler.post {
                        if (currentManualAnalysisHandle != handle) {
                            return@post
                        }
                        currentManualAnalysisHandle = null
                        handleManualStreamingSuccess(fullText)
                    }
                }

                override fun onFailure(handle: AiArSseService.RequestHandle, message: String) {
                    Log.e(TAG, "manual ai/ar failed taskId=${handle.taskId} message=$message")
                    if (currentManualAnalysisHandle == handle) {
                        currentManualAnalysisHandle = null
                    }
                    handleSSEError(message)
                }
            },
        )
    }

    private fun handleManualStreamingSuccess(fullText: String) {
        streamCallbackActive = false
        streamingInProgress = false
        manualDeepAnalysisInProgress = false
        refreshDetectionStatus()
        val jpegBytes = activeHazardContent?.jpegBytes ?: byteArrayOf()
        val resolved = runCatching {
            AiArHazardDetailParser.parse(
                text = fullText,
                jpegBytes = jpegBytes,
            )
        }.getOrElse { error ->
            handleSSEError(error.message ?: getString(R.string.ai_inspection_online_detail_parse_failed))
            return
        }
        if (!resolved.hasStructuredFields() || resolved.isOnlineNoHazardResult()) {
            Log.i(
                TAG,
                "manual ai/ar ignored because result is no hazard structured=${resolved.hasStructuredFields()} noHazard=${resolved.isOnlineNoHazardResult()} hidNum=${resolved.hidNum} hidLevel=${resolved.hidLevel} lawBasis=${resolved.lawBasis}",
            )
            handleOnlineNoHazardResult(activeStreamRequestId)
            return
        }
        presentOnlineHazardWithSimulatedStream(resolved)
    }

    /**
     * 处理 SSE 错误
     */
    private fun handleSSEError(errorMsg: String) {
        uiHandler.post {
            currentManualAnalysisHandle?.cancel()
            currentManualAnalysisHandle = null
            streamCallbackActive = false
            streamingInProgress = false
            manualDeepAnalysisInProgress = false
            pendingStreamStart = false
            returnToDetecting()
            SpriteToastUtil.showSpriteToastOld(
                this,
                errorMsg.ifBlank { getString(R.string.ai_inspection_online_detail_fetch_failed) },
                R.drawable.ic_warning_triangle,
                LOCAL_SAVE_SUCCESS_TOAST_MS,
                false,
            )
        }
    }
}
