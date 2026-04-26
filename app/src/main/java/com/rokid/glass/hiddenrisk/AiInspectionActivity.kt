package com.rokid.glass.hiddenrisk

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
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
import com.rokid.glass.camera.RokidCameraRecoveryController
import com.rokid.glass.camera.RokidFrameSource
import com.rokid.glass.component.AlertBehavior
import com.rokid.glass.component.AlertStatus
import com.rokid.glass.component.AlertStyle
import com.rokid.glass.component.BottomPromptView
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.component.OperationGuideView
import com.rokid.glass.component.RokidCameraPreviewView
import com.rokid.glass.component.StatusAlertModel
import com.rokid.glass.component.StatusAlertOverlayView
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.utils.BitmapUtils
import com.rokid.glass.utils.OfflineTtsPlayer
import com.rokid.glass.utils.SpriteToastUtil
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
 * 流程：加载初始化 -> 周期抓拍 -> 本地 NCNN + 在线 /ai/ar 并行识别 -> 结果确认/保存。
 */
class AiInspectionActivity : BaseGlassActivity(), RokidSdkManager.Listener {

    private lateinit var layoutLoading: View

    companion object {
        private const val TAG = "AiInspection"
        private const val REQUEST_MEDIA_PERMISSION = 201
        private const val CAPTURE_WARMUP_MS = 1200L
        private const val LOCAL_DETECT_INTERVAL_MS = 1000L
        private const val AUTO_HAZARD_PRESENT_DELAY_MS = 3000L
        private const val LOCAL_LABEL_COOLDOWN_MS = 30_000L
        private const val STREAM_THUMBNAIL_TARGET_PX = 160
        private const val LOCAL_HAZARD_INFO_ASSET = "info.json"
        private const val LOCAL_SAVE_SUCCESS_TOAST_MS = 1500

        private const val BACKEND_GPU = 1
        private const val GPU_PROFILE_BALANCED_FP16 = 1
        private const val DEFAULT_TARGET_INPUT_SIZE = 640
        private const val ENABLE_HIT_CAPTURE_SAVE = false
        private const val STALE_FRAME_THRESHOLD_MS = 1200L
        private const val ONLINE_JPEG_QUALITY = 97
        private const val ONLINE_SELECT_WINDOW_MS = 240L
        private const val ONLINE_SELECT_MAX_FRAMES = 3
        private const val ONLINE_SELECT_POLL_INTERVAL_MS = 80L
        private val AUTO_INFERENCE_MODE = AutoInferenceMode.BOTH
    }

    private enum class AutoInferenceMode {
        LOCAL_ONLY,
        ONLINE_ONLY,
        BOTH,
    }

    /**
     * 页面的可见状态。
     */
    private enum class PageState {
        DETECTING,        // 自动取景识别中
        STREAM_RESPONSE,  // 深度识别隐患，流式回答 + 保存确认
        SYNCING,          // 正在同步手机端
        SYNC_SUCCESS,     // 保存成功
    }

    private data class CapturedFramePayload(
        val jpegBytes: ByteArray,
        val width: Int,
        val height: Int,
        val timestamp: Long,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val cropRect: Rect,
        val sharpnessScore: Double,
    )

    private data class SquareFramePayload(
        val nv21: ByteArray,
        val width: Int,
        val height: Int,
        val timestamp: Long,
        val receivedAtElapsedMs: Long,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val cropRect: Rect,
        val sharpnessScore: Double,
    )

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

    private enum class ScanCycleLocalState {
        DISABLED,
        PENDING,
        DISPLAYABLE,
        NO_RESULT,
    }

    private enum class ScanCycleOnlineState {
        DISABLED,
        PENDING,
        POSITIVE,
        NEGATIVE,
        FAILED,
    }

    private data class ScanCycle(
        val id: Long,
        val epoch: Long,
        val timestamp: Long,
        var jpegBytes: ByteArray? = null,
        var capturePayloadReady: Boolean = false,
        var capturePayloadFailed: Boolean = false,
        var localState: ScanCycleLocalState = ScanCycleLocalState.PENDING,
        var rawLocalMatches: List<LocalHazardMatch> = emptyList(),
        var localMatches: List<LocalHazardMatch> = emptyList(),
        var localResult: ResolvedHazardContent? = null,
        var onlineState: ScanCycleOnlineState = ScanCycleOnlineState.PENDING,
        var onlineRawText: String = "",
        var suppressOnlineByCooldown: Boolean = false,
        var decided: Boolean = false,
    )

    private sealed class PendingAutoHazardPresentation {
        abstract val detectedAtElapsedMs: Long

        data class Local(
            override val detectedAtElapsedMs: Long,
            val resolved: ResolvedHazardContent,
        ) : PendingAutoHazardPresentation()

        data class Online(
            override val detectedAtElapsedMs: Long,
            val cycleId: Long,
            val resolved: ResolvedHazardContent? = null,
        ) : PendingAutoHazardPresentation()
    }

    private val isLocalAutoDetectEnabled: Boolean
        get() = AUTO_INFERENCE_MODE != AutoInferenceMode.ONLINE_ONLY

    private val isOnlineAutoDetectEnabled: Boolean
        get() = AUTO_INFERENCE_MODE != AutoInferenceMode.LOCAL_ONLY

    // --- UI ---
    private lateinit var layoutDetection: FrameLayout
    private lateinit var layoutLivePreviewCard: FrameLayout
    private lateinit var viewLivePreview: RokidCameraPreviewView
    private lateinit var statusAlertOverlay: StatusAlertOverlayView
    private lateinit var layoutStreamResponse: FrameLayout
    private lateinit var layoutStreamContentContainer: LinearLayout
    private lateinit var layoutStreamThumbnailCard: FrameLayout
    private lateinit var streamTopSpacer: View
    private lateinit var streamBottomSpacer: View
    private lateinit var tvStreamContent: TextView
    private lateinit var scrollContent: ScrollView
    private lateinit var ivStreamThumbnail: ImageView
    private lateinit var bottomPromptSync: BottomPromptView
    private lateinit var operationGuideDetecting: OperationGuideView
    private lateinit var operationGuideStream: OperationGuideView
    private var currentStreamThumbnail: Bitmap? = null
    private lateinit var layoutSyncSuccess: FrameLayout
    private lateinit var ivSyncLoading: ImageView
    private lateinit var ivSyncSuccessIcon: ImageView
    private lateinit var tvSyncStatusTitle: TextView
    private lateinit var tvSyncStatusDetail: TextView
    private lateinit var bottomPromptSuccess: BottomPromptView
    // 检测状态UI
    private lateinit var statusBarDetecting: GlassStatusBar
    private lateinit var statusBarStream: GlassStatusBar
    private lateinit var statusBarSyncSuccess: GlassStatusBar
    private lateinit var operationGuideSync: OperationGuideView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val nativeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val imageEncodeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val autoHazardPresentationCoordinator = AutoHazardPresentationCoordinator(
        delayMs = AUTO_HAZARD_PRESENT_DELAY_MS,
    )
    private val inferenceRunning = AtomicBoolean(false)
    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private val motionStabilityTracker by lazy { MotionStabilityTracker(this) }
    private val aiArSseService by lazy { AiArSseService() }
    private val onlineHazardDetectionService by lazy {
        OnlineHazardDetectionService(
            callback = object : OnlineHazardDetectionService.Callback {
                override fun onDetectionResult(
                    request: OnlineHazardDetectionService.DetectionRequest,
                    hasHazard: Boolean,
                    rawText: String,
                ) {
                    handleOnlineDetectionResult(request, hasHazard, rawText)
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

                override fun onDetailSuccess(
                    request: OnlineHazardDetectionService.DetailRequest,
                    fullText: String,
                ) {
                    handleOnlineDetailSuccess(request, fullText)
                }

                override fun onDetailFailure(
                    request: OnlineHazardDetectionService.DetailRequest,
                    message: String,
                ) {
                    handleOnlineDetailFailure(request, message)
                }
            },
        )
    }

    private var hiddenRiskNcnn: HiddenRiskNcnn? = null
    private var destroyed = false
    private var isActivityResumed = false
    private var isWorkflowActive = false
    private var mediaPermissionRequested = false
    private var modelLoading = false
    private var modelLoaded = false
    private var captureInProgress = false
    private var pendingCaptureRequest = false
    private var captureDelayScheduled = false
    private var frameStreamInitializing = false
    private var frameStreamReady = false
    private var frameStreamReadyAtElapsedMs = 0L
    private var sdkReadyAtElapsedMs = 0L
    private var autoCaptureScheduled = false
    private var pageState = PageState.DETECTING
    private var streamingInProgress = false
    private var streamCallbackActive = false
    private var pendingStreamStart = false
    private var activeStreamRequestId = 0L
    private var phoneSyncHandle: RetryRequestHandle? = null
    private var lastAnalysisText = ""
    private var hazardCaptureService: HazardCaptureService? = null
    private var activeHazardContent: ResolvedHazardContent? = null
    private var localResultStage = LocalResultStage.NONE
    private var localSaveSubmitting = false
    private var localHazardAutoSaveTaskKey: String? = null
    private var localHazardAlertTtsPlayed = false
    private var localHazardAdviceTtsPlayed = false
    private var streamAutoScrollLocked = false
    private var streamPanelAnchoredBelowPreview = false
    private var pendingAutoHazardPresentation: PendingAutoHazardPresentation? = null
    private val localLabelCooldownUntilMs = linkedMapOf<String, Long>()
    private val localHazardInfoByItem: Map<String, List<LocalHazardInfo>> by lazy {
        buildLocalHazardInfoByItem(loadLocalHazardInfos())
    }

    private var isMotionStable = false
    private var stableQualifiedAtMillis: Long? = null
    private var lastConsumedFrameTimestamp = 0L
    private var scanCycleEpoch = 0L
    private var nextScanCycleId = 0L
    private val activeScanCycles = linkedMapOf<Long, ScanCycle>()

    // 手动分析流相关
    private var currentManualAnalysisHandle: AiArSseService.RequestHandle? = null
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
                    lastConsumedFrameTimestamp = 0L
                    captureInProgress = false
                    stopLocalDetectionLoop("camera_recovery", clearPendingStreamState = false)
                }

