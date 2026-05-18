package com.rokid.glass.hiddenrisk

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rokid.glass.camera.RokidFrameSource
import com.rokid.glass.utils.BitmapUtils
import com.rokid.glass.utils.dpToPx
import com.rokid.glesse.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hidden Risk 巡检主页面。
 * 页面会先完成 SDK、模型和相机链路预热，随后自动循环执行拍摄和推理。
 */
class HiddenRiskProbeActivity : BaseGlassActivity(), RokidSdkManager.Listener {

    /**
     * 底层工作流状态，主要用于日志与调度，不直接映射到产品界面。
     */
    private enum class WorkflowState {
        BINDING_SDK,
        PREPARING_CAMERA,
        LOADING_MODEL,
        READY,
        CAPTURING_SAMPLE,
        INFERRING,
        FAILED,
    }

    /**
     * 产品界面只保留四种可见状态，避免把底层链路细节直接暴露给用户。
     */
    private enum class UiState {
        LOADING,
        CAPTURING,
        ALERT,
        SAFE,
    }

    /**
     * 隐患判断结果。
     * 当前版本只保留占位逻辑：检测框大于 0 时默认判定为异常。
     */
    private enum class HazardDecision {
        NO_DETECTION,
        ABNORMAL,
        NORMAL,
    }

    private data class CapturedFramePayload(
        val jpegBytes: ByteArray,
        val width: Int,
        val height: Int,
        val timestamp: Long,
    )

    private lateinit var imageLoadingIndicator: ImageView
    private lateinit var imageResultIcon: ImageView
    private lateinit var textStatusMessage: TextView
    private lateinit var viewRecordingIndicator: View
    private lateinit var rootContainer: View
    private val uiHandler = Handler(Looper.getMainLooper())
    private val nativeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val inferenceRunning = AtomicBoolean(false)
    private lateinit var pressureMonitor: InferencePressureMonitor

    private var hiddenRiskNcnn: HiddenRiskNcnn? = null
    private var workflowState = WorkflowState.BINDING_SDK
    private var statusMessage = "等待 Rokid SDK 就绪"
    private var workflowErrorMessage: String? = null
    private var nativeInitError: String? = null
    private var isActivityResumed = false
    private var isWorkflowActive = false
    private var destroyed = false
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
    private var lastConsumedFrameTimestamp = 0L

    private var latestNativeSnapshot: NativeInferenceStats? = null
    private var hazardCaptureService: HazardCaptureService? = null
    private var targetBackend = BACKEND_GPU
    private var targetGpuProfile = GPU_PROFILE_BALANCED_FP16
    private var targetInputSize = DEFAULT_TARGET_INPUT_SIZE
    private var targetResultLimitOverride = DEFAULT_RESULT_LIMIT_OVERRIDE
    private var targetDebugCompareEnabled = false
    private var sampleImagePath: String? = null
    private var sampleSourceBitmap: Bitmap? = null
    private var autoCaptureLoopEnabled = true
    private var autoCaptureScheduled = false
    private var uiState = UiState.LOADING

    private val captureTimeoutRunnable = Runnable {
        if (!captureInProgress || destroyed) {
            return@Runnable
        }
        captureInProgress = false
        failWorkflow("样图拍摄超时")
    }

    private val captureDelayRunnable = Runnable {
        captureDelayScheduled = false
        if (destroyed || !pendingCaptureRequest || captureInProgress) {
            return@Runnable
        }
        startSampleCaptureIfNeeded()
    }

    private val autoCaptureRunnable = Runnable {
        autoCaptureScheduled = false
        logWorkflowCheckpoint("autoCaptureRunnable fired")
        if (!shouldAutoCaptureNow()) {
            logWorkflowCheckpoint("autoCaptureRunnable deferred")
            scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
            return@Runnable
        }
        pendingCaptureRequest = true
        logWorkflowCheckpoint("autoCaptureRunnable requestCapture")
        startSampleCaptureIfNeeded()
    }

    private val resultDismissRunnable = Runnable {
        if (destroyed) {
            return@Runnable
        }
        if (uiState == UiState.ALERT || uiState == UiState.SAFE) {
            showCapturingUi()
        }
    }

