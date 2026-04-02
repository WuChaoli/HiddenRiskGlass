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
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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
 * 流程：加载初始化 → 自动拍照检测 → 发现隐患提示 → 流式回答 → 同步确认。
 */
class AiInspectionActivity : BaseGlassActivity(), RokidSdkManager.Listener {

    companion object {
        private const val TAG = "AiInspection"
        private const val REQUEST_MEDIA_PERMISSION = 201
        private const val CAPTURE_TIMEOUT_MS = 15000L
        private const val CAPTURE_WARMUP_MS = 1200L
        private const val HAZARD_ALERT_HOLD_MS = 2500L
        private const val AUTO_CAPTURE_INTERVAL_MS = 1000L
        private val QUICK_CAPTURE_SIZE = Size(960, 960)

        private const val BACKEND_GPU = 1
        private const val GPU_PROFILE_BALANCED_FP16 = 1
        private const val DEFAULT_TARGET_INPUT_SIZE = 640
    }

    /**
     * 页面的四种可见状态。
     */
    private enum class PageState {
        LOADING,          // 系统初始化
        DETECTING,        // 自动取景识别中
        HAZARD_ALERT,     // 发现安全隐患
        STREAM_RESPONSE,  // 流式回答 + 同步确认
        SYNC_SUCCESS,     // 同步成功
    }

    // --- UI ---
    private lateinit var layoutLoading: LinearLayout
    private lateinit var layoutDetection: FrameLayout
    private lateinit var layoutHazardAlert: LinearLayout
    private lateinit var layoutHazardAlertBottom: LinearLayout
    private lateinit var layoutStreamResponse: FrameLayout
    private lateinit var ivLoadingSpinner: ImageView
    private lateinit var tvLoadingTitle: TextView
    private lateinit var tvLoadingSubtitle: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgressPercent: TextView
    private lateinit var tvLoadingHint: TextView
    private lateinit var tvDetectionStatus: TextView
    private lateinit var viewStatusDot: View
    private lateinit var tvStreamContent: TextView
    private lateinit var scrollContent: ScrollView
    private lateinit var tvSyncPrompt: TextView
    private lateinit var layoutSyncSuccess: LinearLayout
    private lateinit var tvSyncSuccessHint: TextView

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
    private var pageState = PageState.LOADING
    private var streamingInProgress = false
    private var lastAnalysisText = ""

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

    // ==================== 生命周期 ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_inspection)

        layoutLoading = findViewById(R.id.layoutLoading)
        layoutDetection = findViewById(R.id.layoutDetection)
        layoutHazardAlert = findViewById(R.id.layoutHazardAlert)
        layoutHazardAlertBottom = findViewById(R.id.layoutHazardAlertBottom)
        layoutStreamResponse = findViewById(R.id.layoutStreamResponse)
        ivLoadingSpinner = findViewById(R.id.ivLoadingSpinner)
        tvLoadingTitle = findViewById(R.id.tvLoadingTitle)
        tvLoadingSubtitle = findViewById(R.id.tvLoadingSubtitle)
        progressBar = findViewById(R.id.progressBar)
        tvProgressPercent = findViewById(R.id.tvProgressPercent)
        tvLoadingHint = findViewById(R.id.tvLoadingHint)
        tvDetectionStatus = findViewById(R.id.tvDetectionStatus)
        viewStatusDot = findViewById(R.id.viewStatusDot)
        tvStreamContent = findViewById(R.id.tvStreamContent)
        scrollContent = findViewById(R.id.scrollContent)
        tvSyncPrompt = findViewById(R.id.tvSyncPrompt)
        layoutSyncSuccess = findViewById(R.id.layoutSyncSuccess)
        tvSyncSuccessHint = findViewById(R.id.tvSyncSuccessHint)

        showPage(PageState.LOADING)

        RokidSdkManager.initialize(application as Application)
        RokidSdkManager.addListener(this)
        RokidSdkManager.ensureInitialized()

