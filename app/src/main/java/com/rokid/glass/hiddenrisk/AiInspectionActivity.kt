package com.rokid.glass.hiddenrisk

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rokid.glass.AiInspectionMenuActivity
import com.rokid.glass.InspectionEndReportActivity
import com.rokid.glass.camera.RokidFrameSource
import com.rokid.glass.component.BottomPromptView
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.component.OperationGuideView
import com.rokid.glass.component.RokidCameraPreviewView
import com.rokid.glass.component.StatusAlertOverlayView
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.utils.BitmapUtils
import com.rokid.glass.utils.SpriteToastUtil
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glesse.R
import okhttp3.sse.EventSource
import java.io.InputStreamReader
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AI 巡检页面。
 * 流程：加载初始化 -> 本地 NCNN 检测 -> 疑似隐患弹窗 + 自动流式窗口 -> 流式回答 -> 同步确认。
 */
class AiInspectionActivity : BaseGlassActivity(), RokidSdkManager.Listener {

    private lateinit var layoutLoading: View

    companion object {
        private const val TAG = "AiInspection"
        private const val REQUEST_MEDIA_PERMISSION = 201
        private const val CAPTURE_TIMEOUT_MS = 1000L  // 从15000改为1000，快速超时并自动重试
        private const val MAX_CONSECUTIVE_TIMEOUTS = 3
        private const val MAX_CAMERA_RESTART_ATTEMPTS = 3
        private const val CAPTURE_WARMUP_MS = 1200L
        private const val DETECT_STABLE_GATE_MS = 500L
        private const val LOCAL_DETECT_INTERVAL_MS = 1000L
        private const val AUTO_STREAM_WINDOW_MS = 3000L
        private const val AUTO_STREAM_REQUIRED_STABLE_MS = 1000L
        private const val LOCAL_HAZARD_ALERT_MESSAGE = "疑似隐患，请在 3 秒内保持稳定，系统将自动进入分析"
        private const val STREAM_THUMBNAIL_TARGET_PX = 160
        private const val LOCAL_HAZARD_INFO_ASSET = "info.json"
        private const val LOCAL_SAVE_SUCCESS_TOAST_MS = 1500

        private const val BACKEND_GPU = 1
        private const val GPU_PROFILE_BALANCED_FP16 = 1
        private const val DEFAULT_TARGET_INPUT_SIZE = 640
        private const val ENABLE_HIT_CAPTURE_SAVE = false
        private const val STALE_FRAME_THRESHOLD_MS = 1200L
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
    )

    private data class LocalHazardInfo(
        val item: String,
        val description: String,
        val advice: String,
    )

    private data class LocalHazardMatch(
        val info: LocalHazardInfo,
        val score: Float,
    )

    private enum class LocalResultStage {
        NONE,
        DESCRIPTION,
        ADVICE,
    }

    // --- UI ---
    private lateinit var layoutDetection: FrameLayout
    private lateinit var viewLivePreview: RokidCameraPreviewView
    private lateinit var statusAlertOverlay: StatusAlertOverlayView
    private lateinit var layoutStreamResponse: FrameLayout
    private lateinit var layoutStreamThumbnailCard: FrameLayout
    private lateinit var tvStreamContent: TextView
    private lateinit var scrollContent: ScrollView
    private lateinit var ivStreamThumbnail: ImageView
    private lateinit var bottomPromptSync: BottomPromptView
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
    private lateinit var tvDetectionStatus: TextView
    private lateinit var layoutHazardBanner: LinearLayout

    private val uiHandler = Handler(Looper.getMainLooper())
    private val nativeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val imageEncodeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val inferenceRunning = AtomicBoolean(false)
    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private val motionStabilityTracker by lazy { MotionStabilityTracker(this) }
    private val deepAnalysisService by lazy { HazardDeepAnalysisService() }

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
    private var pendingAutoWindowStreamStart = false
    private var activeStreamRequestId = 0L
    private var lastAnalysisText = ""
    private var hazardCaptureService: HazardCaptureService? = null
    private var pendingHazardSnapshot: NativeInferenceStats? = null
    private var pendingHazardPayload: CapturedFramePayload? = null
    private var activeLocalHazardInfo: LocalHazardInfo? = null
    private var localResultStage = LocalResultStage.NONE
    private val localHazardInfoByItem: Map<String, LocalHazardInfo> by lazy {
        loadLocalHazardInfos().associateBy { it.item }
    }

    private var isMotionStable = false
    private var stableQualifiedAtMillis: Long? = null
    private var lastConsumedFrameTimestamp = 0L
    private var autoStreamWindowDeadlineElapsedMs = 0L
    private var autoStreamWindowRequestId = 0L
    private var autoStreamStartRequestId = 0L