    private val loadingRotateAnimation: RotateAnimation by lazy {
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

    private val recordingBlinkAnimation: AlphaAnimation by lazy {
        AlphaAnimation(1f, 0.2f).apply {
            duration = 700L
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
            interpolator = LinearInterpolator()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetBackend = resolveTargetBackend(intent?.getIntExtra(EXTRA_BACKEND, BACKEND_GPU))
        targetGpuProfile = resolveTargetGpuProfile(intent?.getIntExtra(EXTRA_GPU_PROFILE, GPU_PROFILE_BALANCED_FP16))
        targetInputSize = resolveTargetInputSize(intent?.getIntExtra(EXTRA_TARGET_INPUT_SIZE, DEFAULT_TARGET_INPUT_SIZE))
        targetResultLimitOverride = resolveResultLimitOverride(
            intent?.getIntExtra(EXTRA_MAX_RESULTS_OVERRIDE, DEFAULT_RESULT_LIMIT_OVERRIDE),
        )
        targetDebugCompareEnabled = intent?.getBooleanExtra(EXTRA_DEBUG_COMPARE, false) == true
        sampleImagePath = intent?.getStringExtra(EXTRA_SAMPLE_IMAGE_PATH)?.takeIf { it.isNotBlank() }
        setContentView(R.layout.activity_hidden_risk_probe)
        pressureMonitor = InferencePressureMonitor(applicationContext, TAG)
        rootContainer = findViewById(R.id.rootContainer)
        imageLoadingIndicator = findViewById(R.id.imageLoadingIndicator)
        imageResultIcon = findViewById(R.id.imageResultIcon)
        textStatusMessage = findViewById(R.id.textStatusMessage)
        viewRecordingIndicator = findViewById(R.id.viewRecordingIndicator)
        rootContainer.post { adjustRecordingIndicatorPosition() }

        RokidSdkManager.initialize(application as Application)
        RokidSdkManager.addListener(this)
        RokidSdkManager.ensureInitialized()
        showLoadingUi(getString(R.string.hidden_risk_loading))
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        pressureMonitor.startSession()
        logWorkflowCheckpoint("onResume")
        rootContainer.post { adjustRecordingIndicatorPosition() }
        renderCurrentUi()
        ensureMediaPermissionOrStart()
        scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
    }

    override fun onStart() {
        super.onStart()
        isWorkflowActive = true
        logWorkflowCheckpoint("onStart")
    }

    override fun onPause() {
        logWorkflowCheckpoint("onPause begin")
        isActivityResumed = false
        pressureMonitor.clearSession()
        uiHandler.removeCallbacks(captureDelayRunnable)
        uiHandler.removeCallbacks(autoCaptureRunnable)
        captureDelayScheduled = false
        autoCaptureScheduled = false
        frameStreamInitializing = false
        frameStreamReady = false
        frameStreamReadyAtElapsedMs = 0L
        lastConsumedFrameTimestamp = 0L
        RokidFrameSource.releaseAll()
        super.onPause()
        logWorkflowCheckpoint("onPause end")
    }

    override fun onStop() {
        logWorkflowCheckpoint("onStop begin")
        isWorkflowActive = false
        uiHandler.removeCallbacks(captureDelayRunnable)
        uiHandler.removeCallbacks(autoCaptureRunnable)
        captureDelayScheduled = false
        autoCaptureScheduled = false
        super.onStop()
        logWorkflowCheckpoint("onStop end")
    }

    override fun onDestroy() {
        destroyed = true
        pressureMonitor.clearSession()
        uiHandler.removeCallbacks(captureDelayRunnable)
        uiHandler.removeCallbacks(captureTimeoutRunnable)
        uiHandler.removeCallbacks(autoCaptureRunnable)
        uiHandler.removeCallbacks(resultDismissRunnable)
        frameStreamInitializing = false
        frameStreamReady = false
        frameStreamReadyAtElapsedMs = 0L
        lastConsumedFrameTimestamp = 0L
        RokidFrameSource.releaseAll()
        RokidSdkManager.removeListener(this)
        sampleSourceBitmap?.recycle()
        sampleSourceBitmap = null
        hiddenRiskNcnn?.clearFrameState()
        hazardCaptureService?.shutdown()
        nativeExecutor.shutdown()
        runCatching { nativeExecutor.awaitTermination(2, TimeUnit.SECONDS) }
        if (isFinishing && !isChangingConfigurations) {
            RokidSdkManager.release()
        }
        super.onDestroy()
    }

    override fun onSdkStateChanged(state: RokidSdkManager.SdkState) {
        Log.i(TAG, "sdk state changed state=$state error=${RokidSdkManager.lastErrorMessage ?: "N/A"}")
        sdkReadyAtElapsedMs = if (state == RokidSdkManager.SdkState.READY) {
            sdkReadyAtElapsedMs.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
        } else {
            0L
        }
        uiHandler.post {
            maybeAdvanceWorkflow()
        }
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        if (keyEvent == GlassKeyEvent.KEYCODE_CLICK) {
            requestCaptureAndInferOnce()
            return true
        }
        return super.onGlassKeyEvent(keyEvent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MEDIA_PERMISSION) {
            return
        }

        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        Log.i(TAG, "permission result granted=$granted permissions=${permissions.joinToString()}")
        if (granted) {
            workflowErrorMessage = null
            statusMessage = "媒体权限已授权"
            maybeAdvanceWorkflow()
        } else {
            failWorkflow("缺少相机或媒体读取权限")
        }
    }

    private fun ensureMediaPermissionOrStart() {
        logWorkflowCheckpoint("ensureMediaPermissionOrStart")
        if (!hasRequiredPermissions()) {
            workflowState = WorkflowState.BINDING_SDK
            statusMessage = "等待相机和媒体权限"
            showLoadingUi(getString(R.string.hidden_risk_loading))
            logWorkflowCheckpoint("ensureMediaPermissionOrStart requestPermissions")
            if (!mediaPermissionRequested) {
                mediaPermissionRequested = true
                ActivityCompat.requestPermissions(this, requiredPermissions(), REQUEST_MEDIA_PERMISSION)
            }
            return
        }

        maybeAdvanceWorkflow()
    }

    /**
     * Rokid 眼镜端点击右触控板时，只触发一次拍照和一次推理。
     * 当前工程已经在 BaseGlassActivity 里把 Rokid 按键广播统一映射到了 KEYCODE_CLICK。
     */
    private fun requestCaptureAndInferOnce() {
        if (destroyed) {
            return
        }
        if (!hasRequiredPermissions()) {
            ensureMediaPermissionOrStart()
            return
        }
        when (RokidSdkManager.state) {
            RokidSdkManager.SdkState.FAILED -> {
                failWorkflow(RokidSdkManager.lastErrorMessage ?: "Rokid SDK 初始化失败")
                return
            }

            RokidSdkManager.SdkState.READY -> Unit

            else -> {
                workflowState = WorkflowState.BINDING_SDK
                statusMessage = "等待 Rokid SDK 就绪"
                showLoadingUi(getString(R.string.hidden_risk_loading))
                return
            }
        }
        if (modelLoading) {
            workflowState = WorkflowState.LOADING_MODEL
            statusMessage = "模型加载中，请稍后再按右触控板"
            showLoadingUi(getString(R.string.hidden_risk_loading))
            return
        }
        if (captureInProgress || captureDelayScheduled || frameStreamInitializing) {
            workflowState = if (captureInProgress) WorkflowState.CAPTURING_SAMPLE else WorkflowState.PREPARING_CAMERA
            statusMessage = "正在准备相机帧，请稍候"
            if (!isResultUiActive()) {
                showLoadingUi(getString(R.string.hidden_risk_loading))
            }
            return
        }
        if (inferenceRunning.get()) {
            workflowState = WorkflowState.INFERRING
            statusMessage = "正在推理，请稍候再按右触控板"
            if (!isResultUiActive()) {
                showCapturingUi()
            }
            return
        }

        val local = ensureNativeEngine()
        if (local == null) {
            failWorkflow(nativeInitError ?: "原生引擎不可用")
            return
        }
        if (!modelLoaded) {
            pendingCaptureRequest = true
            startModelLoadIfNeeded(local)
            return
        }

        workflowErrorMessage = null
        pendingCaptureRequest = true
        startSampleCaptureIfNeeded()
    }

    /**
     * 页面进入后只预热 SDK 与模型，不自动拍照。
     * 准备完成后会自动循环执行拍摄和推理。
     */
    private fun maybeAdvanceWorkflow() {
        logWorkflowCheckpoint("maybeAdvanceWorkflow enter")
        if (destroyed) {
            logWorkflowCheckpoint("maybeAdvanceWorkflow skip lifecycle")
            return
        }
        if (!isActivityResumed || !isWorkflowActive) {
            logWorkflowCheckpoint("maybeAdvanceWorkflow wait foreground")
            return
        }
        if (!hasRequiredPermissions()) {
            statusMessage = "等待相机和媒体权限"
            showLoadingUi(getString(R.string.hidden_risk_loading))
            logWorkflowCheckpoint("maybeAdvanceWorkflow wait permissions")
            return
        }

        when (RokidSdkManager.state) {
            RokidSdkManager.SdkState.FAILED -> {
                failWorkflow(RokidSdkManager.lastErrorMessage ?: "Rokid SDK 初始化失败")
                return
            }

            RokidSdkManager.SdkState.READY -> Unit

            else -> {
                workflowState = WorkflowState.BINDING_SDK
                statusMessage = "等待 Rokid SDK 就绪"
                showLoadingUi(getString(R.string.hidden_risk_loading))
                logWorkflowCheckpoint("maybeAdvanceWorkflow wait sdk")
                return
            }
        }

        val local = ensureNativeEngine()
        if (local == null) {
            logWorkflowCheckpoint("maybeAdvanceWorkflow native engine unavailable")
            failWorkflow(nativeInitError ?: "原生引擎不可用")
            return
        }
        if (!modelLoaded) {
            logWorkflowCheckpoint("maybeAdvanceWorkflow startModelLoad")
            startModelLoadIfNeeded(local)
            return
        }
        if (pendingCaptureRequest) {
            logWorkflowCheckpoint("maybeAdvanceWorkflow startSampleCapture")
            startSampleCaptureIfNeeded()
            return
        }

        if (!captureInProgress && !inferenceRunning.get()) {
            workflowState = WorkflowState.READY
            statusMessage = "自动抓帧已就绪"
            if (!isResultUiActive()) {
                showCapturingUi()
            }
        }
        scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
        logWorkflowCheckpoint("maybeAdvanceWorkflow ready")
    }

    private fun startModelLoadIfNeeded(local: HiddenRiskNcnn) {
        logWorkflowCheckpoint("startModelLoadIfNeeded enter")
        if (modelLoading) {
            workflowState = WorkflowState.LOADING_MODEL
            statusMessage = "模型加载中"
            showLoadingUi(getString(R.string.hidden_risk_loading))
            logWorkflowCheckpoint("startModelLoadIfNeeded alreadyLoading")
            return
        }

        modelLoading = true
        workflowState = WorkflowState.LOADING_MODEL
        statusMessage = "加载 ${targetBackendLabel()} 模型 (${targetProfileLabel()} / ${targetInputSize})"
        workflowErrorMessage = null
        showLoadingUi(getString(R.string.hidden_risk_loading))
        logWorkflowCheckpoint("startModelLoadIfNeeded submit")

        if (!submitNativeTask {
                local.setDebugResultLimit(targetResultLimitOverride)
                local.setDebugCompareEnabled(targetDebugCompareEnabled)
                val success = runCatching {
                    local.loadModel(assets, targetBackend, targetGpuProfile, targetInputSize)
                }
                    .onFailure { error -> Log.e(TAG, "loadModel failed", error) }
                    .getOrDefault(false)
                val snapshot = runCatching { local.getLatestInferenceStats() }.getOrNull()
                uiHandler.post {
                    modelLoading = false
                    latestNativeSnapshot = snapshot
                    if (destroyed) {
                        logWorkflowCheckpoint("startModelLoadIfNeeded completed after destroy")
                        return@post
                    }
                    if (success) {
                        modelLoaded = true
                        workflowErrorMessage = null
                        statusMessage = "模型已加载，准备自动抓帧"
                        logWorkflowCheckpoint("startModelLoadIfNeeded success")
                        scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
                        maybeAdvanceWorkflow()
                    } else {
                        logWorkflowCheckpoint("startModelLoadIfNeeded failed")
                        failWorkflow(snapshot?.errorMessage ?: "${targetBackendLabel()} 模型加载失败")
                    }
                }
            }) {
            modelLoading = false
            failWorkflow("模型任务提交失败")
        }
    }

    private fun startSampleCaptureIfNeeded() {
        if (!pendingCaptureRequest) {
            return
        }
        if (captureInProgress) {
            workflowState = WorkflowState.CAPTURING_SAMPLE
            statusMessage = "正在拍照，请稍候"
            if (!isResultUiActive()) {
                showCapturingUi()
            }
            return
        }
        if (sampleImagePath.isNullOrBlank() && (!isActivityResumed || !isWorkflowActive)) {
            logWorkflowCheckpoint("startSampleCaptureIfNeeded wait foreground")
            return
        }

        if (!sampleImagePath.isNullOrBlank()) {
            startSampleBitmapInferenceIfNeeded()
            return
        }

        if (frameStreamReady && !RokidFrameSource.isCroppedFrameStreamWarm()) {
            frameStreamReady = false
            frameStreamReadyAtElapsedMs = 0L
        }

        if (!frameStreamReady) {
            workflowState = WorkflowState.PREPARING_CAMERA
            statusMessage = if (frameStreamInitializing) "初始化相机帧流" else "准备相机帧流"
            if (!isResultUiActive()) {
                showLoadingUi(getString(R.string.hidden_risk_loading))
            }
            if (!frameStreamInitializing) {
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
                            logWorkflowCheckpoint("frameStream init completed in background")
                            return@post
                        }
                        if (!success) {
                            failWorkflow("相机帧流初始化失败")
                            return@post
                        }
                        val readyElapsedMs = frameStreamReadyAtElapsedMs.takeIf { it > 0L } ?: sdkReadyAtElapsedMs
                        val warmupRemainingMs = when {
                            readyElapsedMs <= 0L -> CAPTURE_WARMUP_MS
                            else -> (CAPTURE_WARMUP_MS - (SystemClock.elapsedRealtime() - readyElapsedMs))
                                .coerceAtLeast(0L)
                        }
                        if (warmupRemainingMs > 0L) {
                            statusMessage = "等待相机帧流稳定"
                            if (!isResultUiActive()) {
                                showLoadingUi(getString(R.string.hidden_risk_loading))
                            }
                            uiHandler.removeCallbacks(captureDelayRunnable)
                            captureDelayScheduled = true
                            uiHandler.postDelayed(captureDelayRunnable, warmupRemainingMs)
                        } else {
                            startSampleCaptureIfNeeded()
                        }
                    }
                }
            }
            return
        }

