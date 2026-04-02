package com.rokid.glass.inspection

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rokid.glass.camera.QuickCameraManager
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.hiddenrisk.GlassKeyEvent
import com.rokid.glass.hiddenrisk.HiddenRiskNcnn
import com.rokid.glass.hiddenrisk.NativeInferenceStats
import com.rokid.glass.hiddenrisk.RokidSdkManager
import com.rokid.glesse.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AI 巡检页面。
 * 进入后先显示加载动画（初始化 SDK、加载模型、预热相机），
 * 完成后自动进入检测模式，循环拍照+推理。
 */
class AiInspectionActivity : BaseGlassActivity(), RokidSdkManager.Listener {

    companion object {
        private const val TAG = "AiInspection"
        private const val REQUEST_MEDIA_PERMISSION = 201
        private const val CAPTURE_TIMEOUT_MS = 15000L
        private const val CAPTURE_WARMUP_MS = 1200L
        private const val RESULT_HOLD_MS = 2000L
        private const val AUTO_CAPTURE_INTERVAL_MS = 1000L
        private val QUICK_CAPTURE_SIZE = Size(960, 960)

        private const val BACKEND_GPU = 1
        private const val GPU_PROFILE_BALANCED_FP16 = 1
        private const val DEFAULT_TARGET_INPUT_SIZE = 640
    }

    // --- UI ---
    private lateinit var layoutLoading: LinearLayout
    private lateinit var layoutDetection: FrameLayout
    private lateinit var ivLoadingSpinner: ImageView
    private lateinit var tvLoadingTitle: TextView
    private lateinit var tvLoadingSubtitle: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressPercent: TextView
    private lateinit var tvLoadingHint: TextView
    private lateinit var tvGuidePrompt: TextView
    private lateinit var tvDetectionStatus: TextView
    private lateinit var viewStatusDot: View
    private lateinit var imageResultIcon: ImageView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val nativeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val inferenceRunning = AtomicBoolean(false)

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
    private var quickCameraInitializing = false
    private var quickCameraReady = false
    private var quickCameraReadyAtElapsedMs = 0L
    private var sdkReadyAtElapsedMs = 0L
    private var autoCaptureScheduled = false
    private var showingGuidePrompt = true