    // SSE 相关
    private var currentEventSource: EventSource? = null
    private var headGestureSupported = false
    private var debugSnapshotState: String? = null

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
                maybeScheduleAutoStreamStart()
                scheduleDetectionCaptureIfNeeded(reason = "motion_stable", preferImmediate = true)
            } else {
                Log.i(TAG, "motion unstable cancel auto stream start")
                cancelPendingAutoStreamStart()
            }
            refreshDetectionStatus()
        }
    }

    // 本次拍照上传的会话 ID，用于与 save 接口保持一致的指纹
    private var sessionId = ""

    // 拍摄超时恢复机制
    private var consecutiveTimeoutCount = 0
    private var frameStreamRestartAttempts = 0
    private var isFrameStreamRestarting = false

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

    private val captureTimeoutRunnable = Runnable {
        if (!captureInProgress || destroyed) return@Runnable

        Log.w(TAG, "拍摄超时，连续超时次数: ${consecutiveTimeoutCount + 1}")
        captureInProgress = false
        consecutiveTimeoutCount++
        if (startPendingStreamAnalysis()) return@Runnable

        when {
            consecutiveTimeoutCount >= MAX_CONSECUTIVE_TIMEOUTS -> {
                Log.w(TAG, "连续3次拍摄超时，准备静默重启相机帧流")
                silentRestartFrameStream()
            }

            else -> {
                // 记录异常但继续自动拍摄
                Log.d(TAG, "拍摄超时，自动获取下一帧")
                scheduleAutoCaptureIfNeeded(LOCAL_DETECT_INTERVAL_MS)
            }
        }
    }

    private val captureDelayRunnable = Runnable {
        captureDelayScheduled = false
        if (destroyed || !pendingCaptureRequest || captureInProgress) return@Runnable
        startSampleCaptureIfNeeded()
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

    private val autoStreamWindowExpireRunnable = Runnable {
        if (destroyed || pageState != PageState.DETECTING) return@Runnable
        if (pendingAutoWindowStreamStart || !hasActiveAutoStreamWindow()) return@Runnable
        clearAutoStreamWindow()
        clearPendingHazardResult()
        refreshDetectionStatus()
    }

    private val autoStreamStartRunnable = Runnable {
        val requestId = autoStreamStartRequestId
        autoStreamStartRequestId = 0L
        if (destroyed || pageState != PageState.DETECTING) return@Runnable
        if (requestId == 0L || requestId != autoStreamWindowRequestId) return@Runnable
        if (!hasActiveAutoStreamWindow()) return@Runnable
        if (!isMotionStable || stableQualifiedAtMillis == null) return@Runnable
        if (remainingStableMsForAutoStream() > 0L) {
            maybeScheduleAutoStreamStart()
            return@Runnable
        }
        if (startPendingHazardResultAfterStable()) {
            return@Runnable
        }
        pendingAutoWindowStreamStart = true
        pendingStreamStart = true
        refreshDetectionStatus()
        if (captureInProgress || inferenceRunning.get()) {
            return@Runnable
        }
        startPendingStreamAnalysis()
    }

    // ==================== 生命周期 ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_inspection)

        layoutLoading = findViewById(R.id.layoutLoading)
        layoutDetection = findViewById(R.id.layoutDetection)
        viewLivePreview = findViewById(R.id.viewLivePreview)
        statusAlertOverlay = findViewById(R.id.statusAlertOverlay)
        layoutStreamResponse = findViewById(R.id.layoutStreamResponse)
        layoutStreamThumbnailCard = findViewById(R.id.layoutStreamThumbnailCard)
        tvStreamContent = findViewById(R.id.tvStreamContent)
        scrollContent = findViewById(R.id.scrollContent)
        ivStreamThumbnail = findViewById(R.id.ivStreamThumbnail)
        bottomPromptSync = findViewById(R.id.bottomPromptSync)
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
        tvDetectionStatus = findViewById(R.id.tvDetectionStatus)
        layoutHazardBanner = findViewById(R.id.layoutHazardBanner)

        // 设置检测页操作指引内容
        val operationGuideDetecting = findViewById<OperationGuideView>(R.id.operationGuideDetecting)
        operationGuideDetecting.setGuide(
            title = "操作指引",
            content = "单击 立即分析当前画面\n双击 返回\n说出\"返回\"\n说出\"结束\""
        )

        HeadGestureManager.initialize(this)
        headGestureSupported = HeadGestureManager.isSupported()
        if (!headGestureSupported) {
            Log.w(TAG, "头部动作识别不可用，确认节点仅支持触控与语音")
        }
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
            startDetectionPreviewIfNeeded()
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
        stopDetectionPreview()
        stopLocalDetectionLoop("onPause")
        hideStatusAlertOverlay()
        // 关闭当前 SSE 连接
        currentEventSource?.cancel()
        currentEventSource = null
        frameStreamInitializing = false
        frameStreamReady = false
        frameStreamReadyAtElapsedMs = 0L
        lastConsumedFrameTimestamp = 0L
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
        currentEventSource?.cancel()
        currentEventSource = null
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        streamCallbackActive = false
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
        // 重置超时恢复计数器
        consecutiveTimeoutCount = 0
        frameStreamRestartAttempts = 0
        isFrameStreamRestarting = false
        RokidSdkManager.removeListener(this)
        // 注意：不单独释放 RokidFrameSource 和 hiddenRiskNcnn，由 InspectionSession 管理生命周期
        nativeExecutor.shutdown()
        imageEncodeExecutor.shutdown()
        runCatching { nativeExecutor.awaitTermination(2, TimeUnit.SECONDS) }
        runCatching { imageEncodeExecutor.awaitTermination(2, TimeUnit.SECONDS) }
        hazardCaptureService?.shutdown()
        // 只有当真正结束巡检（不是返回重新检测）时才释放 InspectionSession
        if (isFinishing && !isChangingConfigurations) {
            InspectionSession.release()
        }
        // 关闭当前 SSE 连接
        currentEventSource?.cancel()
        currentEventSource = null
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
        RokidFrameSource.startFrameStream { success ->
            uiHandler.post {
                frameStreamInitializing = false
                frameStreamReady = success
                frameStreamReadyAtElapsedMs = if (success) SystemClock.elapsedRealtime() else 0L
                if (destroyed) {
                    RokidFrameSource.stopFrameStream()
                    return@post
                }
                if (!isActivityResumed || !isWorkflowActive) {
                    frameStreamReady = false
                    frameStreamReadyAtElapsedMs = 0L
                    RokidFrameSource.stopFrameStream()
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

    /**
     * 静默重启 SDK 帧流，不显示错误页面。
     * 用于连续超时后的自动恢复。
     */
    private fun silentRestartFrameStream() {
        if (isFrameStreamRestarting || destroyed) return

        frameStreamRestartAttempts++

        if (frameStreamRestartAttempts > MAX_CAMERA_RESTART_ATTEMPTS) {
            Log.e(TAG, "相机帧流重启${MAX_CAMERA_RESTART_ATTEMPTS}次失败，显示错误")
            failWorkflow("相机帧流连续超时，请检查设备")
            return
        }

        Log.i(TAG, "静默重启相机帧流 (尝试 ${frameStreamRestartAttempts}/${MAX_CAMERA_RESTART_ATTEMPTS})")
        isFrameStreamRestarting = true

        frameStreamReady = false
        frameStreamReadyAtElapsedMs = 0L
        lastConsumedFrameTimestamp = 0L
        RokidFrameSource.stopFrameStream()

        uiHandler.postDelayed({
            if (destroyed) {
                isFrameStreamRestarting = false
                return@postDelayed
            }
            RokidFrameSource.startFrameStream { success ->
                uiHandler.post {
                    isFrameStreamRestarting = false

                    if (success) {
                        Log.i(TAG, "相机帧流静默重启成功")
                        consecutiveTimeoutCount = 0
                        frameStreamRestartAttempts = 0
                        frameStreamReady = true
                        frameStreamReadyAtElapsedMs = SystemClock.elapsedRealtime()
                        pendingCaptureRequest = false
                        scheduleDetectionCaptureIfNeeded(reason = "frame_stream_restart", preferImmediate = true)
                    } else {
                        Log.e(TAG, "相机帧流静默重启失败")
                        silentRestartFrameStream()
                    }
                }
            }
        }, 500L)  // 500ms延迟确保资源释放
    }

    private fun returnToDetecting() {
        currentEventSource?.cancel()
        currentEventSource = null
        streamCallbackActive = false
        streamingInProgress = false
        pendingStreamStart = false
        pendingAutoWindowStreamStart = false
        clearPendingHazardResult()
        clearLocalHazardResultState()
        activeStreamRequestId++
        hideStatusAlertOverlay()
        // 重置超时恢复计数器
        consecutiveTimeoutCount = 0
        frameStreamRestartAttempts = 0
        isFrameStreamRestarting = false
        showPage(PageState.DETECTING)
        applyDefaultDetectionStatus()
        clearAutoStreamWindow()
        initFrameStreamAndTransition()
    }

    private fun returnDirectlyToHome() {
        currentEventSource?.cancel()
        currentEventSource = null
        streamCallbackActive = false
        streamingInProgress = false
        pendingStreamStart = false
        pendingAutoWindowStreamStart = false
        clearPendingHazardResult()
        clearLocalHazardResultState()
        activeStreamRequestId++
        stopLocalDetectionLoop("return_home")
        hideStatusAlertOverlay()
        refreshInputActions()
        InspectionWorkflowSession.clearForNewInspection()
        startActivity(Intent(this, AiInspectionMenuActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun finishInspectionWithReport() {
        currentEventSource?.cancel()
        currentEventSource = null
        streamCallbackActive = false
        streamingInProgress = false
        pendingStreamStart = false
        pendingAutoWindowStreamStart = false
        clearPendingHazardResult()
        activeStreamRequestId++
        stopLocalDetectionLoop("finish_inspection")
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

        pendingCaptureRequest = false
        captureInProgress = true
        uiHandler.removeCallbacks(captureTimeoutRunnable)
        uiHandler.postDelayed(captureTimeoutRunnable, CAPTURE_TIMEOUT_MS)
        val captureRequestStartMs = SystemClock.elapsedRealtime()

        val frame = copyFrameForDetectionOrNull()
        captureInProgress = false
        uiHandler.removeCallbacks(captureTimeoutRunnable)

        if (consecutiveTimeoutCount > 0) {
            Log.d(TAG, "取帧成功，重置超时计数器")
            consecutiveTimeoutCount = 0
        }

        if (frame == null) {
            Log.w(
                TAG,
                "copyFrameForDetectionOrNull failed elapsed=${SystemClock.elapsedRealtime() - captureRequestStartMs}ms warm=${RokidFrameSource.isCroppedFrameStreamWarm()}",
            )
            if (startPendingStreamAnalysis()) {
                return
            }
            scheduleAutoCaptureIfNeeded(LOCAL_DETECT_INTERVAL_MS)
            return
        }

        Log.i(
            TAG,
            "copyFrameForDetectionOrNull submitted width=${frame.width} height=${frame.height} timestamp=${frame.timestamp} elapsed=${SystemClock.elapsedRealtime() - captureRequestStartMs}ms warm=${RokidFrameSource.isCroppedFrameStreamWarm()}",
        )
        triggerInference(frame)
    }

    private fun triggerInference(frame: RokidFrameSource.CroppedNv21Frame) {
        val local = hiddenRiskNcnn ?: run {
            return
        }
        if (!inferenceRunning.compareAndSet(false, true)) {
            return
        }

        if (!submitNativeTask {
                val nativeStartElapsedMs = SystemClock.elapsedRealtime()
                val success = runCatching {
                    local.submitNv21(
                        frame.data,
                        frame.width,
                        frame.height,
                    )
                }.onFailure { e -> Log.e(TAG, "submitNv21 failed", e) }
                    .getOrDefault(false)
                val snapshot = runCatching { local.getLatestInferenceStats() }.getOrNull()
                val nativeElapsedMs = SystemClock.elapsedRealtime() - nativeStartElapsedMs
                val inferenceMs = snapshot?.inferenceTimeMs ?: -1L
                val detectionCount = snapshot?.detectionCount ?: 0
                val payload = if (success && detectionCount > 0) {
                    buildCapturedFramePayload(frame)
                } else {
                    null
                }
                uiHandler.post {
                    inferenceRunning.set(false)
                    Log.d(
                        TAG,
                        "inference success=$success detectionCount=$detectionCount nativeElapsedMs=$nativeElapsedMs inferenceMs=$inferenceMs"
                    )
                    if (destroyed) return@post
                    if (!success) {
                        if (startPendingStreamAnalysis()) {
                            return@post
                        }
                        scheduleAutoCaptureIfNeeded(LOCAL_DETECT_INTERVAL_MS)
                    } else {
                        if (detectionCount == 0) {
                            InspectionWorkflowSession.updateSummary { summary ->
                                summary.copy(
                                    analyzedCount = summary.analyzedCount + 1,
                                    noHazardCount = summary.noHazardCount + 1,
                                )
                            }
                            if (startPendingStreamAnalysis()) {
                                return@post
                            }
                            scheduleAutoCaptureIfNeeded(LOCAL_DETECT_INTERVAL_MS)
                        } else {
                            pendingHazardSnapshot = snapshot
                            pendingHazardPayload = payload
                            payload?.let {
                                InspectionWorkflowSession.recordCapture(it.jpegBytes)
                            }
                            if (ENABLE_HIT_CAPTURE_SAVE) {
                                payload?.let { captured ->
                                    ensureHazardCaptureService().saveHazardCapture(
                                        captured.jpegBytes,
                                        snapshot
                                    )
                                } ?: Log.w(TAG, "当前隐患帧 JPEG 编码失败，跳过保存")
                            } else {
                                Log.i(TAG, "检测命中保存已关闭，跳过图片落盘")
                            }

                            InspectionWorkflowSession.recordDetection("", LOCAL_HAZARD_ALERT_MESSAGE)
                            InspectionWorkflowSession.updateSummary { summary ->
                                summary.copy(
                                    analyzedCount = summary.analyzedCount + 1,
                                )
                            }
                            openAutoStreamWindow()
                            if (startPendingStreamAnalysis()) {
                                return@post
                            }
                            scheduleAutoCaptureIfNeeded(LOCAL_DETECT_INTERVAL_MS)
                        }
                    }
                }
            }) {
            inferenceRunning.set(false)
            if (startPendingStreamAnalysis()) {
                return
            }
            scheduleAutoCaptureIfNeeded(LOCAL_DETECT_INTERVAL_MS)
        }
    }

    private fun copyFrameForDetectionOrNull(): RokidFrameSource.CroppedNv21Frame? {
        if (!frameStreamReady || !RokidFrameSource.isFrameStreamWarm()) {
            return null
        }
        val frame = RokidFrameSource.copyLatestCroppedFrame() ?: return null
        if (frame.timestamp <= lastConsumedFrameTimestamp) {
            Log.w(TAG, "drop frame reason=duplicate timestamp=${frame.timestamp} last=$lastConsumedFrameTimestamp")
            return null
        }
        val ageMs = SystemClock.elapsedRealtime() - frame.receivedAtElapsedMs
        if (ageMs > STALE_FRAME_THRESHOLD_MS) {
            Log.w(TAG, "drop frame reason=stale timestamp=${frame.timestamp} ageMs=$ageMs")
            return null
        }
        lastConsumedFrameTimestamp = frame.timestamp
        return frame
    }

    private fun copyLatestFrameForStreamingOrNull(): RokidFrameSource.CroppedNv21Frame? {
        if (!frameStreamReady || !RokidFrameSource.isFrameStreamWarm()) {
            return null
        }
        val frame = RokidFrameSource.copyLatestCroppedFrame() ?: return null
        val ageMs = SystemClock.elapsedRealtime() - frame.receivedAtElapsedMs
        if (ageMs > STALE_FRAME_THRESHOLD_MS) {
            Log.w(TAG, "drop stream frame reason=stale timestamp=${frame.timestamp} ageMs=$ageMs")
            return null
        }
        return frame
    }

    private fun buildCapturedFramePayload(frame: RokidFrameSource.CroppedNv21Frame): CapturedFramePayload? {
        val jpegBytes = BitmapUtils.encodeNv21ToJpeg(
            nv21 = frame.data,
            width = frame.width,
            height = frame.height,
            jpegQuality = 80,
        ) ?: return null
        return CapturedFramePayload(
            jpegBytes = jpegBytes,
            width = frame.width,
            height = frame.height,
            timestamp = frame.timestamp,
        )
    }

    // ==================== 隐患处理流程 ====================

    private fun hideStatusAlertOverlay() {
        statusAlertOverlay.reset()
    }

    private fun hideHazardBanner() {
        layoutHazardBanner.visibility = View.GONE
    }

    private fun syncToPhone() {
        if (pageState != PageState.STREAM_RESPONSE || streamingInProgress) {
            return
        }
        showSyncing()
        InspectionSyncService.syncAnalysisToPhone(
            sessionId = sessionId,
            callback = object : InspectionSyncService.Callback {
                override fun onSuccess() {
                    uiHandler.post {
                        if (destroyed) return@post
                        InspectionWorkflowSession.recordPhoneSync(sessionId)
                        InspectionWorkflowSession.updateSummary { summary ->
                            summary.copy(hasHazardCount = summary.hasHazardCount + 1)
                        }
                        showSyncSuccess()
                    }
                }

                override fun onError(message: String) {
                    uiHandler.post {
                        if (destroyed) return@post
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
        bottomPromptSync.visibility = View.VISIBLE
        if (message.isNotBlank()) {
            Log.w(TAG, "sync error prompt message=$message")
        }
    }

    private fun applyDefaultDetectionStatus() {
        refreshDetectionStatus()
    }

    // ==================== 自动拍摄调度 ====================

    private fun shouldAutoCaptureNow(): Boolean {
        if (destroyed || !isActivityResumed || !isWorkflowActive) return false
        if (!hasRequiredPermissions()) return false
        if (RokidSdkManager.state != RokidSdkManager.SdkState.READY) return false
        if (!modelLoaded || modelLoading) return false
        if (pendingStreamStart || streamingInProgress || streamCallbackActive) return false
        if (hasActiveAutoStreamWindow() || pendingHazardSnapshot != null) return false
        if (captureInProgress || captureDelayScheduled || frameStreamInitializing || pendingCaptureRequest) return false
        if (inferenceRunning.get()) return false
        if (pageState != PageState.DETECTING) return false
        if (!isMotionStable || stableQualifiedAtMillis == null) return false
        return true
    }

    private fun scheduleAutoCaptureIfNeeded(delayMs: Long) {
        if (autoCaptureScheduled || destroyed || !isActivityResumed || !isWorkflowActive) return
        if (pendingStreamStart || streamingInProgress || streamCallbackActive) return
        if (pageState != PageState.DETECTING) return
        autoCaptureScheduled = true
        uiHandler.postDelayed(autoCaptureRunnable, delayMs.coerceAtLeast(0L))
    }

    private fun scheduleDetectionCaptureIfNeeded(reason: String, preferImmediate: Boolean) {
        if (preferImmediate && requestImmediateDetectionCaptureIfPossible(reason)) {
            return
        }
        scheduleAutoCaptureIfNeeded(LOCAL_DETECT_INTERVAL_MS)
    }

    private fun requestImmediateDetectionCaptureIfPossible(reason: String): Boolean {
        if (!shouldAutoCaptureNow()) {
            return false
        }
        if (hasActiveAutoStreamWindow() || pendingAutoWindowStreamStart) {
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
        uiHandler.removeCallbacks(captureDelayRunnable)
        uiHandler.removeCallbacks(captureTimeoutRunnable)
        uiHandler.removeCallbacks(autoCaptureRunnable)
        clearAutoStreamWindow()
        clearPendingHazardResult()
        if (clearPendingStreamState) {
            pendingStreamStart = false
            pendingAutoWindowStreamStart = false
        }
    }

    private fun refreshDetectionStatus() {
        if (pageState != PageState.DETECTING) {
            return
        }
        val statusRes = when {
            pendingStreamStart -> R.string.ai_detection_deep_analysis_running
            hasActiveAutoStreamWindow() -> R.string.ai_detection_deep_analysis_wait
            !isMotionStable -> R.string.ai_detection_waiting_for_stable
            else -> R.string.ai_detection_offline_running
        }
        tvDetectionStatus.setText(statusRes)
    }

    private fun hasActiveAutoStreamWindow(): Boolean {
        return autoStreamWindowDeadlineElapsedMs > SystemClock.elapsedRealtime()
    }

    private fun remainingStableMsForAutoStream(): Long {
        val qualifiedAtMillis = stableQualifiedAtMillis ?: return AUTO_STREAM_REQUIRED_STABLE_MS
        val elapsedSinceQualifiedMs = (System.currentTimeMillis() - qualifiedAtMillis).coerceAtLeast(0L)
        val remainingAfterQualifiedMs = AUTO_STREAM_REQUIRED_STABLE_MS - DETECT_STABLE_GATE_MS
        return (remainingAfterQualifiedMs - elapsedSinceQualifiedMs).coerceAtLeast(0L)
    }

    private fun openAutoStreamWindow() {
        autoStreamWindowRequestId += 1
        autoStreamWindowDeadlineElapsedMs = SystemClock.elapsedRealtime() + AUTO_STREAM_WINDOW_MS
        pendingAutoWindowStreamStart = false
        cancelPendingAutoStreamStart()
        uiHandler.removeCallbacks(autoStreamWindowExpireRunnable)
        uiHandler.postDelayed(autoStreamWindowExpireRunnable, AUTO_STREAM_WINDOW_MS)
        maybeScheduleAutoStreamStart()
        refreshDetectionStatus()
    }

    private fun clearAutoStreamWindow() {
        if (autoStreamWindowDeadlineElapsedMs <= 0L && !pendingAutoWindowStreamStart) {
            return
        }
        autoStreamWindowDeadlineElapsedMs = 0L
        pendingAutoWindowStreamStart = false
        autoStreamWindowRequestId += 1
        cancelPendingAutoStreamStart()
        uiHandler.removeCallbacks(autoStreamWindowExpireRunnable)
    }

    private fun cancelPendingAutoStreamStart() {
        autoStreamStartRequestId = 0L
        uiHandler.removeCallbacks(autoStreamStartRunnable)
    }

    private fun maybeScheduleAutoStreamStart() {
        if (pendingAutoWindowStreamStart || !hasActiveAutoStreamWindow()) return
        if (pageState != PageState.DETECTING) return
        cancelPendingAutoStreamStart()
        if (!isMotionStable || stableQualifiedAtMillis == null) return
        val remainingStableMs = remainingStableMsForAutoStream()
        val remainingWindowMs =
            (autoStreamWindowDeadlineElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        if (remainingWindowMs <= 0L) return
        autoStreamStartRequestId = autoStreamWindowRequestId
        if (remainingStableMs <= 0L) {
            uiHandler.post(autoStreamStartRunnable)
            return
        }
        uiHandler.postDelayed(autoStreamStartRunnable, minOf(remainingStableMs, remainingWindowMs))
    }

    // ==================== UI 页面切换 ====================

    private fun startDetectionPreviewIfNeeded() {
        if (!isActivityResumed || destroyed) {
            return
        }
        viewLivePreview.visibility = View.VISIBLE
        viewLivePreview.startPreview { success ->
            if (!success) {
                Log.w(TAG, "left-top live preview start failed")
            }
        }
    }

    private fun stopDetectionPreview() {
        viewLivePreview.visibility = View.GONE
        viewLivePreview.stopPreview()
    }

    private fun showPage(state: PageState) {
        pageState = state
        layoutLoading.visibility = View.GONE
        layoutDetection.visibility = if (state == PageState.DETECTING) View.VISIBLE else View.GONE
        layoutStreamResponse.visibility =
            if (state == PageState.STREAM_RESPONSE) View.VISIBLE else View.GONE
        layoutSyncSuccess.visibility =
            if (state == PageState.SYNCING || state == PageState.SYNC_SUCCESS) View.VISIBLE else View.GONE
        bottomPromptSuccess.visibility =
            if (state == PageState.SYNC_SUCCESS) View.VISIBLE else View.GONE
        if (state == PageState.DETECTING) {
            startDetectionPreviewIfNeeded()
        } else {
            stopDetectionPreview()
        }
        if (state != PageState.DETECTING) {
            hideStatusAlertOverlay()
            hideHazardBanner()
        }
        // DETECTING 状态时显示隐患 banner
        if (state == PageState.DETECTING) {
            clearStreamResponseUiState()
            refreshDetectionStatus()
        }
        if (state == PageState.SYNCING || state == PageState.SYNC_SUCCESS) {
            renderSyncStatusUi(state)
        } else {
            ivSyncLoading.clearAnimation()
            ivSyncLoading.visibility = View.GONE
            ivSyncSuccessIcon.visibility = View.VISIBLE
            operationGuideSync.visibility = View.VISIBLE
        }
        refreshInputActions()
    }

    private fun applyDebugSnapshotState(state: String) {
        when (state) {
            "analyzing" -> {
                showPage(PageState.DETECTING)
                tvDetectionStatus.setText(R.string.ai_detection_deep_analysis_running)
            }
            "result" -> {
                showPage(PageState.STREAM_RESPONSE)
                tvStreamContent.text = intent.getStringExtra("debug_text")
                    ?: "隐患描述：\n三合一住人，防盗窗未设置紧急逃生口，电子烟靠近笔记本电脑存在火灾风险。\n\n整改建议：\n立即拆除住宿隔断，增设逃生口，远离易燃物。"
                bottomPromptSync.setPrompt(
                    title = getString(R.string.ai_inspection_sync_prompt),
                    subtitle = syncPromptSubtitle(),
                )
                bottomPromptSync.visibility = View.VISIBLE
                // 限制结果卡片高度不超过半屏
                adjustStreamScrollHeight()
                // 调试模式：显示一个测试缩略图
                showDebugThumbnail()
            }
            "sync" -> {
                showPage(PageState.SYNC_SUCCESS)
                bottomPromptSuccess.setPrompt(
                    title = getString(R.string.ai_inspection_continue_prompt)
                )
            }
            else -> {
                showPage(PageState.DETECTING)
                tvDetectionStatus.text = intent.getStringExtra("debug_text")
                    ?: getString(R.string.ai_detection_offline_running)
            }
        }
        refreshInputActions()
    }

    private fun buildInputActions(): List<UnifiedInputSession.InputActionSpec> {
        return listOf(
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_detecting_stream_analysis"),
                label = "检测页分析",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK),
                ),
                enabled = { pageState == PageState.DETECTING },
            ) {
                requestStreamingAnalysis()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_detecting_exit"),
                label = "检测页退出",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK),
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK),
                    UnifiedInputSession.InputTrigger.Voice("退出", "tui chu"),
                ),
                enabled = { pageState == PageState.DETECTING },
            ) {
                returnDirectlyToHome()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_stream_confirm_sync"),
                label = "确认同步",
                triggers = buildList {
                    add(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK))
                    add(UnifiedInputSession.InputTrigger.Voice("确认", "que ren"))
                    if (headGestureSupported) {
                        add(UnifiedInputSession.InputTrigger.HeadGesture(HeadGestureManager.HeadGestureType.NOD))
                    }
                },
                enabled = {
                    pageState == PageState.STREAM_RESPONSE &&
                        !streamingInProgress &&
                        localResultStage == LocalResultStage.NONE
                },
            ) {
                handleStreamConfirmAction()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_local_description_save"),
                label = "保存隐患",
                triggers = buildList {
                    add(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK))
                    add(UnifiedInputSession.InputTrigger.Voice("保存", "bao cun"))
                    if (headGestureSupported) {
                        add(UnifiedInputSession.InputTrigger.HeadGesture(HeadGestureManager.HeadGestureType.NOD))
                    }
                },
                enabled = {
                    pageState == PageState.STREAM_RESPONSE &&
                        !streamingInProgress &&
                        localResultStage == LocalResultStage.DESCRIPTION
                },
            ) {
                confirmLocalHazardSave()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_local_advice_continue"),
                label = "继续识别",
                triggers = buildList {
                    add(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK))
                    add(UnifiedInputSession.InputTrigger.Voice("继续", "ji xu"))
                    if (headGestureSupported) {
                        add(UnifiedInputSession.InputTrigger.HeadGesture(HeadGestureManager.HeadGestureType.NOD))
                    }
                },
                enabled = {
                    pageState == PageState.STREAM_RESPONSE &&
                        !streamingInProgress &&
                        localResultStage == LocalResultStage.ADVICE
                },
            ) {
                returnToDetecting()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_stream_cancel_sync"),
                label = "取消同步",
                triggers = buildList {
                    add(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK))
                    add(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK))
                    add(UnifiedInputSession.InputTrigger.Voice("取消", "qu xiao"))
                    if (headGestureSupported) {
                        add(UnifiedInputSession.InputTrigger.HeadGesture(HeadGestureManager.HeadGestureType.SHAKE))
                    }
                },
                enabled = {
                    pageState == PageState.STREAM_RESPONSE &&
                        localResultStage == LocalResultStage.NONE
                },
            ) {
                handleStreamCancelAction(it)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_local_description_cancel_save"),
                label = "取消保存",
                triggers = buildList {
                    add(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK))
                    add(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK))
                    add(UnifiedInputSession.InputTrigger.Voice("取消", "qu xiao"))
                    if (headGestureSupported) {
                        add(UnifiedInputSession.InputTrigger.HeadGesture(HeadGestureManager.HeadGestureType.SHAKE))
                    }
                },
                enabled = {
                    pageState == PageState.STREAM_RESPONSE &&
                        !streamingInProgress &&
                        localResultStage == LocalResultStage.DESCRIPTION
                },
            ) {
                showLocalHazardAdvice(showSaveSuccessToast = false, countAsSaved = false)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_local_advice_exit"),
                label = "退出识别",
                triggers = buildList {
                    add(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK))
                    add(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK))
                    add(UnifiedInputSession.InputTrigger.Voice("退出", "tui chu"))
                    if (headGestureSupported) {
                        add(UnifiedInputSession.InputTrigger.HeadGesture(HeadGestureManager.HeadGestureType.SHAKE))
                    }
                },
                enabled = {
                    pageState == PageState.STREAM_RESPONSE &&
                        !streamingInProgress &&
                        localResultStage == LocalResultStage.ADVICE
                },
            ) {
                finishInspectionWithReport()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_sync_continue"),
                label = "继续巡检",
                triggers = buildList {
                    add(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK))
                    add(UnifiedInputSession.InputTrigger.Voice("继续", "ji xu"))
                    if (headGestureSupported) {
                        add(UnifiedInputSession.InputTrigger.HeadGesture(HeadGestureManager.HeadGestureType.NOD))
                    }
                },
                enabled = { pageState == PageState.SYNC_SUCCESS },
            ) {
                returnToDetecting()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_sync_finish"),
                label = "结束巡检",
                triggers = buildList {
                    add(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK))
                    add(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK))
                    add(UnifiedInputSession.InputTrigger.Voice("退出", "tui chu"))
                    if (headGestureSupported) {
                        add(UnifiedInputSession.InputTrigger.HeadGesture(HeadGestureManager.HeadGestureType.SHAKE))
                    }
                },
                enabled = { pageState == PageState.SYNC_SUCCESS },
            ) {
                finishInspectionWithReport()
            },
        )
    }

    private fun refreshInputActions() {
        inputSession.updateActions(buildInputActions())
    }

    private fun updateConfirmationHints() {
        val streamGuide = if (headGestureSupported) {
            "说出\"确认\"\n说出\"取消\"\n单击 确认\n双击 取消\n点头 确认\n摇头 取消"
        } else {
            "说出\"确认\"\n说出\"取消\"\n单击 确认\n双击 取消"
        }
        operationGuideStream.setContent(streamGuide)
        operationGuideSync.setGuide(title = "操作指引", content = streamGuide)
        bottomPromptSync.setPrompt(
            title = getString(R.string.ai_inspection_sync_prompt),
            subtitle = syncPromptSubtitle(),
        )
        bottomPromptSuccess.setPrompt(
            title = getString(R.string.ai_inspection_continue_prompt),
            subtitle = continuePromptSubtitle(),
        )
    }

    private fun syncPromptSubtitle(): String {
        return if (headGestureSupported) {
            getString(R.string.ai_inspection_sync_hint_with_gyro)
        } else {
            getString(R.string.ai_inspection_sync_hint)
        }
    }

    private fun localDescriptionPromptSubtitle(): String {
        return if (headGestureSupported) {
            getString(R.string.ai_inspection_local_save_hint_with_gyro)
        } else {
            getString(R.string.ai_inspection_local_save_hint)
        }
    }

    private fun localContinuePromptSubtitle(): String {
        return if (headGestureSupported) {
            getString(R.string.ai_inspection_local_continue_hint_with_gyro)
        } else {
            getString(R.string.ai_inspection_local_continue_hint)
        }
    }

    private fun continuePromptSubtitle(): String {
        return if (headGestureSupported) {
            getString(R.string.ai_inspection_continue_hint_with_gyro)
        } else {
            getString(R.string.ai_inspection_continue_hint)
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
        tvStreamContent.text = ""
        bottomPromptSync.visibility = View.INVISIBLE
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
                operationGuideSync.visibility = View.VISIBLE
            }

            else -> {
                ivSyncLoading.clearAnimation()
                ivSyncLoading.visibility = View.GONE
                ivSyncSuccessIcon.visibility = View.GONE
                operationGuideSync.visibility = View.VISIBLE
            }
        }
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

    private fun startPendingHazardResultAfterStable(): Boolean {
        val snapshot = pendingHazardSnapshot ?: return false
        val payload = pendingHazardPayload
        clearPendingHazardResult()
        val localMatch = findLocalHazardMatch(snapshot)
        if (localMatch != null) {
            startLocalHazardResult(localMatch, payload)
            return true
        }
        pendingAutoWindowStreamStart = true
        pendingStreamStart = true
        refreshDetectionStatus()
        if (captureInProgress || inferenceRunning.get()) {
            return true
        }
        startPendingStreamAnalysis()
        return true
    }

    private fun findLocalHazardMatch(snapshot: NativeInferenceStats): LocalHazardMatch? {
        if (localHazardInfoByItem.isEmpty()) {
            return null
        }
        return snapshot.detections
            .mapNotNull { detection ->
                val displayLabel = HiddenRiskMultiOverlayRenderer.labelFor(detection)
                val info = localHazardInfoByItem[detection.label]
                    ?: localHazardInfoByItem[displayLabel]
                    ?: return@mapNotNull null
                LocalHazardMatch(info = info, score = detection.score)
            }
            .maxByOrNull { it.score }
    }

    private fun startLocalHazardResult(match: LocalHazardMatch, payload: CapturedFramePayload?) {
        stopLocalDetectionLoop("start_local_hazard_result")
        currentEventSource?.cancel()
        currentEventSource = null
        activeStreamRequestId += 1
        streamingInProgress = false
        streamCallbackActive = false
        pendingStreamStart = false
        pendingAutoWindowStreamStart = false
        activeLocalHazardInfo = match.info
        localResultStage = LocalResultStage.DESCRIPTION
        sessionId = ""
        showPage(PageState.STREAM_RESPONSE)
        clearStreamResponseUiState()
        payload?.let {
            InspectionWorkflowSession.recordCapture(it.jpegBytes)
            setStreamThumbnail(it.jpegBytes)
        }
        tvStreamContent.text = match.info.description
        lastAnalysisText = match.info.description
        InspectionWorkflowSession.recordDetection(match.info.item, match.info.description)
        InspectionWorkflowSession.recordAnalysis(lastAnalysisText)
        renderLocalDescriptionPrompt()
        adjustStreamScrollHeight()
        refreshInputActions()
    }

    private fun handleStreamConfirmAction() {
        when (localResultStage) {
            LocalResultStage.DESCRIPTION -> confirmLocalHazardSave()
            LocalResultStage.ADVICE -> returnToDetecting()
            LocalResultStage.NONE -> syncToPhone()
        }
    }

    private fun handleStreamCancelAction(event: UnifiedInputSession.InputEvent) {
        when (localResultStage) {
            LocalResultStage.DESCRIPTION -> {
                if (event.trigger is UnifiedInputSession.InputTrigger.Touch &&
                    event.trigger.key == UnifiedInputSession.InputKey.DOUBLE_CLICK
                ) {
                    showLocalHazardAdvice(showSaveSuccessToast = false, countAsSaved = false)
                } else {
                    returnToDetecting()
                }
            }
            LocalResultStage.ADVICE -> finishInspectionWithReport()
            LocalResultStage.NONE -> returnToDetecting()
        }
    }

    private fun confirmLocalHazardSave() {
        showLocalHazardAdvice(showSaveSuccessToast = true, countAsSaved = true)
    }

    private fun showLocalHazardAdvice(showSaveSuccessToast: Boolean, countAsSaved: Boolean) {
        val localInfo = activeLocalHazardInfo ?: return
        if (localResultStage != LocalResultStage.DESCRIPTION) return
        localResultStage = LocalResultStage.ADVICE
        tvStreamContent.text = localInfo.advice
        lastAnalysisText = "${localInfo.description}\n\n${localInfo.advice}"
        InspectionWorkflowSession.recordAnalysis(lastAnalysisText)
        if (countAsSaved) {
            InspectionWorkflowSession.updateSummary { summary ->
                summary.copy(hasHazardCount = summary.hasHazardCount + 1)
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
        adjustStreamScrollHeight()
        scrollContent.post {
            scrollContent.scrollTo(0, 0)
            scrollContent.fullScroll(View.FOCUS_UP)
        }
        refreshInputActions()
    }

    private fun renderLocalDescriptionPrompt() {
        operationGuideStream.setGuide(
            title = "操作指引",
            content = localDescriptionPromptSubtitle(),
        )
        bottomPromptSync.setPrompt(
            title = getString(R.string.ai_inspection_local_save_prompt),
            subtitle = null,
        )
        bottomPromptSync.visibility = View.VISIBLE
    }

    private fun renderLocalAdvicePrompt() {
        operationGuideStream.setGuide(
            title = "操作指引",
            content = localContinuePromptSubtitle(),
        )
        bottomPromptSync.setPrompt(
            title = getString(R.string.ai_inspection_local_continue_prompt),
            subtitle = null,
        )
        bottomPromptSync.visibility = View.VISIBLE
    }

    private fun clearPendingHazardResult() {
        pendingHazardSnapshot = null
        pendingHazardPayload = null
    }

    private fun clearLocalHazardResultState() {
        activeLocalHazardInfo = null
        localResultStage = LocalResultStage.NONE
    }

    private fun loadLocalHazardInfos(): List<LocalHazardInfo> {
        return runCatching {
            assets.open(LOCAL_HAZARD_INFO_ASSET).use { input ->
                InputStreamReader(input, Charsets.UTF_8).use { reader ->
                    val type = object : TypeToken<List<LocalHazardInfo>>() {}.type
                    Gson().fromJson<List<LocalHazardInfo>>(reader, type).orEmpty()
                        .filter { it.item.isNotBlank() }
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
        val frame = copyLatestFrameForStreamingOrNull()
        if (!shouldDeliverStreamRequest(requestId)) {
            return
        }
        if (frame == null) {
            Log.e(TAG, "拍照失败：无可用 SDK NV21 帧")
            handleSSEError("拍照失败")
            return
        }
        try {
            imageEncodeExecutor.execute {
                val payload = buildCapturedFramePayload(frame)
                if (payload == null) {
                    Log.e(TAG, "当前 SDK NV21 帧编码失败")
                    handleSSEError("图像编码失败")
                    return@execute
                }
                InspectionWorkflowSession.recordCapture(payload.jpegBytes)
                setStreamThumbnail(payload.jpegBytes)
                encodePayloadToBase64AndSend(requestId, payload)
            }
        } catch (error: RejectedExecutionException) {
            Log.w(TAG, "image encode task rejected", error)
            handleSSEError("图像编码任务提交失败")
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
        clearPendingHazardResult()
        clearLocalHazardResultState()
        pendingStreamStart = true
        pendingAutoWindowStreamStart = false
        cancelPendingAutoStreamStart()
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
            pendingAutoWindowStreamStart = false
            return true
        }
        if (captureInProgress || inferenceRunning.get()) {
            return false
        }
        pendingStreamStart = false
        pendingAutoWindowStreamStart = false
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
                sendImageToSSE(base64Image)
            }
        }.onFailure { error ->
            Log.e(
                TAG,
                "JPEG Base64 编码失败 width=${payload.width} height=${payload.height} ts=${payload.timestamp}",
                error,
            )
            handleSSEError("图像编码失败")
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
     * 内容少时自适应，内容超过半屏时限制为半屏。
     */
    private fun adjustStreamScrollHeight() {
        scrollContent.post {
            val contentView = scrollContent.getChildAt(0) ?: return@post
            val maxH = resources.displayMetrics.heightPixels / 2
            val desiredHeight = minOf(contentView.height, maxH)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                desiredHeight,
            )
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
        canvas.drawText("测试图", 60f, 65f, paint)
        layoutStreamThumbnailCard.visibility = View.VISIBLE
        ivStreamThumbnail.setImageBitmap(bitmap)
        ivStreamThumbnail.visibility = View.VISIBLE
        currentStreamThumbnail?.takeIf { !it.isRecycled }?.recycle()
        currentStreamThumbnail = bitmap
    }

    private fun beginStreamingRequest() {
        currentEventSource?.cancel()
        currentEventSource = null
        clearLocalHazardResultState()
        activeStreamRequestId += 1
        showPage(PageState.STREAM_RESPONSE)
        clearStreamResponseUiState()
        operationGuideStream.setContent(syncPromptSubtitle())
        tvStreamContent.text = "正在准备图像..."
        streamingInProgress = true
        streamCallbackActive = true
        bottomPromptSync.visibility = View.INVISIBLE
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
     * 通过 SSE 接口发送图像数据
     */
    private fun sendImageToSSE(base64Image: String) {
        val snCode = RokidSdkManager.getSerialNumber()
        val analysisSessionId = InspectionBackendSessionId.create(snCode, prefix = "analysis")
        sessionId = deepAnalysisService.analyzeBase64(
            base64Image = base64Image,
            snCode = snCode,
            sessionId = analysisSessionId,
            callback = object : HazardDeepAnalysisService.StreamCallback {
                override fun onOpened(sessionId: String) {
                    Log.d(TAG, "SSE 连接已建立")
                    uiHandler.post {
                        tvStreamContent.text = "正在分析隐患..."
                        scrollContent.post {
                            scrollContent.scrollTo(0, 0)
                            scrollContent.fullScroll(View.FOCUS_UP)
                        }
                    }
                }

                override fun onChunk(sessionId: String, text: String) {
                    Log.d(TAG, "收到 SSE 消息: $text")
                    uiHandler.post {
                        tvStreamContent.text = text
                        adjustStreamScrollHeight()
                        scrollContent.post {
                            scrollContent.fullScroll(View.FOCUS_DOWN)
                        }
                    }
                }

                override fun onComplete(sessionId: String, fullText: String) {
                    Log.d(TAG, "SSE 连接已关闭")
                    uiHandler.post {
                        streamCallbackActive = false
                        streamingInProgress = false
                        lastAnalysisText = tvStreamContent.text.toString()
                        InspectionWorkflowSession.recordAnalysis(lastAnalysisText, sessionId)
                        bottomPromptSync.setPrompt(
                            title = getString(R.string.ai_inspection_sync_prompt),
                            subtitle = syncPromptSubtitle(),
                        )
                        bottomPromptSync.visibility = View.VISIBLE
                        refreshInputActions()
                    }
                }

                override fun onError(sessionId: String, message: String) {
                    Log.e(TAG, "SSE 连接失败 sessionId=$sessionId message=$message")
                    handleSSEError(message)
                }

                override fun onEventSourceCreated(sessionId: String, eventSource: EventSource) {
                    currentEventSource = eventSource
                }
            },
        )
    }

    /**
     * 处理 SSE 错误
     */
    private fun handleSSEError(errorMsg: String) {
        uiHandler.post {
            currentEventSource?.cancel()
            currentEventSource = null
            streamCallbackActive = false
            streamingInProgress = false
            pendingStreamStart = false
            activeStreamRequestId += 1
            tvStreamContent.text = "分析失败：$errorMsg"
            bottomPromptSync.setPrompt(
                title = getString(R.string.ai_inspection_sync_prompt),
                subtitle = syncPromptSubtitle(),
            )
            bottomPromptSync.visibility = View.VISIBLE
            refreshInputActions()
        }
    }
}