        val readyElapsedMs = frameStreamReadyAtElapsedMs.takeIf { it > 0L } ?: sdkReadyAtElapsedMs
        val warmupRemainingMs = when {
            readyElapsedMs <= 0L -> CAPTURE_WARMUP_MS
            else -> (CAPTURE_WARMUP_MS - (SystemClock.elapsedRealtime() - readyElapsedMs)).coerceAtLeast(0L)
        }
        if (warmupRemainingMs > 0L) {
            workflowState = WorkflowState.PREPARING_CAMERA
            statusMessage = "等待相机帧流稳定"
            if (!isResultUiActive()) {
                showLoadingUi(getString(R.string.hidden_risk_loading))
            }
            uiHandler.removeCallbacks(captureDelayRunnable)
            captureDelayScheduled = true
            uiHandler.postDelayed(captureDelayRunnable, warmupRemainingMs)
            return
        }
        if (captureDelayScheduled) {
            captureDelayScheduled = false
            uiHandler.removeCallbacks(captureDelayRunnable)
        }
        pendingCaptureRequest = false
        captureInProgress = true
        workflowState = WorkflowState.CAPTURING_SAMPLE
        statusMessage = "开始拍照"
        workflowErrorMessage = null
        clearLatestInferenceState()
        hiddenRiskNcnn?.clearFrameState()
        if (!isResultUiActive()) {
            showCapturingUi()
        }
        uiHandler.removeCallbacks(captureTimeoutRunnable)
        uiHandler.postDelayed(captureTimeoutRunnable, CAPTURE_TIMEOUT_MS)
        val captureRequestStartMs = SystemClock.elapsedRealtime()