                override fun onRecoverySucceeded() {
                    Log.i(TAG, "camera recovery success")
                    frameStreamReady = true
                    frameStreamReadyAtElapsedMs = SystemClock.elapsedRealtime()
                    lastConsumedFrameTimestamp = 0L
                    if (!destroyed && isActivityResumed && isWorkflowActive && pageState == PageState.DETECTING) {
                        scheduleDetectionCaptureIfNeeded(reason = "camera_recovery", preferImmediate = true)
                    }
                }

                override fun onRecoveryAbandoned(issue: RokidCameraRecoveryController.RecoveryIssue) {
                    Log.e(TAG, "camera recovery abandoned issue=$issue")
                    failWorkflow("相机帧流连续超时，请检查设备")
                }
            },
        )
    }

    // 时间和电量更新
    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            updateCurrentTime()
            uiHandler.postDelayed(this, 1000L) // 每秒更新
        }
    }
    private var batteryReceiver: BroadcastReceiver? = null
    private val motionStabilityListener = object : MotionStabilityTracker.Listener {
        override fun onStabilityChanged(isStable: Boolean, stableSinceMillis: Long?) {
            isMotionStable = isStable
            stableQualifiedAtMillis = stableSinceMillis
            if (isStable) {
                Log.i(TAG, "motion stable qualified stableSinceMillis=$stableSinceMillis")
                scheduleDetectionCaptureIfNeeded(reason = "motion_stable", preferImmediate = true)
            }
            refreshDetectionStatus()
        }
    }

    // 本次拍照上传的会话 ID，用于与 save 接口保持一致的指纹
    private var sessionId = ""

    private val mayHazardLoadingRotateAnimation: RotateAnimation by lazy {
        RotateAnimation(
            0f,
            360f,
            Animation.RELATIVE_TO_SELF,
            0.5f,
            Animation.RELATIVE_TO_SELF,
            0.5f,
        ).apply {
            duration = 900L
            repeatCount = Animation.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    private val captureDelayRunnable = Runnable {
        captureDelayScheduled = false
        if (destroyed || !pendingCaptureRequest || captureInProgress) return@Runnable
        startSampleCaptureIfNeeded()
    }

    private val pendingAutoHazardPresentationRunnable = Runnable {
        tryPresentPendingAutoHazard()
    }

    private val autoCaptureRunnable = Runnable {
        autoCaptureScheduled = false
        if (!shouldAutoCaptureNow()) {
            scheduleAutoCaptureIfNeeded(LOCAL_DETECT_INTERVAL_MS)
            return@Runnable
        }
        pendingCaptureRequest = true
        startSampleCaptureIfNeeded()
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
        layoutStreamResponse = findViewById(R.id.layoutStreamResponse)
        layoutStreamContentContainer = findViewById(R.id.layoutStreamContentContainer)
        layoutStreamThumbnailCard = findViewById(R.id.layoutStreamThumbnailCard)
        streamTopSpacer = findViewById(R.id.streamTopSpacer)
        streamBottomSpacer = findViewById(R.id.streamBottomSpacer)
        tvStreamContent = findViewById(R.id.tvStreamContent)
        scrollContent = findViewById(R.id.scrollContent)
        ivStreamThumbnail = findViewById(R.id.ivStreamThumbnail)
        bottomPromptSync = findViewById(R.id.bottomPromptSync)
        operationGuideDetecting = findViewById(R.id.operationGuideDetecting)
        operationGuideStream = findViewById(R.id.operationGuideStream)
        // 流式结果卡片高度限制在 onMessage / applyDebugSnapshotState 中动态处理
        layoutSyncSuccess = findViewById(R.id.layoutSyncSuccess)
        ivSyncLoading = findViewById(R.id.ivSyncLoading)
        ivSyncSuccessIcon = findViewById(R.id.ivSyncSuccessIcon)
        tvSyncStatusTitle = findViewById(R.id.tvSyncStatusTitle)
        tvSyncStatusDetail = findViewById(R.id.tvSyncStatusDetail)
        bottomPromptSuccess = findViewById(R.id.bottomPromptSuccess)
        // 检测状态 UI 初始化
        statusBarDetecting = findViewById(R.id.statusBarDetecting)
        statusBarStream = findViewById(R.id.statusBarStream)
        statusBarSyncSuccess = findViewById(R.id.statusBarSyncSuccess)
        operationGuideSync = findViewById(R.id.operationGuideSync)

        // 设置检测页操作指引内容
        operationGuideDetecting.setGuide(
            content = getString(R.string.ai_inspection_operation_guide_detecting),
        )
        hideActionPrompts()

        updateConfirmationHints()
        motionStabilityTracker.addListener(motionStabilityListener)

        showPage(PageState.DETECTING)
        applyDefaultDetectionStatus()
        startTimeAndBatteryUpdate()
        debugSnapshotState = intent.getStringExtra("debug_state")
        if (debugSnapshotState != null) {
            applyDebugSnapshotState(debugSnapshotState!!)
            return
        }

        // 从 InspectionSession 获取已初始化的对象
        hiddenRiskNcnn = InspectionSession.hiddenRiskNcnn
        frameStreamReady = InspectionSession.isFrameStreamReady
        if (frameStreamReady) {
            frameStreamReadyAtElapsedMs = SystemClock.elapsedRealtime()
        }

        // 注册 SDK 监听（用于语音命令）
        RokidSdkManager.addListener(this)

        // 检查初始化状态，如果未初始化则返回
        if (!InspectionSession.isInitialized || hiddenRiskNcnn == null) {
            Log.e(TAG, "InspectionSession 未初始化，返回加载页面")
            startActivity(Intent(this, InspectionLoadingActivity::class.java))
            finish()
            return
        }

        // 直接使用已初始化的对象开始检测
        modelLoaded = true
        startDetectionImmediately()
    }

    /**
     * 立即开始检测（对象已预初始化）
     */
    private fun startDetectionImmediately() {
        pendingCaptureRequest = false
        cameraRecoveryController.resetRecoveryAttempts()
        initFrameStreamAndTransition()
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        inputSession.attach()
        if (debugSnapshotState != null) {
            refreshInputActions()
            return
        }
        motionStabilityTracker.start()
        refreshInputActions()
        if (pageState == PageState.DETECTING) {
            cameraRecoveryController.setRecoveryEnabled(true)
            initFrameStreamAndTransition()
        }
    }

    override fun onStart() {
        super.onStart()
        isWorkflowActive = true
        refreshInputActions()
    }

    override fun onPause() {
        isActivityResumed = false
        inputSession.detach()
        if (debugSnapshotState != null) {
            super.onPause()
            return
        }
        motionStabilityTracker.stop()
        cameraRecoveryController.setRecoveryEnabled(false)
        cameraRecoveryController.notifyConsumerWaitStopped()
        stopDetectionPreview()
        stopLocalDetectionLoop("onPause")
        hideStatusAlertOverlay()
        // 关闭当前 SSE 连接
        currentManualAnalysisHandle?.cancel()
        currentManualAnalysisHandle = null
        frameStreamInitializing = false
        frameStreamReady = false
        frameStreamReadyAtElapsedMs = 0L
        lastConsumedFrameTimestamp = 0L
        cameraRecoveryController.stop()
        InspectionSession.stopFrameStream()
        super.onPause()
    }

    override fun onStop() {
        isWorkflowActive = false
        if (debugSnapshotState != null) {
            super.onStop()
            return
        }
        stopLocalDetectionLoop("onStop")
        hideStatusAlertOverlay()
        // 关闭当前 SSE 连接
        currentManualAnalysisHandle?.cancel()
        currentManualAnalysisHandle = null
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        streamCallbackActive = false
        phoneSyncHandle?.cancel()
        phoneSyncHandle = null
        inputSession.release()
        ivSyncLoading.clearAnimation()
        if (debugSnapshotState != null) {
            stopTimeAndBatteryUpdate()
            super.onDestroy()
            return
        }
        motionStabilityTracker.removeListener(motionStabilityListener)
        motionStabilityTracker.stop()
        stopLocalDetectionLoop("onDestroy")
        hideStatusAlertOverlay()
        stopDetectionPreview()
        frameStreamInitializing = false
        frameStreamReady = false
        frameStreamReadyAtElapsedMs = 0L
        lastConsumedFrameTimestamp = 0L
        cameraRecoveryController.setRecoveryEnabled(false)
        cameraRecoveryController.notifyConsumerWaitStopped()
        cameraRecoveryController.stop()
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
        // 停止时间和电量更新
        stopTimeAndBatteryUpdate()
        super.onDestroy()
    }

    // ==================== 输入事件 ====================

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
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

        val local = ensureNativeEngine() ?: run {
            failWorkflow("原生引擎不可用")
            return
        }

        if (!modelLoaded) {
            startModelLoadIfNeeded(local)
            return
        }

        if (pendingCaptureRequest) {
            startSampleCaptureIfNeeded()
            return
        }

        if (!captureInProgress && !inferenceRunning.get() && pageState == PageState.DETECTING) {
            initFrameStreamAndTransition()
        }
    }

    private fun startModelLoadIfNeeded(local: HiddenRiskNcnn) {
        if (modelLoading) return

        modelLoading = true

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
                    if (success) {
                        modelLoaded = true
                        initFrameStreamAndTransition()
                    } else {
                        failWorkflow("模型加载失败")
                    }
                }
            }) {
            modelLoading = false
            failWorkflow("模型任务提交失败")
        }
    }

    private fun initFrameStreamAndTransition() {
        if (frameStreamReady && !RokidFrameSource.isCroppedFrameStreamWarm()) {
            frameStreamReady = false
            frameStreamReadyAtElapsedMs = 0L
        }
        if (frameStreamReady) {
            transitionToDetection()
            return
        }
        if (frameStreamInitializing) return

        frameStreamInitializing = true
        cameraRecoveryController.startOrReuse { success ->
            uiHandler.post {
                frameStreamInitializing = false
                frameStreamReady = success
                frameStreamReadyAtElapsedMs = if (success) SystemClock.elapsedRealtime() else 0L
                if (destroyed) {
                    cameraRecoveryController.stop()
                    InspectionSession.stopFrameStream()
                    return@post
                }
                if (!isActivityResumed || !isWorkflowActive) {
                    frameStreamReady = false
                    frameStreamReadyAtElapsedMs = 0L
                    cameraRecoveryController.stop()
                    InspectionSession.stopFrameStream()
                    return@post
                }
                if (!success) {
                    failWorkflow("相机帧流初始化失败")
                    return@post
                }
                Log.i(
                    TAG,
                    "frame stream ready pending=$pendingCaptureRequest pageState=$pageState"
                )
                if (pageState == PageState.DETECTING || pageState == PageState.STREAM_RESPONSE) {
                    startDetectionPreviewIfNeeded()
                }
                if (pendingCaptureRequest) {
                    startSampleCaptureIfNeeded()
                } else if (pageState == PageState.DETECTING) {
                    scheduleDetectionCaptureIfNeeded(reason = "frame_stream_ready", preferImmediate = true)
                }
                refreshInputActions()
            }
        }
    }

    private fun transitionToDetection() {
        pendingCaptureRequest = false
        scheduleDetectionCaptureIfNeeded(reason = "transition_to_detection", preferImmediate = true)
        refreshDetectionStatus()
    }

    private fun returnToDetecting() {
        phoneSyncHandle?.cancel()
        phoneSyncHandle = null
        currentManualAnalysisHandle?.cancel()
        currentManualAnalysisHandle = null
        streamCallbackActive = false
        streamingInProgress = false
        pendingStreamStart = false
        clearPendingAutoHazardPresentation()
        invalidateActiveScanCycles()
        clearLocalHazardResultState()
        activeStreamRequestId++
        hideStatusAlertOverlay()
        cameraRecoveryController.resetRecoveryAttempts()
        showPage(PageState.DETECTING)
        applyDefaultDetectionStatus()
        initFrameStreamAndTransition()
    }

    private fun returnDirectlyToHome() {
        phoneSyncHandle?.cancel()
        phoneSyncHandle = null
        currentManualAnalysisHandle?.cancel()
        currentManualAnalysisHandle = null
        streamCallbackActive = false
        streamingInProgress = false
        pendingStreamStart = false
        clearPendingAutoHazardPresentation()
        invalidateActiveScanCycles()
        clearLocalHazardResultState()
        activeStreamRequestId++
        stopLocalDetectionLoop("return_home")
        localLabelCooldownUntilMs.clear()
        hideStatusAlertOverlay()
        refreshInputActions()
        InspectionWorkflowSession.clearForNewInspection()
        startActivity(Intent(this, AiInspectionMenuActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun finishInspectionWithReport() {
        phoneSyncHandle?.cancel()
        phoneSyncHandle = null
        currentManualAnalysisHandle?.cancel()
        currentManualAnalysisHandle = null
        streamCallbackActive = false
        streamingInProgress = false
        pendingStreamStart = false
        clearPendingAutoHazardPresentation()
        invalidateActiveScanCycles()
        activeStreamRequestId++
        stopLocalDetectionLoop("finish_inspection")
        localLabelCooldownUntilMs.clear()
        hideStatusAlertOverlay()
        refreshInputActions()
        InspectionWorkflowSession.recordAnalysis(lastAnalysisText, sessionId)
        startActivity(Intent(this, InspectionEndReportActivity::class.java))
        finish()
    }

    // ==================== 拍照与推理 ====================

    private fun startSampleCaptureIfNeeded() {
        if (!pendingCaptureRequest || captureInProgress) return
        if (!isActivityResumed || !isWorkflowActive) return
        if (pageState != PageState.DETECTING) return
        if (pendingStreamStart) return

        if (frameStreamReady && !RokidFrameSource.isCroppedFrameStreamWarm()) {
            frameStreamReady = false
            frameStreamReadyAtElapsedMs = 0L
        }

        if (!frameStreamReady) {
            initFrameStreamAndTransition()
            return
        }

        val readyElapsedMs = frameStreamReadyAtElapsedMs.takeIf { it > 0L } ?: sdkReadyAtElapsedMs
        val warmupRemainingMs = when {
            readyElapsedMs <= 0L -> CAPTURE_WARMUP_MS
            else -> (CAPTURE_WARMUP_MS - (SystemClock.elapsedRealtime() - readyElapsedMs)).coerceAtLeast(
                0L
            )
        }
        if (warmupRemainingMs > 0L) {
            uiHandler.removeCallbacks(captureDelayRunnable)
            captureDelayScheduled = true
            uiHandler.postDelayed(captureDelayRunnable, warmupRemainingMs)
            return
        }

        cameraRecoveryController.notifyConsumerWaitStarted()
        pendingCaptureRequest = false
        captureInProgress = true
        val captureRequestStartMs = SystemClock.elapsedRealtime()

        val frame = copyFrameForDetectionOrNull()
        captureInProgress = false

        if (frame == null) {
            Log.w(
                TAG,
                "copyFrameForDetectionOrNull failed elapsed=${SystemClock.elapsedRealtime() - captureRequestStartMs}ms warm=${RokidFrameSource.isFrameStreamWarm()}",
            )
            if (startPendingStreamAnalysis()) {
                return
            }
            scheduleAutoCaptureIfNeeded(LOCAL_DETECT_INTERVAL_MS)
            return
        }

        Log.i(
            TAG,
            "copyFrameForDetectionOrNull submitted baseline=${frame.width}x${frame.height} source=${frame.sourceWidth}x${frame.sourceHeight} timestamp=${frame.timestamp} elapsed=${SystemClock.elapsedRealtime() - captureRequestStartMs}ms warm=${RokidFrameSource.isFrameStreamWarm()}",
        )
        triggerInference(frame)
    }

    private fun triggerInference(frame: SquareFramePayload) {
        val cycleId = ++nextScanCycleId
        val cycle = ScanCycle(
            id = cycleId,
            epoch = scanCycleEpoch,
            timestamp = frame.timestamp,
            localState = if (isLocalAutoDetectEnabled) {
                ScanCycleLocalState.PENDING
            } else {
                ScanCycleLocalState.DISABLED
            },
            onlineState = if (isOnlineAutoDetectEnabled) {
                ScanCycleOnlineState.PENDING
            } else {
                ScanCycleOnlineState.DISABLED
            },
        )
        activeScanCycles[cycleId] = cycle
        prepareCapturedPayloadForCycle(cycle, frame)
        if (isOnlineAutoDetectEnabled && !isLocalAutoDetectEnabled) {
            scheduleAutoCaptureIfNeeded(LOCAL_DETECT_INTERVAL_MS)
        }
        if (!isLocalAutoDetectEnabled) {
            evaluateScanCycle(cycle)
            return
        }

        val local = hiddenRiskNcnn ?: run {
            handleLocalInferenceCompleted(
                cycleId = cycleId,
                success = false,
                snapshot = null,
                localMatches = emptyList(),
            )
            scheduleAutoCaptureIfNeeded(LOCAL_DETECT_INTERVAL_MS)
            return
        }
        if (!inferenceRunning.compareAndSet(false, true)) {
            cycle.localState = ScanCycleLocalState.NO_RESULT
            evaluateScanCycle(cycle)
            if (!isOnlineAutoDetectEnabled) {
                scheduleAutoCaptureIfNeeded(LOCAL_DETECT_INTERVAL_MS)
            }
            return
        }
        val localInput = BitmapUtils.resizeSquareNv21(
            nv21 = frame.nv21,
            width = frame.width,
            height = frame.height,
            targetSize = DEFAULT_TARGET_INPUT_SIZE,
        ) ?: run {
            inferenceRunning.set(false)
            handleLocalInferenceCompleted(
                cycleId = cycleId,
                success = false,
                snapshot = null,
                localMatches = emptyList(),
            )
            if (startPendingStreamAnalysis()) {
                return
            }
            scheduleAutoCaptureIfNeeded(LOCAL_DETECT_INTERVAL_MS)
            return
        }

        if (!submitNativeTask {
                val nativeStartElapsedMs = SystemClock.elapsedRealtime()
                val success = runCatching {
                    local.submitNv21(
                        localInput,
                        DEFAULT_TARGET_INPUT_SIZE,
                        DEFAULT_TARGET_INPUT_SIZE,
                    )
                }.onFailure { e -> Log.e(TAG, "submitNv21 failed", e) }
                    .getOrDefault(false)
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
                    Log.d(
                        TAG,
                        "inference success=$success detectionCount=$detectionCount nativeElapsedMs=$nativeElapsedMs inferenceMs=$inferenceMs"
                    )
                    if (destroyed || cycle.epoch != scanCycleEpoch || pageState != PageState.DETECTING) {
                        activeScanCycles.remove(cycleId)
                        return@post
                    }
                    handleLocalInferenceCompleted(
                        cycleId = cycleId,
                        success = success,
                        snapshot = snapshot,
                        localMatches = localMatches,
                    )
                }
            }) {
            inferenceRunning.set(false)
            handleLocalInferenceCompleted(
                cycleId = cycleId,
                success = false,
                snapshot = null,
                localMatches = emptyList(),
            )
            if (startPendingStreamAnalysis()) {
                return
            }
            scheduleAutoCaptureIfNeeded(LOCAL_DETECT_INTERVAL_MS)
        }
    }

    private fun copyFrameForDetectionOrNull(): SquareFramePayload? {
        if (!frameStreamReady || !RokidFrameSource.isFrameStreamWarm()) {
            return null
        }
        val rawFrame = RokidFrameSource.copyLatestRawFrame() ?: return null
        val frame = buildSquareFramePayload(rawFrame) ?: return null
        if (frame.timestamp <= lastConsumedFrameTimestamp) {
            Log.w(TAG, "drop frame reason=duplicate timestamp=${frame.timestamp} last=$lastConsumedFrameTimestamp")
            return null
        }
        val ageMs = SystemClock.elapsedRealtime() - frame.receivedAtElapsedMs
        if (ageMs > STALE_FRAME_THRESHOLD_MS) {
            Log.w(TAG, "drop frame reason=stale timestamp=${frame.timestamp} ageMs=$ageMs")
            return null
        }
        cameraRecoveryController.reportFrameConsumed(frame.timestamp)
        cameraRecoveryController.notifyConsumerWaitStopped()
        lastConsumedFrameTimestamp = frame.timestamp
        return frame
    }

    private fun copyLatestRawFrameForOnlineOrNull(): RokidFrameSource.Nv21Frame? {
        if (!frameStreamReady || !RokidFrameSource.isFrameStreamWarm()) {
            return null
        }
        val frame = RokidFrameSource.copyLatestRawFrame() ?: return null
        val ageMs = SystemClock.elapsedRealtime() - frame.receivedAtElapsedMs
        if (ageMs > STALE_FRAME_THRESHOLD_MS) {
            Log.w(TAG, "drop online raw frame reason=stale timestamp=${frame.timestamp} ageMs=$ageMs")
            return null
        }
        return frame
    }

    private fun buildSquareFramePayload(frame: RokidFrameSource.Nv21Frame): SquareFramePayload? {
        val cropRect = RokidFrameSource.calculateSquareCropRect(frame.width, frame.height)
        if (cropRect.width() <= 0 || cropRect.height() <= 0) {
            Log.w(TAG, "buildSquareFramePayload invalid crop timestamp=${frame.timestamp} crop=$cropRect")
            return null
        }
        val squareNv21 = BitmapUtils.cropNv21Rect(
            nv21 = frame.data,
            width = frame.width,
            height = frame.height,
            cropRect = cropRect,
        ) ?: return null
        return SquareFramePayload(
            nv21 = squareNv21,
            width = cropRect.width(),
            height = cropRect.height(),
            timestamp = frame.timestamp,
            receivedAtElapsedMs = frame.receivedAtElapsedMs,
            sourceWidth = frame.width,
            sourceHeight = frame.height,
            cropRect = Rect(cropRect),
            sharpnessScore = computeSquareFrameSharpnessScore(
                squareNv21,
                cropRect.width(),
                cropRect.height(),
            ),
        )
    }

    private fun buildCapturedFramePayload(frame: SquareFramePayload): CapturedFramePayload? {
        val jpegBytes = BitmapUtils.encodeNv21CropRectToJpeg(
            nv21 = frame.nv21,
            width = frame.width,
            height = frame.height,
            cropRect = Rect(0, 0, frame.width, frame.height),
            jpegQuality = ONLINE_JPEG_QUALITY,
        ) ?: return null
        return CapturedFramePayload(
            jpegBytes = jpegBytes,
            width = frame.width,
            height = frame.height,
            timestamp = frame.timestamp,
            sourceWidth = frame.sourceWidth,
            sourceHeight = frame.sourceHeight,
            cropRect = Rect(frame.cropRect),
            sharpnessScore = frame.sharpnessScore,
        )
    }

    private fun buildOnlineFramePayload(frame: RokidFrameSource.Nv21Frame): CapturedFramePayload? {
        val squareFrame = buildSquareFramePayload(frame) ?: return null
        return buildCapturedFramePayload(squareFrame)
    }

    private fun selectBestOnlineFramePayload(): CapturedFramePayload? {
        val deadline = SystemClock.elapsedRealtime() + ONLINE_SELECT_WINDOW_MS
        var bestPayload: CapturedFramePayload? = null
        var lastTimestamp = Long.MIN_VALUE
        var sampledFrames = 0
        while (sampledFrames < ONLINE_SELECT_MAX_FRAMES) {
            val frame = copyLatestRawFrameForOnlineOrNull()
            if (frame == null || frame.timestamp <= lastTimestamp) {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    break
                }
                SystemClock.sleep(ONLINE_SELECT_POLL_INTERVAL_MS)
                continue
            }
            lastTimestamp = frame.timestamp
            val payload = buildOnlineFramePayload(frame)
            if (payload != null) {
                sampledFrames += 1
                if (bestPayload == null ||
                    payload.sharpnessScore > bestPayload.sharpnessScore ||
                    (payload.sharpnessScore == bestPayload.sharpnessScore && payload.timestamp > bestPayload.timestamp)
                ) {
                    bestPayload = payload
                }
            }
            if (sampledFrames >= ONLINE_SELECT_MAX_FRAMES || SystemClock.elapsedRealtime() >= deadline) {
                break
            }
            SystemClock.sleep(ONLINE_SELECT_POLL_INTERVAL_MS)
        }
        bestPayload?.let { payload ->
            Log.i(
                TAG,
                "selected online frame ts=${payload.timestamp} sharpness=${"%.2f".format(payload.sharpnessScore)} crop=${payload.cropRect} output=${payload.width}x${payload.height} bytes=${payload.jpegBytes.size}",
            )
        }
        return bestPayload
    }

    private fun computeSquareFrameSharpnessScore(
        nv21: ByteArray,
        width: Int,
        height: Int,
    ): Double {
        if (width <= 2 || height <= 2) {
            return 0.0
        }
        val stride = 4
        var score = 0.0
        var samples = 0
        var y = stride
        while (y < height - stride) {
            var x = stride
            while (x < width - stride) {
                val center = nv21[y * width + x].toInt() and 0xFF
                val leftPx = nv21[y * width + (x - stride)].toInt() and 0xFF
                val rightPx = nv21[y * width + (x + stride)].toInt() and 0xFF
                val topPx = nv21[(y - stride) * width + x].toInt() and 0xFF
                val bottomPx = nv21[(y + stride) * width + x].toInt() and 0xFF
                val laplacian = kotlin.math.abs(leftPx + rightPx + topPx + bottomPx - 4 * center)
                score += laplacian.toDouble()
                samples += 1
                x += stride
            }
            y += stride
        }
        return if (samples == 0) 0.0 else score / samples
    }

    // ==================== 隐患处理流程 ====================

    private fun hideStatusAlertOverlay() {
        statusAlertOverlay.reset()
    }

    private fun refreshPendingHazardAlertOverlay() {
        if (pageState != PageState.DETECTING || pendingAutoHazardPresentation == null) {
            hideStatusAlertOverlay()
            return
        }
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
    }

    private fun syncToPhone() {
        if (pageState != PageState.STREAM_RESPONSE || streamingInProgress) {
            return
        }
        showSyncing()
        phoneSyncHandle = InspectionSyncService.syncAnalysisToPhone(
            sessionId = sessionId,
            callback = object : InspectionSyncService.Callback {
                override fun onSuccess() {
                    uiHandler.post {
                        if (destroyed) return@post
                        phoneSyncHandle = null
                        InspectionWorkflowSession.recordPhoneSync(sessionId)
                        InspectionWorkflowSession.recordSavedHazardCapture(activeHazardContent?.jpegBytes)
                        InspectionWorkflowSession.updateSummary { summary ->
                            summary.copy(
                                hasHazardCount = summary.hasHazardCount + (activeHazardContent?.hazardCount() ?: 0),
                            )
                        }
                        showSyncSuccess()
                    }
                }

                override fun onError(message: String) {
                    uiHandler.post {
                        if (destroyed) return@post
                        phoneSyncHandle = null
                        Log.e(TAG, "sync failed: $message")
                        showSyncError(message)
                    }
                }
            },
        )
    }

    private fun showSyncing() {
        showPage(PageState.SYNCING)
    }

    private fun showSyncSuccess() {
        showPage(PageState.SYNC_SUCCESS)
    }

    private fun showSyncError(message: String) {
        showPage(PageState.STREAM_RESPONSE)
        bottomPromptSync.setPrompt(
            title = getString(R.string.ai_inspection_sync_failed),
            subtitle = syncPromptSubtitle(),
        )
        bottomPromptSync.visibility = View.GONE
        if (message.isNotBlank()) {
            Log.w(TAG, "sync error prompt message=$message")
        }
        hideActionPrompts()
    }

    private fun applyDefaultDetectionStatus() {
        // 检测页不再显示状态监测文案，内部状态仅用于自动抓拍和自动分析调度。
    }

    // ==================== 自动拍摄调度 ====================

    private fun shouldAutoCaptureNow(): Boolean {
        if (destroyed || !isActivityResumed || !isWorkflowActive) return false
        if (isAutoHazardPresentationPending()) return false
        if (!hasRequiredPermissions()) return false
        if (RokidSdkManager.state != RokidSdkManager.SdkState.READY) return false
        if (!modelLoaded || modelLoading) return false
        if (pendingStreamStart || streamingInProgress || streamCallbackActive) return false
        if (captureInProgress || captureDelayScheduled || frameStreamInitializing || pendingCaptureRequest) return false
        if (inferenceRunning.get()) return false
        if (pageState != PageState.DETECTING) return false
        if (!isMotionStable || stableQualifiedAtMillis == null) return false
        return true
    }

    private fun scheduleAutoCaptureIfNeeded(delayMs: Long) {
        if (autoCaptureScheduled || destroyed || !isActivityResumed || !isWorkflowActive) return
        if (isAutoHazardPresentationPending()) return
        if (pendingStreamStart || streamingInProgress || streamCallbackActive) return
        if (pageState != PageState.DETECTING) return
        autoCaptureScheduled = true
        uiHandler.postDelayed(autoCaptureRunnable, delayMs.coerceAtLeast(0L))
    }

    private fun scheduleDetectionCaptureIfNeeded(reason: String, preferImmediate: Boolean) {
        if (isAutoHazardPresentationPending()) {
            return
        }
        if (preferImmediate && requestImmediateDetectionCaptureIfPossible(reason)) {
            return
        }
        scheduleAutoCaptureIfNeeded(LOCAL_DETECT_INTERVAL_MS)
    }

    private fun requestImmediateDetectionCaptureIfPossible(reason: String): Boolean {
        if (!shouldAutoCaptureNow()) {
            return false
        }
        Log.i(TAG, "request immediate local detect reason=$reason")
        autoCaptureScheduled = false
        uiHandler.removeCallbacks(autoCaptureRunnable)
        pendingCaptureRequest = true
        startSampleCaptureIfNeeded()
        return true
    }

    private fun stopLocalDetectionLoop(reason: String, clearPendingStreamState: Boolean = true) {
        Log.i(TAG, "stop local detection loop reason=$reason")
        pendingCaptureRequest = false
        captureDelayScheduled = false
        autoCaptureScheduled = false
        clearPendingAutoHazardPresentation()
        uiHandler.removeCallbacks(captureDelayRunnable)
        uiHandler.removeCallbacks(autoCaptureRunnable)
        cameraRecoveryController.notifyConsumerWaitStopped()
        invalidateActiveScanCycles()
        if (clearPendingStreamState) {
            pendingStreamStart = false
        }
    }

    private fun refreshDetectionStatus() {
        // 检测状态仅保留内部状态机，不再向检测页渲染文案。
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
            "start left-top live preview pageState=$pageState previewStarted=${viewLivePreview.isPreviewStarted()}",
        )
        layoutLivePreviewCard.visibility = View.VISIBLE
        viewLivePreview.visibility = View.VISIBLE
        viewLivePreview.post {
            if (!isActivityResumed || destroyed) {
                Log.i(
                    TAG,
                    "skip posted left-top live preview resumed=$isActivityResumed destroyed=$destroyed pageState=$pageState",
                )
                return@post
            }
            if (pageState != PageState.DETECTING && pageState != PageState.STREAM_RESPONSE) {
                Log.i(TAG, "skip posted left-top live preview pageState=$pageState")
                return@post
            }
            if (!frameStreamReady || !RokidFrameSource.isFrameStreamOpen()) {
                Log.i(
                    TAG,
                    "skip posted left-top live preview frameStreamReady=$frameStreamReady frameStreamOpen=${RokidFrameSource.isFrameStreamOpen()} pageState=$pageState",
                )
                return@post
            }
            viewLivePreview.startPreview { success ->
                if (!success) {
                    Log.w(TAG, "left-top live preview start failed")
                } else {
                    Log.i(TAG, "left-top live preview ready pageState=$pageState")
                }
            }
        }
    }

    private fun stopDetectionPreview() {
        Log.i(
            TAG,
            "stop left-top live preview pageState=$pageState previewStarted=${viewLivePreview.isPreviewStarted()}",
        )
        layoutLivePreviewCard.visibility = View.INVISIBLE
        viewLivePreview.visibility = View.INVISIBLE
        viewLivePreview.stopPreview()
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
        layoutSyncSuccess.visibility =
            if (state == PageState.SYNCING || state == PageState.SYNC_SUCCESS) View.VISIBLE else View.GONE
        bottomPromptSuccess.visibility = View.GONE
        val shouldShowLivePreview =
            state == PageState.DETECTING || state == PageState.STREAM_RESPONSE
        Log.i(
            TAG,
            "showPage state=$state shouldShowLivePreview=$shouldShowLivePreview resumed=$isActivityResumed workflowActive=$isWorkflowActive",
        )
        if (shouldShowLivePreview) {
            startDetectionPreviewIfNeeded()
        } else {
            stopDetectionPreview()
        }
        if (state != PageState.DETECTING) {
            hideStatusAlertOverlay()
        }
        if (state == PageState.DETECTING) {
            clearStreamResponseUiState()
            refreshDetectionStatus()
            refreshPendingHazardAlertOverlay()
        }
        if (state == PageState.SYNCING || state == PageState.SYNC_SUCCESS) {
            renderSyncStatusUi(state)
        } else {
            ivSyncLoading.clearAnimation()
            ivSyncLoading.visibility = View.GONE
            ivSyncSuccessIcon.visibility = View.VISIBLE
            operationGuideSync.visibility = View.GONE
        }
        hideActionPrompts()
        refreshInputActions()
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
                showPage(PageState.SYNC_SUCCESS)
                bottomPromptSuccess.setPrompt(
                    title = getString(R.string.ai_inspection_continue_prompt)
                )
                hideActionPrompts()
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
                enabled = { pageState == PageState.DETECTING && !isAutoHazardPresentationPending() },
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
                enabled = { pageState == PageState.DETECTING && !isAutoHazardPresentationPending() },
            ) {
                returnDirectlyToHome()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_detecting_finish"),
                label = getString(R.string.ai_inspection_input_label_detecting_finish),
                triggers = listOf(
                    voiceTrigger(R.string.ai_inspection_voice_finish, "jie shu"),
                    voiceTrigger(R.string.ai_inspection_voice_finish_patrol, "jie shu xun cha"),
                    voiceTrigger(R.string.ai_inspection_voice_finish_detect, "jie shu shi huan"),
                ),
                enabled = { pageState == PageState.DETECTING && !isAutoHazardPresentationPending() },
            ) {
                finishInspectionWithReport()
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
                handleStreamConfirmAction()
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
                        !localSaveSubmitting &&
                        localResultStage == LocalResultStage.ADVICE
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
                        !localSaveSubmitting &&
                        localResultStage == LocalResultStage.ADVICE
                },
            ) {
                returnToDetecting()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Confirm,
                label = getString(R.string.ai_inspection_input_label_confirm),
                triggers = buildConfirmTriggers(),
                enabled = { pageState == PageState.SYNC_SUCCESS },
            ) {
                returnToDetecting()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Cancel,
                label = getString(R.string.ai_inspection_input_label_return),
                triggers = buildReturnTriggers(),
                enabled = { pageState == PageState.SYNC_SUCCESS },
            ) {
                finishInspectionWithReport()
            },
        )
    }

    private fun refreshInputActions() {
        inputSession.updateActions(buildInputActions())
    }

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
        val streamGuide = getString(R.string.ai_inspection_operation_guide_confirm_return)
        operationGuideStream.setContent(streamGuide)
        operationGuideSync.setGuide(content = streamGuide)
        bottomPromptSync.setPrompt(
            title = getString(R.string.ai_inspection_sync_prompt),
            subtitle = syncPromptSubtitle(),
        )
        bottomPromptSuccess.setPrompt(
            title = getString(R.string.ai_inspection_continue_prompt),
            subtitle = continuePromptSubtitle(),
        )
        hideActionPrompts()
    }

    private fun syncPromptSubtitle(): String {
        return getString(R.string.ai_inspection_sync_hint)
    }

    private fun localDescriptionPromptSubtitle(): String {
        return getString(R.string.ai_inspection_local_save_hint)
    }

    private fun localContinuePromptSubtitle(): String {
        return getString(R.string.ai_inspection_local_continue_hint)
    }

    private fun continuePromptSubtitle(): String {
        return getString(R.string.ai_inspection_continue_hint)
    }

    private fun isFixedResultPanelMode(): Boolean {
        val hasLocalResult =
            localResultStage == LocalResultStage.DESCRIPTION || localResultStage == LocalResultStage.ADVICE
        return pageState == PageState.STREAM_RESPONSE &&
            (streamPanelAnchoredBelowPreview ||
                (!streamingInProgress && hasLocalResult))
    }

    private fun previewBottomOffsetPx(): Int {
        return resources.getDimensionPixelSize(R.dimen.inspection_preview_card_margin_top) +
            resources.getDimensionPixelSize(R.dimen.inspection_preview_card_size)
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
        topParams.height = previewBottomOffsetPx()
        topParams.weight = 0f
        streamTopSpacer.layoutParams = topParams
        streamTopSpacer.visibility = View.VISIBLE

        val bottomParams = streamBottomSpacer.layoutParams as LinearLayout.LayoutParams
        bottomParams.height = 0
        bottomParams.weight = 1f
        streamBottomSpacer.layoutParams = bottomParams
        streamBottomSpacer.visibility = View.VISIBLE
    }

    private fun applyCurrentStreamPanelLayout() {
        if (isFixedResultPanelMode()) {
            applyFixedResultStreamPanelLayout()
        } else {
            applyDefaultStreamPanelLayout()
        }
    }

    private fun clearStreamThumbnailState() {
        layoutStreamThumbnailCard.visibility = View.GONE
        ivStreamThumbnail.setImageBitmap(null)
        ivStreamThumbnail.visibility = View.GONE
        currentStreamThumbnail?.takeIf { !it.isRecycled }?.recycle()
        currentStreamThumbnail = null
    }

    private fun clearStreamResponseUiState() {
        clearStreamThumbnailState()
        streamAutoScrollLocked = false
        tvStreamContent.text = ""
        applyDefaultStreamPanelLayout()
        bottomPromptSync.visibility = View.GONE
        bottomPromptSync.setPrompt(
            title = getString(R.string.ai_inspection_sync_prompt),
            subtitle = syncPromptSubtitle(),
        )
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
        tvStreamContent.text = text
        applyCurrentStreamPanelLayout()
        adjustStreamScrollHeight()
        scrollContent.post {
            scrollContent.scrollTo(0, 0)
            scrollContent.fullScroll(View.FOCUS_UP)
        }
    }

    private fun updateStreamingText(partialText: String) {
        val previousScrollY = scrollContent.scrollY
        tvStreamContent.text = partialText
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

    private fun renderSyncStatusUi(state: PageState) {
        layoutSyncSuccess.alpha = 1f
        bottomPromptSuccess.alpha = 1f
        when (state) {
            PageState.SYNCING -> {
                ivSyncLoading.visibility = View.VISIBLE
                ivSyncLoading.startAnimation(mayHazardLoadingRotateAnimation)
                ivSyncSuccessIcon.visibility = View.GONE
                tvSyncStatusTitle.setText(R.string.ai_inspection_syncing)
                tvSyncStatusDetail.setText(R.string.ai_inspection_syncing_detail)
                operationGuideSync.visibility = View.GONE
            }

            PageState.SYNC_SUCCESS -> {
                ivSyncLoading.clearAnimation()
                ivSyncLoading.visibility = View.GONE
                ivSyncSuccessIcon.visibility = View.VISIBLE
                tvSyncStatusTitle.setText(R.string.ai_inspection_synced)
                tvSyncStatusDetail.setText(R.string.ai_inspection_synced_detail)
                operationGuideSync.visibility = View.GONE
            }

            else -> {
                ivSyncLoading.clearAnimation()
                ivSyncLoading.visibility = View.GONE
                ivSyncSuccessIcon.visibility = View.GONE
                operationGuideSync.visibility = View.GONE
            }
        }
        hideActionPrompts()
    }

    private fun failWorkflow(message: String) {
        Log.e(TAG, "workflow failed: $message")
        // 简化错误处理，仅记录日志，不显示错误页面
        // 因为加载页面已剥离到 InspectionLoadingActivity
    }

    // ==================== 时间和电量更新 ====================

    /**
     * 启动时间和电量更新
     */
    private fun startTimeAndBatteryUpdate() {
        // 启动时间更新
        uiHandler.post(timeUpdateRunnable)

        // 注册电量广播接收器
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                updateBatteryLevel(intent)
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)
    }

    /**
     * 停止时间和电量更新
     */
    private fun stopTimeAndBatteryUpdate() {
        uiHandler.removeCallbacks(timeUpdateRunnable)
        batteryReceiver?.let {
            unregisterReceiver(it)
            batteryReceiver = null
        }
    }

    /**
     * 更新当前时间显示
     */
    private fun updateCurrentTime() {
        statusBarDetecting.updateTime()
        statusBarStream.updateTime()
        statusBarSyncSuccess.updateTime()
    }

    /**
     * 更新电量显示
     */
    private fun updateBatteryLevel(intent: Intent?) {
        intent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level != -1 && scale != -1) {
                val batteryPct = (level * 100 / scale.toFloat()).toInt()
                statusBarDetecting.setBatteryPercent(batteryPct)
                statusBarStream.setBatteryPercent(batteryPct)
                statusBarSyncSuccess.setBatteryPercent(batteryPct)
            }
        }
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
        if (localMatches.isEmpty()) {
            return null
        }
        val resolvedHazards = localMatches.map { it.toResolvedItem() }
        val primaryHazard = resolvedHazards.first()
        return ResolvedHazardContent(
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
    }

    private fun buildLocalHazardUploadItems(hazardContent: ResolvedHazardContent): List<LocalHazardPushService.HidDangerItem> {
        return LocalHazardUploadItemBuilder.build(hazardContent)
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

    private fun prepareCapturedPayloadForCycle(cycle: ScanCycle, frame: SquareFramePayload) {
        try {
            imageEncodeExecutor.execute {
                val payload = buildCapturedFramePayload(frame)
                uiHandler.post {
                    if (!isScanCycleActive(cycle.id, cycle.epoch)) {
                        return@post
                    }
                    if (payload == null) {
                        val activeCycle = activeScanCycles[cycle.id] ?: return@post
                        activeCycle.capturePayloadFailed = true
                        if (isOnlineAutoDetectEnabled) {
                            activeCycle.onlineState = ScanCycleOnlineState.FAILED
                        }
                        if (activeCycle.localMatches.isNotEmpty() && activeCycle.localResult == null) {
                            activeCycle.localResult = buildLocalResolvedContent(
                                localMatches = activeCycle.localMatches,
                                jpegBytes = ByteArray(0),
                            )
                        }
                        evaluateScanCycle(activeCycle)
                        return@post
                    }
                    cycle.capturePayloadReady = true
                    cycle.jpegBytes = payload.jpegBytes
                    if (cycle.localMatches.isNotEmpty() && cycle.localResult == null) {
                        cycle.localResult = buildLocalResolvedContent(
                            localMatches = cycle.localMatches,
                            jpegBytes = payload.jpegBytes,
                        )
                    }
                    if (isOnlineAutoDetectEnabled) {
                        onlineHazardDetectionService.submitDetection(
                            OnlineHazardDetectionService.DetectionRequest(
                                epoch = cycle.epoch,
                                cycleId = cycle.id,
                                jpegBytes = payload.jpegBytes,
                            ),
                        )
                    }
                    evaluateScanCycle(cycle)
                }
            }
        } catch (error: RejectedExecutionException) {
            Log.w(TAG, "prepareCapturedPayloadForCycle encode rejected cycleId=${cycle.id}", error)
            cycle.capturePayloadFailed = true
            if (isOnlineAutoDetectEnabled) {
                cycle.onlineState = ScanCycleOnlineState.FAILED
            }
            if (cycle.localMatches.isNotEmpty() && cycle.localResult == null) {
                cycle.localResult = buildLocalResolvedContent(
                    localMatches = cycle.localMatches,
                    jpegBytes = ByteArray(0),
                )
            }
            evaluateScanCycle(cycle)
        }
    }

    private fun handleLocalInferenceCompleted(
        cycleId: Long,
        success: Boolean,
        snapshot: NativeInferenceStats?,
        localMatches: List<LocalHazardMatch>,
    ) {
        val cycle = activeScanCycles[cycleId] ?: run {
            scheduleAutoCaptureIfNeeded(LOCAL_DETECT_INTERVAL_MS)
            return
        }
        if (cycle.epoch != scanCycleEpoch || pageState != PageState.DETECTING) {
            activeScanCycles.remove(cycleId)
            scheduleAutoCaptureIfNeeded(LOCAL_DETECT_INTERVAL_MS)
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

        cycle.rawLocalMatches = localMatches
        if (!success) {
            cycle.localState = ScanCycleLocalState.NO_RESULT
            cycle.localMatches = emptyList()
            cycle.suppressOnlineByCooldown = false
        } else if (localMatches.isNotEmpty()) {
            val filteredLocalMatches = filterLocalMatchesByCooldown(
                localMatches = localMatches,
                nowElapsedMs = SystemClock.elapsedRealtime(),
            )
            cycle.localMatches = filteredLocalMatches
            cycle.suppressOnlineByCooldown = filteredLocalMatches.isEmpty()
            cycle.localState = ScanCycleLocalState.DISPLAYABLE
            cycle.localResult = when {
                filteredLocalMatches.isEmpty() -> null
                cycle.capturePayloadReady -> cycle.jpegBytes?.let { jpegBytes ->
                    buildLocalResolvedContent(
                        localMatches = filteredLocalMatches,
                        jpegBytes = jpegBytes,
                    )
                }

                cycle.capturePayloadFailed -> buildLocalResolvedContent(
                    localMatches = filteredLocalMatches,
                    jpegBytes = ByteArray(0),
                )

                else -> null
            }
            if (filteredLocalMatches.isEmpty()) {
                cycle.localState = ScanCycleLocalState.NO_RESULT
            }
        } else {
            cycle.localState = ScanCycleLocalState.NO_RESULT
            cycle.localMatches = emptyList()
            cycle.suppressOnlineByCooldown = false
        }

        if (success && ENABLE_HIT_CAPTURE_SAVE && detectionCount > 0) {
            cycle.jpegBytes?.let { jpegBytes ->
                ensureHazardCaptureService().saveHazardCapture(jpegBytes, snapshot)
            }
        }

        evaluateScanCycle(cycle)
        if (pageState == PageState.DETECTING &&
            !cycle.decided &&
            cycle.localState != ScanCycleLocalState.DISPLAYABLE &&
            cycle.onlineState != ScanCycleOnlineState.POSITIVE
        ) {
            scheduleAutoCaptureIfNeeded(LOCAL_DETECT_INTERVAL_MS)
        }
    }

    private fun handleOnlineDetectionResult(
        request: OnlineHazardDetectionService.DetectionRequest,
        hasHazard: Boolean,
        rawText: String,
    ) {
        val cycle = activeScanCycles[request.cycleId] ?: return
        if (cycle.epoch != request.epoch || cycle.epoch != scanCycleEpoch) {
            return
        }
        cycle.onlineState = if (hasHazard) {
            ScanCycleOnlineState.POSITIVE
        } else {
            ScanCycleOnlineState.NEGATIVE
        }
        cycle.onlineRawText = rawText
        evaluateScanCycle(cycle)
    }

    private fun handleOnlineDetectionFailure(
        request: OnlineHazardDetectionService.DetectionRequest,
        message: String,
    ) {
        val cycle = activeScanCycles[request.cycleId] ?: return
        if (cycle.epoch != request.epoch || cycle.epoch != scanCycleEpoch) {
            return
        }
        Log.w(TAG, "online detect failed cycleId=${request.cycleId} message=$message")
        cycle.onlineState = ScanCycleOnlineState.FAILED
        evaluateScanCycle(cycle)
    }

    private fun handleOnlineDetectionDropped(
        request: OnlineHazardDetectionService.DetectionRequest,
        reason: String,
    ) {
        val cycle = activeScanCycles[request.cycleId] ?: return
        if (cycle.epoch != request.epoch || cycle.epoch != scanCycleEpoch) {
            return
        }
        Log.i(TAG, "online detect dropped cycleId=${request.cycleId} reason=$reason")
        cycle.onlineState = ScanCycleOnlineState.FAILED
        evaluateScanCycle(cycle)
    }

    private fun handleOnlineDetailSuccess(
        request: OnlineHazardDetectionService.DetailRequest,
        fullText: String,
    ) {
        if (request.epoch != scanCycleEpoch) {
            return
        }
        val jpegBytes = request.jpegBytes
        val resolved = runCatching {
            AiArHazardDetailParser.parse(
                text = fullText,
                jpegBytes = jpegBytes,
            )
        }.getOrElse { error ->
            handleOnlineDetailFailure(request, error.message ?: getString(R.string.ai_inspection_online_detail_parse_failed))
            return
        }
        val pending = pendingAutoHazardPresentation as? PendingAutoHazardPresentation.Online ?: return
        if (pending.cycleId != request.cycleId) {
            return
        }
        pendingAutoHazardPresentation = pending.copy(resolved = resolved)
        schedulePendingAutoHazardPresentationCheck(pending.detectedAtElapsedMs)
        tryPresentPendingAutoHazard()
        refreshInputActions()
    }

    private fun handleOnlineDetailFailure(
        request: OnlineHazardDetectionService.DetailRequest,
        message: String,
    ) {
        if (request.epoch != scanCycleEpoch) {
            return
        }
        val pending = pendingAutoHazardPresentation as? PendingAutoHazardPresentation.Online ?: return
        if (pending.cycleId != request.cycleId) {
            return
        }
        Log.e(TAG, "online detail failed cycleId=${request.cycleId} message=$message")
        returnToDetecting()
        SpriteToastUtil.showSpriteToastOld(
            this,
            message.ifBlank { getString(R.string.ai_inspection_online_detail_fetch_failed) },
            R.drawable.ic_warning_triangle,
            LOCAL_SAVE_SUCCESS_TOAST_MS,
            false,
        )
    }

    private fun evaluateScanCycle(cycle: ScanCycle) {
        if (cycle.decided || cycle.epoch != scanCycleEpoch || pageState != PageState.DETECTING) {
            return
        }
        if (cycle.localState == ScanCycleLocalState.DISPLAYABLE) {
            val resolved = cycle.localResult ?: return
            cycle.decided = true
            activeScanCycles.remove(cycle.id)
            queueAutoDetectedLocalHazardPresentation(resolved)
            return
        }
        if (cycle.onlineState == ScanCycleOnlineState.POSITIVE) {
            if (cycle.suppressOnlineByCooldown) {
                cycle.decided = true
                activeScanCycles.remove(cycle.id)
                return
            }
            val jpegBytes = cycle.jpegBytes
            if (jpegBytes == null || jpegBytes.isEmpty()) {
                return
            }
            cycle.decided = true
            activeScanCycles.remove(cycle.id)
            queueAutoDetectedOnlineHazardPresentation(cycle)
            return
        }

        val localTerminal = when (cycle.localState) {
            ScanCycleLocalState.DISABLED,
            ScanCycleLocalState.NO_RESULT,
            ScanCycleLocalState.DISPLAYABLE -> true

            ScanCycleLocalState.PENDING -> false
        }
        val onlineTerminal = when (cycle.onlineState) {
            ScanCycleOnlineState.DISABLED,
            ScanCycleOnlineState.NEGATIVE,
            ScanCycleOnlineState.FAILED,
            ScanCycleOnlineState.POSITIVE -> true

            ScanCycleOnlineState.PENDING -> false
        }
        if (localTerminal && onlineTerminal) {
            activeScanCycles.remove(cycle.id)
        }
    }

    private fun queueAutoDetectedOnlineHazardPresentation(cycle: ScanCycle) {
        val jpegBytes = cycle.jpegBytes
        if (jpegBytes == null || jpegBytes.isEmpty()) {
            Log.w(TAG, "queueAutoDetectedOnlineHazardPresentation missing jpeg cycleId=${cycle.id}")
            scheduleAutoCaptureIfNeeded(LOCAL_DETECT_INTERVAL_MS)
            return
        }
        stopLocalDetectionLoop("accept_online_hazard_result")
        val detectedAtElapsedMs = SystemClock.elapsedRealtime()
        pendingAutoHazardPresentation = PendingAutoHazardPresentation.Online(
            detectedAtElapsedMs = detectedAtElapsedMs,
            cycleId = cycle.id,
        )
        refreshPendingHazardAlertOverlay()
        schedulePendingAutoHazardPresentationCheck(detectedAtElapsedMs)
        refreshInputActions()
        onlineHazardDetectionService.fetchHazardDetails(
            OnlineHazardDetectionService.DetailRequest(
                epoch = scanCycleEpoch,
                cycleId = cycle.id,
                jpegBytes = jpegBytes.copyOf(),
            ),
        )
    }

    private fun presentResolvedHazardContent(result: ResolvedHazardContent) {
        stopLocalDetectionLoop("present_hazard_result")
        playHazardAlertIfNeeded()
        currentManualAnalysisHandle?.cancel()
        currentManualAnalysisHandle = null
        activeStreamRequestId += 1
        streamingInProgress = false
        streamCallbackActive = false
        pendingStreamStart = false
        activeHazardContent = result
        localResultStage = LocalResultStage.DESCRIPTION
        localSaveSubmitting = false
        sessionId = ""
        if (result.source == HazardSource.LOCAL) {
            val cooldownLabels = result.localCooldownLabels.ifEmpty {
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
        InspectionWorkflowSession.recordCapture(result.jpegBytes)
        if (result.jpegBytes.isNotEmpty()) {
            setStreamThumbnail(result.jpegBytes)
        }
        val descriptionText = result.descriptionPageText()
        setStreamContentAndResetViewport(descriptionText)
        lastAnalysisText = descriptionText
        InspectionWorkflowSession.recordDetection(result.displayTitle, descriptionText)
        InspectionWorkflowSession.recordAnalysis(lastAnalysisText)
        renderLocalDescriptionPrompt()
        if (!result.isOnlineNoHazardResult()) {
            scheduleBackgroundLocalHazardSaveIfNeeded(result)
        }
        refreshInputActions()
    }

    private fun queueAutoDetectedLocalHazardPresentation(result: ResolvedHazardContent) {
        stopLocalDetectionLoop("accept_local_hazard_result")
        val detectedAtElapsedMs = SystemClock.elapsedRealtime()
        pendingAutoHazardPresentation = PendingAutoHazardPresentation.Local(
            detectedAtElapsedMs = detectedAtElapsedMs,
            resolved = result,
        )
        refreshPendingHazardAlertOverlay()
        schedulePendingAutoHazardPresentationCheck(detectedAtElapsedMs)
        refreshInputActions()
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
        val readyResult = when (pending) {
            is PendingAutoHazardPresentation.Local -> pending.resolved
            is PendingAutoHazardPresentation.Online -> pending.resolved
        }
        val canPresent = autoHazardPresentationCoordinator.canPresent(
            detectedAtElapsedMs = pending.detectedAtElapsedMs,
            isReady = readyResult != null,
            nowElapsedMs = SystemClock.elapsedRealtime(),
        )
        if (!canPresent || readyResult == null) {
            Log.i(
                TAG,
                "pending hazard not ready for presentation canPresent=$canPresent ready=${readyResult != null} detectedAtElapsedMs=${pending.detectedAtElapsedMs}",
            )
            schedulePendingAutoHazardPresentationCheck(pending.detectedAtElapsedMs)
            return
        }
        Log.i(
            TAG,
            "present pending hazard now source=${readyResult.source} detectedAtElapsedMs=${pending.detectedAtElapsedMs}",
        )
        clearPendingAutoHazardPresentation()
        presentResolvedHazardContent(readyResult)
    }

    private fun playHazardAlertIfNeeded() {
        if (!localHazardAlertTtsPlayed) {
            localHazardAlertTtsPlayed = OfflineTtsPlayer.speak(
                ownerTag = TAG,
                message = getString(R.string.offline_tts_hazard_alert),
            )
        }
    }

    private fun handleStreamConfirmAction() {
        when (localResultStage) {
            LocalResultStage.DESCRIPTION -> {
                if (advanceStreamViewportByPage()) {
                    return
                }
                if (activeHazardContent == null) {
                    returnToDetecting()
                    return
                }
                advanceToLocalHazardAdvice()
            }
            LocalResultStage.ADVICE -> {
                if (advanceStreamViewportByPage()) {
                    return
                }
                returnToDetecting()
            }
            LocalResultStage.NONE -> syncToPhone()
        }
    }

    private fun handleStreamCancelAction() {
        when (localResultStage) {
            LocalResultStage.DESCRIPTION -> returnToDetecting()
            LocalResultStage.ADVICE -> returnToDetecting()
            LocalResultStage.NONE -> returnToDetecting()
        }
    }

    private fun advanceToLocalHazardAdvice() {
        val hazardContent = activeHazardContent ?: return
        if (hazardContent.isOnlineNoHazardResult()) {
            returnToDetecting()
            return
        }
        if (hazardContent.source == HazardSource.ONLINE) {
            requestOnlineHazardAdvice(hazardContent)
            return
        }
        showLocalHazardAdvice(showSaveSuccessToast = false, countAsSaved = false)
    }

    private fun scheduleBackgroundLocalHazardSaveIfNeeded(hazardContent: ResolvedHazardContent) {
        val taskKey = buildLocalHazardAutoSaveTaskKey(hazardContent)
        if (localHazardAutoSaveTaskKey == taskKey) {
            return
        }
        localHazardAutoSaveTaskKey = taskKey
        val enterprisePayload = InspectionWorkflowSession.enterpriseQrPayload
        val capturedJpeg = hazardContent.jpegBytes.takeIf { it.isNotEmpty() }
        val taskId = when {
            enterprisePayload == null -> {
                Log.w(TAG, "skip local hazard background upload: missing enterprise payload")
                null
            }

            enterprisePayload.apiBaseUrl.isBlank() -> {
                Log.w(TAG, "skip local hazard background upload: blank api base url")
                null
            }

            enterprisePayload.authCode.isBlank() -> {
                Log.w(TAG, "skip local hazard background upload: blank auth code")
                null
            }

            enterprisePayload.objectId.isBlank() -> {
                Log.w(TAG, "skip local hazard background upload: blank object id")
                null
            }

            enterprisePayload.userId.isBlank() -> {
                Log.w(TAG, "skip local hazard background upload: blank user id")
                null
            }

            capturedJpeg == null -> {
                Log.w(TAG, "skip local hazard background upload: missing jpeg")
                null
            }

            else -> {
                InspectionBackgroundUploadQueue.enqueueLocalHazardSave(
                    taskKey = taskKey,
                    baseUrl = enterprisePayload.apiBaseUrl,
                    authCode = enterprisePayload.authCode,
                    objectId = enterprisePayload.objectId,
                    userId = enterprisePayload.userId,
                    customParam = enterprisePayload.extraField,
                    jpegBytes = capturedJpeg,
                    hidDanger = buildLocalHazardUploadItems(hazardContent),
                )
            }
        }
        if (taskId.isNullOrBlank()) {
            return
        }
        InspectionBackgroundUploadService.start(this, taskId)
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

    private fun showLocalHazardAdvice(showSaveSuccessToast: Boolean, countAsSaved: Boolean) {
        val hazardContent = activeHazardContent ?: return
        if (localResultStage != LocalResultStage.DESCRIPTION) return
        localSaveSubmitting = false
        localResultStage = LocalResultStage.ADVICE
        if (!localHazardAdviceTtsPlayed) {
            localHazardAdviceTtsPlayed = OfflineTtsPlayer.speak(
                ownerTag = TAG,
                message = getString(R.string.offline_tts_hazard_advice_intro),
            )
        }
        val descriptionText = hazardContent.displayDescription()
        val adviceText = hazardContent.displayAdvice()
        setStreamContentAndResetViewport(adviceText)
        lastAnalysisText = listOf(descriptionText, adviceText)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n\n")
        InspectionWorkflowSession.recordAnalysis(lastAnalysisText)
        if (countAsSaved) {
            InspectionWorkflowSession.recordSavedHazardCapture(hazardContent.jpegBytes)
            InspectionWorkflowSession.updateSummary { summary ->
                summary.copy(hasHazardCount = summary.hasHazardCount + hazardContent.hazardCount())
            }
        }
        if (showSaveSuccessToast) {
            SpriteToastUtil.showSpriteToastOld(
                this,
                getString(R.string.ai_inspection_local_save_success),
                R.drawable.ic_check_circle,
                LOCAL_SAVE_SUCCESS_TOAST_MS,
                false,
            )
        }
        renderLocalAdvicePrompt()
        refreshInputActions()
    }

    private fun requestOnlineHazardAdvice(hazardContent: ResolvedHazardContent) {
        if (localResultStage != LocalResultStage.DESCRIPTION) return
        val sourceText = hazardContent.rawDetailText.trim()
            .ifBlank { hazardContent.displayDescription().trim() }
        if (sourceText.isBlank()) {
            showLocalHazardAdvice(showSaveSuccessToast = false, countAsSaved = false)
            return
        }
        currentManualAnalysisHandle?.cancel()
        currentManualAnalysisHandle = null
        activeStreamRequestId += 1
        val requestId = activeStreamRequestId
        localSaveSubmitting = false
        localResultStage = LocalResultStage.ADVICE
        // 在线 advice 文字流也沿用固定结果布局，避免先半屏再下沉。
        streamPanelAnchoredBelowPreview = true
        streamingInProgress = true
        streamCallbackActive = true
        pendingStreamStart = false
        setStreamContentAndResetViewport(getString(R.string.ai_inspection_online_fetching_advice))
        hideActionPrompts()
        refreshInputActions()
        if (!localHazardAdviceTtsPlayed) {
            localHazardAdviceTtsPlayed = OfflineTtsPlayer.speak(
                ownerTag = TAG,
                message = getString(R.string.offline_tts_hazard_advice_intro),
            )
        }
        currentManualAnalysisHandle = aiArSseService.fetchHazardAdvice(
            text = sourceText,
            onChunk = { partialText ->
                Log.d(TAG, "online advice chunk length=${partialText.length}")
                uiHandler.post {
                    if (!shouldDeliverStreamRequest(requestId)) {
                        return@post
                    }
                    updateStreamingText(OnlineHazardAdviceFormatter.format(partialText))
                }
            },
            callback = object : AiArSseService.DetailCallback {
                override fun onOpened(handle: AiArSseService.RequestHandle) {
                    Log.d(TAG, "online advice opened taskId=${handle.taskId}")
                }

                override fun onSuccess(handle: AiArSseService.RequestHandle, fullText: String) {
                    Log.d(TAG, "online advice closed taskId=${handle.taskId}")
                    uiHandler.post {
                        if (currentManualAnalysisHandle != handle || requestId != activeStreamRequestId) {
                            return@post
                        }
                        currentManualAnalysisHandle = null
                        handleOnlineAdviceSuccess(
                            hazardContent = hazardContent,
                            adviceText = fullText,
                        )
                    }
                }

                override fun onFailure(handle: AiArSseService.RequestHandle, message: String) {
                    Log.e(TAG, "online advice failed taskId=${handle.taskId} message=$message")
                    uiHandler.post {
                        if (currentManualAnalysisHandle == handle) {
                            currentManualAnalysisHandle = null
                        }
                        handleOnlineAdviceFailure(message)
                    }
                }
            },
        )
    }

    private fun handleOnlineAdviceSuccess(
        hazardContent: ResolvedHazardContent,
        adviceText: String,
    ) {
        streamCallbackActive = false
        streamingInProgress = false
        val displayAdviceText = OnlineHazardAdviceFormatter.format(adviceText)
            .ifBlank { hazardContent.displayAdvice() }
        setStreamContentAndResetViewport(displayAdviceText)
        val descriptionText = hazardContent.displayDescription()
        lastAnalysisText = listOf(descriptionText, displayAdviceText)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n\n")
        InspectionWorkflowSession.recordAnalysis(lastAnalysisText)
        renderLocalAdvicePrompt()
        refreshInputActions()
    }

    private fun handleOnlineAdviceFailure(message: String) {
        streamCallbackActive = false
        streamingInProgress = false
        pendingStreamStart = false
        localResultStage = LocalResultStage.DESCRIPTION
        val fallback = activeHazardContent?.displayDescription().orEmpty()
        if (fallback.isNotBlank()) {
            setStreamContentAndResetViewport(fallback)
        }
        renderLocalDescriptionPrompt()
        refreshInputActions()
        SpriteToastUtil.showSpriteToastOld(
            this,
            message.ifBlank { getString(R.string.ai_inspection_online_advice_fetch_failed) },
            R.drawable.ic_warning_triangle,
            LOCAL_SAVE_SUCCESS_TOAST_MS,
            false,
        )
    }

    private fun showLocalSaveError(message: String) {
        localSaveSubmitting = false
        Log.e(TAG, "local save failed: $message")
        renderLocalDescriptionPrompt()
        bottomPromptSync.setPrompt(
            title = message.ifBlank { getString(R.string.ai_inspection_local_save_failed) },
            subtitle = null,
        )
        bottomPromptSync.visibility = View.GONE
        hideActionPrompts()
        refreshInputActions()
    }

    private fun renderLocalDescriptionGuide() {
        operationGuideStream.setGuide(
            content = localDescriptionPromptSubtitle(),
        )
        operationGuideStream.visibility = View.GONE
    }

    private fun renderLocalDescriptionPrompt() {
        renderLocalDescriptionGuide()
        bottomPromptSync.visibility = View.GONE
    }

    private fun renderLocalAdvicePrompt() {
        operationGuideStream.setGuide(
            content = localContinuePromptSubtitle(),
        )
        operationGuideStream.visibility = View.GONE
        bottomPromptSync.visibility = View.GONE
    }

    private fun hideActionPrompts() {
        operationGuideDetecting.visibility = View.GONE
        operationGuideStream.visibility = View.GONE
        operationGuideSync.visibility = View.GONE
        bottomPromptSync.visibility = View.GONE
        bottomPromptSuccess.visibility = View.GONE
    }

    private fun clearLocalHazardResultState() {
        clearPendingAutoHazardPresentation()
        streamPanelAnchoredBelowPreview = false
        activeHazardContent = null
        localResultStage = LocalResultStage.NONE
        localSaveSubmitting = false
        localHazardAutoSaveTaskKey = null
        localHazardAlertTtsPlayed = false
        localHazardAdviceTtsPlayed = false
    }

    private fun invalidateActiveScanCycles() {
        scanCycleEpoch += 1
        activeScanCycles.clear()
        onlineHazardDetectionService.cancelAll()
    }

    private fun isAutoHazardPresentationPending(): Boolean {
        return pendingAutoHazardPresentation != null
    }

    private fun clearPendingAutoHazardPresentation() {
        uiHandler.removeCallbacks(pendingAutoHazardPresentationRunnable)
        pendingAutoHazardPresentation = null
        refreshPendingHazardAlertOverlay()
    }

    private fun isScanCycleActive(
        cycleId: Long,
        epoch: Long,
    ): Boolean {
        return epoch == scanCycleEpoch && activeScanCycles[cycleId]?.epoch == epoch
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
                val payload = selectBestOnlineFramePayload()
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
     */
    private fun requestStreamingAnalysis() {
        if (streamingInProgress || streamCallbackActive) {
            return
        }
        invalidateActiveScanCycles()
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
        stopLocalDetectionLoop("start_streaming")
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
                InspectionWorkflowSession.recordCapture(frozenJpeg)
                setStreamThumbnail(frozenJpeg)
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
            ivStreamThumbnail.setImageBitmap(thumbnail)
            ivStreamThumbnail.visibility = View.VISIBLE
            currentStreamThumbnail?.takeIf { !it.isRecycled }?.recycle()
            currentStreamThumbnail = thumbnail
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
     * descrip/advice 固定在预览框下沿向下展开，其他状态维持底部半屏布局。
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
                (containerHeight - previewBottomOffsetPx() - statusHeight).coerceAtLeast(0)
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
        ivStreamThumbnail.setImageBitmap(bitmap)
        ivStreamThumbnail.visibility = View.VISIBLE
        currentStreamThumbnail?.takeIf { !it.isRecycled }?.recycle()
        currentStreamThumbnail = bitmap
    }

    private fun beginStreamingRequest() {
        currentManualAnalysisHandle?.cancel()
        currentManualAnalysisHandle = null
        clearLocalHazardResultState()
        sessionId = ""
        activeStreamRequestId += 1
        showPage(PageState.STREAM_RESPONSE)
        clearStreamResponseUiState()
        renderLocalDescriptionGuide()
        streamPanelAnchoredBelowPreview = true
        applyCurrentStreamPanelLayout()
        streamingInProgress = true
        streamCallbackActive = true
        bottomPromptSync.visibility = View.GONE
        hideActionPrompts()
        refreshInputActions()
    }

    private fun shouldDeliverStreamRequest(requestId: Long): Boolean {
        return !destroyed &&
            isActivityResumed &&
            isWorkflowActive &&
            pageState == PageState.STREAM_RESPONSE &&
            streamingInProgress &&
            streamCallbackActive &&
            requestId == activeStreamRequestId
    }

    /**
     * 通过 /ai/ar 接口发送图像数据。
     */
    private fun sendImageToAiAr(base64Image: String) {
        currentManualAnalysisHandle?.cancel()
        currentManualAnalysisHandle = aiArSseService.fetchHazardDetails(
            base64Image = base64Image,
            onChunk = { partialText ->
                Log.d(TAG, "manual ai/ar chunk length=${partialText.length}")
                uiHandler.post {
                    if (currentManualAnalysisHandle == null) {
                        return@post
                    }
                    updateStreamingText(partialText)
                }
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
        activeHazardContent = resolved
        localResultStage = LocalResultStage.DESCRIPTION
        localSaveSubmitting = false
        sessionId = ""
        val descriptionText = resolved.descriptionPageText()
        setStreamContentAndResetViewport(descriptionText)
        lastAnalysisText = descriptionText
        InspectionWorkflowSession.recordDetection(resolved.displayTitle, descriptionText)
        InspectionWorkflowSession.recordAnalysis(lastAnalysisText)
        renderLocalDescriptionPrompt()
        if (!resolved.isOnlineNoHazardResult()) {
            scheduleBackgroundLocalHazardSaveIfNeeded(resolved)
        }
        refreshInputActions()
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