        animateProgressTo(10)
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        ensureMediaPermissionOrStart()
        if (pageState == PageState.DETECTING) {
            scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
        }
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
        when (keyEvent) {
            GlassKeyEvent.KEYCODE_CLICK -> {
                when (pageState) {
                    PageState.STREAM_RESPONSE -> {
                        if (!streamingInProgress) {
                            // 单击确认同步
                            syncToPhone()
                            return true
                        }
                    }
                    PageState.SYNC_SUCCESS -> {
                        // 单击继续巡检
                        returnToDetecting()
                        return true
                    }
                    else -> {}
                }
            }
            GlassKeyEvent.KEYCODE_DOUBLE_CLICK -> {
                when (pageState) {
                    PageState.STREAM_RESPONSE -> {
                        // 双击退出 → 跳过同步，回到检测
                        returnToDetecting()
                        return true
                    }
                    PageState.SYNC_SUCCESS -> {
                        // 双击退出
                        finish()
                        return true
                    }
                    else -> {}
                }
            }
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

        if (!captureInProgress && !inferenceRunning.get() && pageState == PageState.DETECTING) {
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
                uiHandler.postDelayed({
                    if (!destroyed) transitionToDetection()
                }, CAPTURE_WARMUP_MS)
            }
        }
    }

    private fun transitionToDetection() {
        ivLoadingSpinner.clearAnimation()
        showPage(PageState.DETECTING)

        pendingCaptureRequest = true
        startSampleCaptureIfNeeded()
        scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
    }

    private fun returnToDetecting() {
        showPage(PageState.DETECTING)
        pendingCaptureRequest = true
        startSampleCaptureIfNeeded()
        scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
    }

    // ==================== 拍照与推理 ====================

    private fun startSampleCaptureIfNeeded() {
        if (!pendingCaptureRequest || captureInProgress) return
        if (!isActivityResumed || !isWorkflowActive) return
        if (pageState != PageState.DETECTING) return

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
                    if (success && hasHazard) {
                        onHazardDetected()
                    } else {
                        // 未发现隐患，继续自动检测
                        scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
                    }
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

    // ==================== 隐患处理流程 ====================

    /**
     * 检测到隐患：显示警告 → 调用流式接口 → 显示回答。
     */
    private fun onHazardDetected() {
        // 停止自动拍照
        uiHandler.removeCallbacks(autoCaptureRunnable)
        autoCaptureScheduled = false

        // 显示隐患警告页面
        showPage(PageState.HAZARD_ALERT)

        // 停留一段时间后开始流式请求
        uiHandler.postDelayed({
            if (!destroyed) {
                startStreamingAnalysis()
            }
        }, HAZARD_ALERT_HOLD_MS)
    }

    private fun startStreamingAnalysis() {
        showPage(PageState.STREAM_RESPONSE)
        tvStreamContent.text = ""
        streamingInProgress = true
        tvSyncPrompt.visibility = View.INVISIBLE

        // 调用流式接口（当前使用模拟数据）
        HazardStreamService.analyze(null, object : HazardStreamService.StreamCallback {
            override fun onChunk(text: String) {
                if (destroyed) return
                tvStreamContent.text = text
                scrollContent.post {
                    scrollContent.fullScroll(View.FOCUS_DOWN)
                }
            }

            override fun onComplete(fullText: String) {
                if (destroyed) return
                streamingInProgress = false
                lastAnalysisText = fullText
                tvSyncPrompt.visibility = View.VISIBLE
            }

            override fun onError(message: String) {
                if (destroyed) return
                streamingInProgress = false
                tvStreamContent.text = "分析失败：$message"
                tvSyncPrompt.visibility = View.VISIBLE
            }
        })
    }

    /**
     * 调用后端接口同步隐患记录，成功后显示同步成功页面。
     */
    private fun syncToPhone() {
        HazardStreamService.syncToPhone(lastAnalysisText, object : HazardStreamService.SyncCallback {
            override fun onSuccess() {
                if (destroyed) return
                showSyncSuccess()
            }

            override fun onError(message: String) {
                if (destroyed) return
                Log.e(TAG, "sync failed: $message")
                // 同步失败也显示成功页面（后续可改为错误提示）
                showSyncSuccess()
            }
        })
    }

    private fun showSyncSuccess() {
        showPage(PageState.SYNC_SUCCESS)

        // 3 秒后渐隐，然后回到检测状态
        uiHandler.postDelayed({
            if (destroyed || pageState != PageState.SYNC_SUCCESS) return@postDelayed
            val fadeOut = AlphaAnimation(1f, 0f).apply {
                duration = 800L
                fillAfter = true
                setAnimationListener(object : Animation.AnimationListener {
                    override fun onAnimationStart(animation: Animation?) {}
                    override fun onAnimationRepeat(animation: Animation?) {}
                    override fun onAnimationEnd(animation: Animation?) {
                        if (!destroyed) {
                            layoutSyncSuccess.clearAnimation()
                            tvSyncSuccessHint.clearAnimation()
                            returnToDetecting()
                        }
                    }
                })
            }
            layoutSyncSuccess.startAnimation(fadeOut)
            tvSyncSuccessHint.startAnimation(fadeOut)
        }, 3000L)
    }

    // ==================== 自动拍摄调度 ====================

    private fun shouldAutoCaptureNow(): Boolean {
        if (destroyed || !isActivityResumed || !isWorkflowActive) return false
        if (!hasRequiredPermissions()) return false
        if (RokidSdkManager.state != RokidSdkManager.SdkState.READY) return false
        if (!modelLoaded || modelLoading) return false
        if (captureInProgress || captureDelayScheduled || quickCameraInitializing || pendingCaptureRequest) return false
        if (inferenceRunning.get()) return false
        if (pageState != PageState.DETECTING) return false
        return true
    }

    private fun scheduleAutoCaptureIfNeeded(delayMs: Long) {
        if (autoCaptureScheduled || destroyed || !isActivityResumed || !isWorkflowActive) return
        if (pageState != PageState.DETECTING) return
        autoCaptureScheduled = true
        uiHandler.postDelayed(autoCaptureRunnable, delayMs)
    }

    // ==================== UI 页面切换 ====================

    private fun showPage(state: PageState) {
        pageState = state
        layoutLoading.visibility = if (state == PageState.LOADING) View.VISIBLE else View.GONE
        tvLoadingHint.visibility = if (state == PageState.LOADING) View.VISIBLE else View.GONE
        layoutDetection.visibility = if (state == PageState.DETECTING) View.VISIBLE else View.GONE
        layoutHazardAlert.visibility = if (state == PageState.HAZARD_ALERT) View.VISIBLE else View.GONE
        layoutHazardAlertBottom.visibility = if (state == PageState.HAZARD_ALERT) View.VISIBLE else View.GONE
        layoutStreamResponse.visibility = if (state == PageState.STREAM_RESPONSE) View.VISIBLE else View.GONE
        layoutSyncSuccess.visibility = if (state == PageState.SYNC_SUCCESS) View.VISIBLE else View.GONE
        tvSyncSuccessHint.visibility = if (state == PageState.SYNC_SUCCESS) View.VISIBLE else View.GONE

        if (state == PageState.LOADING) {
            ivLoadingSpinner.startAnimation(loadingRotateAnimation)
        }
        if (state == PageState.SYNC_SUCCESS) {
            // 重置透明度（上次渐隐后可能为0）
            layoutSyncSuccess.alpha = 1f
            tvSyncSuccessHint.alpha = 1f
        }
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
        QuickCameraManager.releaseCamera()

        if (pageState == PageState.LOADING) {
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