        Log.i(
            TAG,
            "copyNextFrameOrNull request source=RokidFrameSource backend=${targetBackendLabel()} profile=${targetProfileLabel()} targetSize=$targetInputSize",
        )
        val frame = copyNextFrameOrNull()
        if (destroyed || !captureInProgress) {
            return
        }
        if (frame == null) {
            captureInProgress = false
            uiHandler.removeCallbacks(captureTimeoutRunnable)
                Log.w(
                    TAG,
                    "copyNextFrameOrNull failed elapsed=${SystemClock.elapsedRealtime() - captureRequestStartMs}ms warm=${RokidFrameSource.isCroppedFrameStreamWarm()}",
                )
            failWorkflow("预览帧采集失败")
            return
        }
        captureInProgress = false
        uiHandler.removeCallbacks(captureTimeoutRunnable)
        Log.i(
            TAG,
            "copyNextFrameOrNull submitted width=${frame.width} height=${frame.height} timestamp=${frame.timestamp} elapsed=${SystemClock.elapsedRealtime() - captureRequestStartMs}ms warm=${RokidFrameSource.isCroppedFrameStreamWarm()}",
        )
        triggerInference(frame)
    }

    private fun startSampleBitmapInferenceIfNeeded() {
        val bitmapPath = sampleImagePath
        if (bitmapPath.isNullOrBlank()) {
            failWorkflow("样图路径为空")
            return
        }
        val sourceBitmap = loadSampleBitmapIfNeeded(bitmapPath) ?: run {
            failWorkflow("样图加载失败: $bitmapPath")
            return
        }
        val workingBitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true) ?: run {
            failWorkflow("样图复制失败")
            return
        }

        pendingCaptureRequest = false
        captureInProgress = true
        workflowState = WorkflowState.CAPTURING_SAMPLE
        statusMessage = "加载样图推理"
        workflowErrorMessage = null
        clearLatestInferenceState()
        hiddenRiskNcnn?.clearFrameState()
        if (!isResultUiActive()) {
            showCapturingUi()
        }

        uiHandler.post {
            if (destroyed) {
                workingBitmap.recycle()
                return@post
            }
            captureInProgress = false
            triggerBitmapInference(workingBitmap)
        }
    }

    private fun triggerInference(frame: RokidFrameSource.CroppedNv21Frame) {
        val local = hiddenRiskNcnn ?: run {
            failWorkflow("原生引擎不可用")
            return
        }
        if (!inferenceRunning.compareAndSet(false, true)) {
            return
        }

        workflowState = WorkflowState.INFERRING
        statusMessage = "正在执行 NV21 推理"
        if (!isResultUiActive()) {
            showCapturingUi()
        }

        if (!submitNativeTask {
                Log.i(
                    TAG,
                    "inference start width=${frame.width} height=${frame.height} timestamp=${frame.timestamp}",
                )
                val success = runCatching {
                    local.submitNv21(
                        frame.data,
                        frame.width,
                        frame.height,
                    )
                }.onFailure { error ->
                    Log.e(TAG, "submitNv21 failed", error)
                }.getOrDefault(false)
                Log.i(TAG, "submitNv21 finished success=$success")

                val snapshot = runCatching { local.getLatestInferenceStats() }
                    .onFailure { error -> Log.w(TAG, "getLatestInferenceStats failed", error) }
                    .getOrNull()
                Log.i(
                    TAG,
                    "stats snapshot backend=${snapshot?.backendName ?: "N/A"} inferenceMs=${snapshot?.inferenceTimeMs ?: -1L} detections=${snapshot?.detectionCount ?: -1} preLimitDetections=${snapshot?.preLimitDetectionCount ?: -1} errorStage=${snapshot?.errorStage ?: "N/A"} errorCode=${snapshot?.errorCode ?: -1}",
                )
                pressureMonitor.logSnapshot(
                    workflowState = workflowState.name,
                    stats = snapshot,
                    success = success,
                )
                val hazardDecision = evaluateHazardDecision(snapshot)

                val capturedPayload = if (hazardDecision == HazardDecision.ABNORMAL) {
                    buildCapturedFramePayload(frame)
                } else {
                    null
                }

                if (hazardDecision == HazardDecision.ABNORMAL) {
                    ensureHazardCaptureService().saveHazardCapture(capturedPayload?.jpegBytes, snapshot)
                }

                uiHandler.post {
                    inferenceRunning.set(false)
                    latestNativeSnapshot = snapshot
                    if (destroyed) {
                        return@post
                    }
                    if (success) {
                        workflowErrorMessage = null
                        workflowState = WorkflowState.READY
                        statusMessage = "推理完成，等待下一轮自动抓帧"
                        applyHazardDecision(hazardDecision)
                        scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
                    } else {
                        failWorkflow(snapshot?.errorMessage ?: "预览帧推理失败")
                    }
                }
            }) {
            inferenceRunning.set(false)
            failWorkflow("推理任务提交失败")
        }
    }

    private fun copyNextFrameOrNull(): RokidFrameSource.CroppedNv21Frame? {
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

    private fun triggerBitmapInference(bitmap: Bitmap) {
        val local = hiddenRiskNcnn ?: run {
            bitmap.recycle()
            failWorkflow("原生引擎不可用")
            return
        }
        if (!inferenceRunning.compareAndSet(false, true)) {
            bitmap.recycle()
            return
        }

        workflowState = WorkflowState.INFERRING
        statusMessage = "正在执行样图推理"
        if (!isResultUiActive()) {
            showCapturingUi()
        }

        if (!submitNativeTask {
                Log.i(TAG, "bitmap inference start width=${bitmap.width} height=${bitmap.height} path=$sampleImagePath")
                val success = runCatching {
                    local.submitBitmap(bitmap)
                }.onFailure { error ->
                    Log.e(TAG, "submitBitmap failed", error)
                }.getOrDefault(false)
                Log.i(TAG, "submitBitmap finished success=$success")

                val snapshot = runCatching { local.getLatestInferenceStats() }
                    .onFailure { error -> Log.w(TAG, "getLatestInferenceStats failed", error) }
                    .getOrNull()
                Log.i(
                    TAG,
                    "bitmap stats snapshot backend=${snapshot?.backendName ?: "N/A"} inferenceMs=${snapshot?.inferenceTimeMs ?: -1L} detections=${snapshot?.detectionCount ?: -1} preLimitDetections=${snapshot?.preLimitDetectionCount ?: -1} errorStage=${snapshot?.errorStage ?: "N/A"} errorCode=${snapshot?.errorCode ?: -1}",
                )
                pressureMonitor.logSnapshot(
                    workflowState = workflowState.name,
                    stats = snapshot,
                    success = success,
                )
                val hazardDecision = evaluateHazardDecision(snapshot)

                uiHandler.post {
                    inferenceRunning.set(false)
                    latestNativeSnapshot = snapshot
                    if (destroyed) {
                        bitmap.recycle()
                        return@post
                    }
                    if (success) {
                        workflowErrorMessage = null
                        workflowState = WorkflowState.READY
                        statusMessage = "样图推理完成，等待下一轮自动抓帧"
                        bitmap.recycle()
                        applyHazardDecision(hazardDecision)
                        scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
                    } else {
                        bitmap.recycle()
                        failWorkflow(snapshot?.errorMessage ?: "样图推理失败")
                    }
                }
            }) {
            inferenceRunning.set(false)
            bitmap.recycle()
            failWorkflow("样图推理任务提交失败")
        }
    }

    private fun failWorkflow(message: String) {
        captureInProgress = false
        pendingCaptureRequest = false
        captureDelayScheduled = false
        autoCaptureScheduled = false
        frameStreamInitializing = false
        frameStreamReady = false
        frameStreamReadyAtElapsedMs = 0L
        lastConsumedFrameTimestamp = 0L
        modelLoading = false
        workflowState = WorkflowState.FAILED
        workflowErrorMessage = message
        statusMessage = message
        uiHandler.removeCallbacks(captureDelayRunnable)
        uiHandler.removeCallbacks(captureTimeoutRunnable)
        uiHandler.removeCallbacks(autoCaptureRunnable)
        uiHandler.removeCallbacks(resultDismissRunnable)
        RokidFrameSource.stopFrameStream()
        clearLatestInferenceState()
        showLoadingUi(message, spinning = false)
    }

    private fun clearLatestInferenceState() {
        latestNativeSnapshot = null
    }

    /**
     * 当前隐患判断先留空，默认只要 detection 大于 0 就输出“有异常”。
     */
    private fun evaluateHazardDecision(snapshot: NativeInferenceStats?): HazardDecision {
        val detectionCount = snapshot?.detectionCount ?: 0
        if (detectionCount <= 0) {
            return HazardDecision.NO_DETECTION
        }
        return if (judgePotentialHazard(snapshot)) {
            HazardDecision.ABNORMAL
        } else {
            HazardDecision.NORMAL
        }
    }

    private fun judgePotentialHazard(snapshot: NativeInferenceStats?): Boolean {
        return true
    }

    private fun applyHazardDecision(decision: HazardDecision) {
        when (decision) {
            HazardDecision.NO_DETECTION -> {
                if (!isResultUiActive()) {
                    showCapturingUi()
                }
            }

            HazardDecision.ABNORMAL -> showAlertUi()
            HazardDecision.NORMAL -> showSafeUi()
        }
    }

    private fun isResultUiActive(): Boolean {
        return uiState == UiState.ALERT || uiState == UiState.SAFE
    }

    private fun renderCurrentUi() {
        when (uiState) {
            UiState.LOADING -> showLoadingUi(textStatusMessage.text?.toString().orEmpty(), spinning = workflowState != WorkflowState.FAILED)
            UiState.CAPTURING -> showCapturingUi()
            UiState.ALERT -> showAlertUi()
            UiState.SAFE -> showSafeUi()
        }
    }

    private fun showLoadingUi(message: String, spinning: Boolean = true) {
        uiState = UiState.LOADING
        uiHandler.removeCallbacks(resultDismissRunnable)
        renderProductUi(
            message = message,
            resultIconResId = null,
            showLoadingIndicator = spinning,
            showRecordingIndicator = false,
        )
    }

    private fun showCapturingUi() {
        uiState = UiState.CAPTURING
        uiHandler.removeCallbacks(resultDismissRunnable)
        renderProductUi(
            message = null,
            resultIconResId = null,
            showLoadingIndicator = false,
            showRecordingIndicator = true,
        )
    }

    private fun showAlertUi() {
        if (uiState != UiState.ALERT) {
            uiState = UiState.ALERT
            renderProductUi(
                message = getString(R.string.hidden_risk_alert_message),
                resultIconResId = R.drawable.hidden_risk_alert,
                showLoadingIndicator = false,
                showRecordingIndicator = false,
            )
        }
        scheduleResultDismiss()
    }

    private fun showSafeUi() {
        if (uiState != UiState.SAFE) {
            uiState = UiState.SAFE
            renderProductUi(
                message = getString(R.string.hidden_risk_safe_message),
                resultIconResId = R.drawable.hidden_risk_safe,
                showLoadingIndicator = false,
                showRecordingIndicator = false,
            )
        }
        scheduleResultDismiss()
    }

    private fun scheduleResultDismiss() {
        uiHandler.removeCallbacks(resultDismissRunnable)
        uiHandler.postDelayed(resultDismissRunnable, RESULT_HOLD_MS)
    }

    private fun renderProductUi(
        message: String?,
        resultIconResId: Int?,
        showLoadingIndicator: Boolean,
        showRecordingIndicator: Boolean,
    ) {
        textStatusMessage.text = message.orEmpty()
        textStatusMessage.visibility = if (message.isNullOrBlank()) View.GONE else View.VISIBLE

        if (resultIconResId == null) {
            imageResultIcon.setImageDrawable(null)
            imageResultIcon.visibility = View.GONE
        } else {
            imageResultIcon.setImageResource(resultIconResId)
            imageResultIcon.visibility = View.VISIBLE
        }

        imageLoadingIndicator.visibility = if (showLoadingIndicator) View.VISIBLE else View.GONE
        if (showLoadingIndicator) {
            imageLoadingIndicator.startAnimation(loadingRotateAnimation)
        } else {
            imageLoadingIndicator.clearAnimation()
        }

        viewRecordingIndicator.visibility = if (showRecordingIndicator) View.VISIBLE else View.GONE
        if (showRecordingIndicator) {
            viewRecordingIndicator.startAnimation(recordingBlinkAnimation)
        } else {
            viewRecordingIndicator.clearAnimation()
        }
    }

    /**
     * 眼镜显示区域顶部会有裁切风险，录制红点需要根据当前可见高度往下沉一点。
     */
    private fun adjustRecordingIndicatorPosition() {
        val layoutParams = viewRecordingIndicator.layoutParams as? FrameLayout.LayoutParams ?: return
        val metrics: DisplayMetrics = resources.displayMetrics
        val measuredHeight = rootContainer.height.takeIf { it > 0 } ?: metrics.heightPixels
        val measuredWidth = rootContainer.width.takeIf { it > 0 } ?: metrics.widthPixels
        val shortEdge = minOf(measuredHeight, measuredWidth)
        val safeTopMarginPx = maxOf(dpToPx(28f).toInt(), (shortEdge * RECORDING_DOT_TOP_RATIO).toInt())
        val safeEndMarginPx = maxOf(dpToPx(20f).toInt(), (measuredWidth * RECORDING_DOT_END_RATIO).toInt())
        if (layoutParams.topMargin == safeTopMarginPx && layoutParams.marginEnd == safeEndMarginPx) {
            return
        }
        layoutParams.topMargin = safeTopMarginPx
        layoutParams.marginEnd = safeEndMarginPx
        viewRecordingIndicator.layoutParams = layoutParams
    }

    private fun ensureNativeEngine(): HiddenRiskNcnn? {
        hiddenRiskNcnn?.let { return it }
        return runCatching { HiddenRiskNcnn() }
            .onFailure { error ->
                nativeInitError = error.message ?: error.javaClass.simpleName
                Log.e(TAG, "HiddenRiskNcnn initialize failed", error)
            }
            .getOrNull()
            ?.also {
                hiddenRiskNcnn = it
                nativeInitError = null
            }
    }

    private fun ensureHazardCaptureService(): HazardCaptureService {
        hazardCaptureService?.let { return it }
        return HazardCaptureService(this).also {
            hazardCaptureService = it
        }
    }

    private fun loadSampleBitmapIfNeeded(path: String): Bitmap? {
        sampleSourceBitmap?.let { return it }
        return runCatching { BitmapFactory.decodeFile(path) }
            .onFailure { error -> Log.e(TAG, "decode sample bitmap failed path=$path", error) }
            .getOrNull()
            ?.also { decoded ->
                sampleSourceBitmap = decoded
                Log.i(TAG, "sample bitmap loaded path=$path width=${decoded.width} height=${decoded.height}")
            }
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(): Array<String> = buildList {
        if (sampleImagePath.isNullOrBlank()) {
            add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private fun submitNativeTask(task: () -> Unit): Boolean {
        return try {
            nativeExecutor.execute(task)
            true
        } catch (error: RejectedExecutionException) {
            Log.w(TAG, "native task rejected", error)
            false
        }
    }

    private fun targetBackendLabel(): String = when (targetBackend) {
        BACKEND_CPU -> "CPU"
        BACKEND_GPU -> "System Vulkan"
        BACKEND_TURNIP -> "Turnip"
        else -> "Unknown"
    }

    private fun targetProfileLabel(): String = when (targetGpuProfile) {
        GPU_PROFILE_SAFE_FP32 -> "Safe FP32"
        GPU_PROFILE_BALANCED_FP16 -> "Balanced FP16"
        GPU_PROFILE_NO_PACKING_FP32 -> "No Packing FP32"
        else -> "Unknown"
    }

    private fun formatResultLimit(detectionCount: Int?, preLimitDetectionCount: Int?): String {
        val current = detectionCount ?: -1
        val raw = preLimitDetectionCount ?: -1
        return if (raw >= 0 && current >= 0) {
            "$targetResultLimitOverride ($current/$raw)"
        } else {
            targetResultLimitOverride.toString()
        }
    }

    private fun shouldAutoCaptureNow(): Boolean {
        if (!autoCaptureLoopEnabled || destroyed) {
            return false
        }
        if (!isActivityResumed || !isWorkflowActive) {
            return false
        }
        if (!hasRequiredPermissions()) {
            return false
        }
        if (RokidSdkManager.state != RokidSdkManager.SdkState.READY) {
            return false
        }
        if (workflowState == WorkflowState.FAILED) {
            return false
        }
        if (!modelLoaded || modelLoading) {
            return false
        }
        if (captureInProgress || captureDelayScheduled || frameStreamInitializing || pendingCaptureRequest) {
            return false
        }
        if (inferenceRunning.get()) {
            return false
        }
        return true
    }

    private fun scheduleAutoCaptureIfNeeded(delayMs: Long) {
        if (!autoCaptureLoopEnabled || autoCaptureScheduled || destroyed || !isActivityResumed || !isWorkflowActive) {
            logWorkflowCheckpoint("scheduleAutoCaptureIfNeeded skip delayMs=$delayMs")
            return
        }
        autoCaptureScheduled = true
        Log.i(TAG, "schedule auto capture delayMs=$delayMs")
        uiHandler.postDelayed(autoCaptureRunnable, delayMs)
    }

    private fun logWorkflowCheckpoint(reason: String) {
        val permissionReady = runCatching { hasRequiredPermissions() }.getOrDefault(false)
        Log.i(
            TAG,
            "workflow checkpoint=$reason state=$workflowState status=$statusMessage resumed=$isActivityResumed active=$isWorkflowActive destroyed=$destroyed permissions=$permissionReady sdk=${RokidSdkManager.state} modelLoaded=$modelLoaded modelLoading=$modelLoading pending=$pendingCaptureRequest capture=$captureInProgress captureDelay=$captureDelayScheduled frameInit=$frameStreamInitializing frameReady=$frameStreamReady infer=${inferenceRunning.get()} autoScheduled=$autoCaptureScheduled",
        )
    }

    private fun resolveTargetBackend(value: Int?): Int {
        return when (value) {
            BACKEND_CPU,
            BACKEND_GPU,
            BACKEND_TURNIP -> value
            else -> BACKEND_GPU
        }
    }

    private fun resolveTargetGpuProfile(value: Int?): Int {
        return when (value) {
            GPU_PROFILE_SAFE_FP32,
            GPU_PROFILE_BALANCED_FP16,
            GPU_PROFILE_NO_PACKING_FP32 -> value
            else -> GPU_PROFILE_BALANCED_FP16
        }
    }

    private fun resolveTargetInputSize(value: Int?): Int {
        return value?.takeIf { it > 0 } ?: DEFAULT_TARGET_INPUT_SIZE
    }

    private fun resolveResultLimitOverride(value: Int?): Int {
        return value ?: DEFAULT_RESULT_LIMIT_OVERRIDE
    }

    companion object {
        private const val TAG = "HiddenRiskProbe"
        private const val REQUEST_MEDIA_PERMISSION = 101
        private const val CAPTURE_TIMEOUT_MS = 15000L
        private const val CAPTURE_WARMUP_MS = 1200L
        private const val RESULT_HOLD_MS = 2000L
        private const val RECORDING_DOT_TOP_RATIO = 0.14f
        private const val RECORDING_DOT_END_RATIO = 0.06f
        private const val STALE_FRAME_THRESHOLD_MS = 1200L
        private const val BACKEND_CPU = 0
        private const val BACKEND_GPU = 1
        private const val BACKEND_TURNIP = 2
        private const val GPU_PROFILE_SAFE_FP32 = 0
        private const val GPU_PROFILE_BALANCED_FP16 = 1
        private const val GPU_PROFILE_NO_PACKING_FP32 = 2
        const val EXTRA_BACKEND = "hiddenrisk.backend"
        const val EXTRA_GPU_PROFILE = "hiddenrisk.gpu_profile"
        const val EXTRA_TARGET_INPUT_SIZE = "hiddenrisk.target_input_size"
        const val EXTRA_MAX_RESULTS_OVERRIDE = "hiddenrisk.max_results_override"
        const val EXTRA_DEBUG_COMPARE = "hiddenrisk.debug_compare"
        const val EXTRA_SAMPLE_IMAGE_PATH = "hiddenrisk.sample_image_path"
        private const val AUTO_CAPTURE_INTERVAL_MS = 1000L
        private const val DEFAULT_RESULT_LIMIT_OVERRIDE = 5
        // 当前正式运行时资产是 640 输入、8400 anchors 的单输出模型，探针页必须保持一致。
        private const val DEFAULT_TARGET_INPUT_SIZE = 640
    }
}