    // 加载进度模拟
    private var currentProgress = 0
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (currentProgress < targetProgress) {
                currentProgress++
                progressBar.progress = currentProgress
                tvProgressPercent.text = "${currentProgress}%"
                uiHandler.postDelayed(this, 30L)
            }
        }
    }
    private var targetProgress = 0

    private val loadingRotateAnimation: RotateAnimation by lazy {
        RotateAnimation(
            0f, 360f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f,
        ).apply {
            duration = 900L
            repeatCount = Animation.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    private val captureTimeoutRunnable = Runnable {
        if (!captureInProgress || destroyed) return@Runnable
        captureInProgress = false
        failWorkflow("样图拍摄超时")
    }

    private val captureDelayRunnable = Runnable {
        captureDelayScheduled = false
        if (destroyed || !pendingCaptureRequest || captureInProgress) return@Runnable
        startSampleCaptureIfNeeded()
    }

    private val autoCaptureRunnable = Runnable {
        autoCaptureScheduled = false
        if (!shouldAutoCaptureNow()) {
            scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
            return@Runnable
        }
        pendingCaptureRequest = true
        startSampleCaptureIfNeeded()
    }

    private val resultDismissRunnable = Runnable {
        if (destroyed) return@Runnable
        imageResultIcon.visibility = View.GONE
    }

    // ==================== 生命周期 ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_inspection)

        layoutLoading = findViewById(R.id.layoutLoading)
        layoutDetection = findViewById(R.id.layoutDetection)
        ivLoadingSpinner = findViewById(R.id.ivLoadingSpinner)
        tvLoadingTitle = findViewById(R.id.tvLoadingTitle)
        tvLoadingSubtitle = findViewById(R.id.tvLoadingSubtitle)
        progressBar = findViewById(R.id.progressBar)
        tvProgressPercent = findViewById(R.id.tvProgressPercent)
        tvLoadingHint = findViewById(R.id.tvLoadingHint)
        tvGuidePrompt = findViewById(R.id.tvGuidePrompt)
        tvDetectionStatus = findViewById(R.id.tvDetectionStatus)
        viewStatusDot = findViewById(R.id.viewStatusDot)
        imageResultIcon = findViewById(R.id.imageResultIcon)

        showLoadingState()

        RokidSdkManager.initialize(application as Application)
        RokidSdkManager.addListener(this)
        RokidSdkManager.ensureInitialized()

        animateProgressTo(10)
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        ensureMediaPermissionOrStart()
        scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
    }

    override fun onStart() {
        super.onStart()
        isWorkflowActive = true
    }

    override fun onPause() {
        isActivityResumed = false
        uiHandler.removeCallbacks(captureDelayRunnable)
        uiHandler.removeCallbacks(autoCaptureRunnable)
        captureDelayScheduled = false
        autoCaptureScheduled = false
        super.onPause()
    }

    override fun onStop() {
        isWorkflowActive = false
        uiHandler.removeCallbacks(captureDelayRunnable)
        uiHandler.removeCallbacks(autoCaptureRunnable)
        captureDelayScheduled = false
        autoCaptureScheduled = false
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        uiHandler.removeCallbacks(captureDelayRunnable)
        uiHandler.removeCallbacks(captureTimeoutRunnable)
        uiHandler.removeCallbacks(autoCaptureRunnable)
        uiHandler.removeCallbacks(resultDismissRunnable)
        uiHandler.removeCallbacks(progressRunnable)
        quickCameraInitializing = false
        quickCameraReady = false
        quickCameraReadyAtElapsedMs = 0L
        QuickCameraManager.releaseCamera()
        RokidSdkManager.removeListener(this)
        hiddenRiskNcnn?.clearFrameState()
        nativeExecutor.shutdown()
        runCatching { nativeExecutor.awaitTermination(2, TimeUnit.SECONDS) }
        if (isFinishing && !isChangingConfigurations) {
            RokidSdkManager.release()
        }
        super.onDestroy()
    }

    // ==================== 输入事件 ====================

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        if (keyEvent == GlassKeyEvent.KEYCODE_CLICK) {
            if (showingGuidePrompt) {
                showingGuidePrompt = false
                tvGuidePrompt.visibility = View.GONE
            }
            return true
        }
        return super.onGlassKeyEvent(keyEvent)
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
            if (state == RokidSdkManager.SdkState.READY) {
                animateProgressTo(30)
                tvLoadingSubtitle.text = "SDK 就绪，正在加载模型…"
            }
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
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
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
                ActivityCompat.requestPermissions(this, requiredPermissions(), REQUEST_MEDIA_PERMISSION)
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

        if (!captureInProgress && !inferenceRunning.get()) {
            scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
        }
    }

    private fun startModelLoadIfNeeded(local: HiddenRiskNcnn) {
        if (modelLoading) return

        modelLoading = true
        animateProgressTo(50)
        tvLoadingSubtitle.text = "正在加载检测模型…"

        if (!submitNativeTask {
                local.setDebugResultLimit(5)
                local.setDebugCompareEnabled(false)
                val success = runCatching {
                    local.loadModel(assets, BACKEND_GPU, GPU_PROFILE_BALANCED_FP16, DEFAULT_TARGET_INPUT_SIZE)
                }.onFailure { e -> Log.e(TAG, "loadModel failed", e) }
                    .getOrDefault(false)

                uiHandler.post {
                    modelLoading = false
                    if (destroyed) return@post
                    if (success) {
                        modelLoaded = true
                        animateProgressTo(80)
                        tvLoadingSubtitle.text = "模型加载完成，准备相机…"
                        initCameraAndTransition()
                    } else {
                        failWorkflow("模型加载失败")
                    }
                }
            }) {
            modelLoading = false
            failWorkflow("模型任务提交失败")
        }
    }

    private fun initCameraAndTransition() {
        if (quickCameraReady) {
            transitionToDetection()
            return
        }
        if (quickCameraInitializing) return

        quickCameraInitializing = true
        QuickCameraManager.initialize(
            size = QUICK_CAPTURE_SIZE,
            quickCapture = true,
        ) { success ->
            uiHandler.post {
                quickCameraInitializing = false
                quickCameraReady = success
                quickCameraReadyAtElapsedMs = if (success) SystemClock.elapsedRealtime() else 0L
                if (destroyed) {
                    QuickCameraManager.releaseCamera()
                    return@post
                }
                if (!success) {
                    failWorkflow("相机初始化失败")
                    return@post
                }
                animateProgressTo(100)
                tvLoadingSubtitle.text = "准备就绪"
                // 短暂延迟后切换到检测界面
                uiHandler.postDelayed({
                    if (!destroyed) transitionToDetection()
                }, CAPTURE_WARMUP_MS)
            }
        }
    }

    private fun transitionToDetection() {
        ivLoadingSpinner.clearAnimation()
        layoutLoading.visibility = View.GONE
        tvLoadingHint.visibility = View.GONE
        layoutDetection.visibility = View.VISIBLE

        pendingCaptureRequest = true
        startSampleCaptureIfNeeded()
        scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
    }

    // ==================== 拍照与推理 ====================

    private fun startSampleCaptureIfNeeded() {
        if (!pendingCaptureRequest || captureInProgress) return
        if (!isActivityResumed || !isWorkflowActive) return

        if (!quickCameraReady || !QuickCameraManager.isGpuCaptureWarm()) {
            if (!quickCameraReady) {
                initCameraAndTransition()
            }
            return
        }

        val readyElapsedMs = quickCameraReadyAtElapsedMs.takeIf { it > 0L } ?: sdkReadyAtElapsedMs
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

        pendingCaptureRequest = false
        captureInProgress = true
        uiHandler.removeCallbacks(captureTimeoutRunnable)
        uiHandler.postDelayed(captureTimeoutRunnable, CAPTURE_TIMEOUT_MS)

        QuickCameraManager.takeGpuFrame { frame ->
            uiHandler.post {
                if (destroyed || !captureInProgress) return@post
                captureInProgress = false
                uiHandler.removeCallbacks(captureTimeoutRunnable)
                if (frame == null) {
                    Log.w(TAG, "takeGpuFrame failed")
                    scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
                    return@post
                }
                triggerInference(frame)
            }
        }
    }

    private fun triggerInference(frame: QuickCameraManager.GpuFrame) {
        val local = hiddenRiskNcnn ?: run {
            frame.hardwareBuffer.close()
            return
        }
        if (!inferenceRunning.compareAndSet(false, true)) {
            frame.hardwareBuffer.close()
            return
        }

        if (!submitNativeTask {
                val success = runCatching {
                    local.submitHardwareBuffer(
                        frame.hardwareBuffer,
                        frame.width,
                        frame.height,
                        frame.rotationDegrees,
                    )
                }.onFailure { e -> Log.e(TAG, "submitHardwareBuffer failed", e) }
                    .getOrDefault(false)
                frame.hardwareBuffer.close()

                val snapshot = runCatching { local.getLatestInferenceStats() }.getOrNull()
                val hasHazard = evaluateHazard(snapshot)

                uiHandler.post {
                    inferenceRunning.set(false)
                    if (destroyed) return@post
                    if (success) {
                        applyResult(hasHazard)
                    }
                    scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
                }
            }) {
            frame.hardwareBuffer.close()
            inferenceRunning.set(false)
            scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
        }
    }

    private fun evaluateHazard(snapshot: NativeInferenceStats?): Boolean {
        val count = snapshot?.detectionCount ?: 0
        return count > 0
    }

    private fun applyResult(hasHazard: Boolean) {
        if (hasHazard) {
            imageResultIcon.setImageResource(R.drawable.hidden_risk_alert)
            imageResultIcon.visibility = View.VISIBLE
        } else {
            imageResultIcon.setImageResource(R.drawable.hidden_risk_safe)
            imageResultIcon.visibility = View.VISIBLE
        }
        uiHandler.removeCallbacks(resultDismissRunnable)
        uiHandler.postDelayed(resultDismissRunnable, RESULT_HOLD_MS)
    }

    // ==================== 自动拍摄调度 ====================

    private fun shouldAutoCaptureNow(): Boolean {
        if (destroyed || !isActivityResumed || !isWorkflowActive) return false
        if (!hasRequiredPermissions()) return false
        if (RokidSdkManager.state != RokidSdkManager.SdkState.READY) return false
        if (!modelLoaded || modelLoading) return false
        if (captureInProgress || captureDelayScheduled || quickCameraInitializing || pendingCaptureRequest) return false
        if (inferenceRunning.get()) return false
        if (layoutLoading.visibility == View.VISIBLE) return false
        return true
    }

    private fun scheduleAutoCaptureIfNeeded(delayMs: Long) {
        if (autoCaptureScheduled || destroyed || !isActivityResumed || !isWorkflowActive) return
        if (layoutLoading.visibility == View.VISIBLE) return
        autoCaptureScheduled = true
        uiHandler.postDelayed(autoCaptureRunnable, delayMs)
    }

    // ==================== UI 状态 ====================

    private fun showLoadingState() {
        layoutLoading.visibility = View.VISIBLE
        layoutDetection.visibility = View.GONE
        tvLoadingHint.visibility = View.VISIBLE
        ivLoadingSpinner.startAnimation(loadingRotateAnimation)
        progressBar.progress = 0
        tvProgressPercent.text = "0%"
    }

    private fun animateProgressTo(target: Int) {
        targetProgress = target
        uiHandler.removeCallbacks(progressRunnable)
        uiHandler.post(progressRunnable)
    }

    private fun failWorkflow(message: String) {
        Log.e(TAG, "workflow failed: $message")
        captureInProgress = false
        pendingCaptureRequest = false
        captureDelayScheduled = false
        autoCaptureScheduled = false
        quickCameraInitializing = false
        quickCameraReady = false
        quickCameraReadyAtElapsedMs = 0L
        modelLoading = false
        uiHandler.removeCallbacks(captureDelayRunnable)
        uiHandler.removeCallbacks(captureTimeoutRunnable)
        uiHandler.removeCallbacks(autoCaptureRunnable)
        uiHandler.removeCallbacks(resultDismissRunnable)
        QuickCameraManager.releaseCamera()

        if (layoutLoading.visibility == View.VISIBLE) {
            ivLoadingSpinner.clearAnimation()
            tvLoadingTitle.text = "初始化失败"
            tvLoadingSubtitle.text = message
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

    private fun submitNativeTask(task: () -> Unit): Boolean {
        return try {
            nativeExecutor.execute(task)
            true
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "native task rejected", e)
            false
        }
    }
}
