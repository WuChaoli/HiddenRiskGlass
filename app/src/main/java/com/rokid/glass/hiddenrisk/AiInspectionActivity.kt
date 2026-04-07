package com.rokid.glass.hiddenrisk

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import com.rokid.glesse.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AI 巡检页面。
 * 流程：加载初始化 -> 自动拍照检测 -> 发现隐患提示 -> 流式回答 -> 同步确认。
 */
class AiInspectionActivity : BaseGlassActivity(), RokidSdkManager.Listener {

    companion object {
        private const val TAG = "AiInspection"
        private const val REQUEST_MEDIA_PERMISSION = 201
        private const val CAPTURE_TIMEOUT_MS = 1000L  // 从15000改为1000，快速超时并自动重试
        private const val MAX_CONSECUTIVE_TIMEOUTS = 3
        private const val MAX_CAMERA_RESTART_ATTEMPTS = 3
        private const val CAPTURE_WARMUP_MS = 1200L
        private const val HAZARD_ALERT_HOLD_MS = 2500L
        private const val AUTO_CAPTURE_INTERVAL_MS = 1000L
        private val QUICK_CAPTURE_SIZE = Size(960, 960)

        private const val BACKEND_GPU = 1
        private const val GPU_PROFILE_BALANCED_FP16 = 1
        private const val DEFAULT_TARGET_INPUT_SIZE = 640
    }

    /**
     * 页面的可见状态。
     */
    private enum class PageState {
        LOADING,          // 系统初始化
        LOAD_ERROR,       // 加载失败
        LENS_BLOCKED,     // 镜头被遮挡
        CONFIRM_GUIDE,    // 确认开始巡检
        DEVICE_ERROR,     // 设备异常
        INSPECTION_GUIDE, // 巡检操作说明
        DETECTING,        // 自动取景识别中
        SAFE_AREA,        // 安全区域
        HAZARD_ALERT,     // 发现安全隐患
        STREAM_RESPONSE,  // 流式回答 + 同步确认
        SYNC_SUCCESS,     // 同步成功
        END_REPORT,       // 巡检结束报告
    }

    /**
     * 隐患判断结果。
     */
    private enum class HazardJudgeResult { NO_HAZARD, HAS_HAZARD }

    /**
     * 检测状态，用于图标提示逻辑。
     */
    private enum class DetectionStatus {
        NONE,       // 初始/检测中
        HAS_HAZARD, // 有隐患
        NO_HAZARD   // 无隐患
    }

    private fun evaluateHazardWithJudgment(snapshot: NativeInferenceStats?): HazardJudgeResult {
        // 调用方已保证 detectionCount > 0
        if (snapshot == null || snapshot.detections == null) {
            return HazardJudgeResult.HAS_HAZARD
        }
        
        // 定义三组必须配对出现的label
        val requiredPairs = listOf(
            Pair("T_btn", "load_switch"),          // T字按钮 + 负荷开关
            Pair("lpg_cylinder", "gas_alarm"),     // 液化石油气瓶 + 可燃气体报警器
            Pair("gas_range", "flameout_protection") // 燃气灶 + 熄火保护装置
        )
        
        // 收集当前检测到的所有label
        val detectedLabels = snapshot.detections.map { it.label }.toSet()
        
        // 如果没有检测到任何相关label，认为有隐患
        val allRelevantLabels = requiredPairs.flatMap { listOf(it.first, it.second) }.toSet()
        if (detectedLabels.none { it in allRelevantLabels }) {
            Log.d(TAG, "未检测到任何配对相关的label，判定为有隐患")
            return HazardJudgeResult.HAS_HAZARD
        }
        
        // 检查每一组配对
        for ((labelA, labelB) in requiredPairs) {
            val hasA = labelA in detectedLabels
            val hasB = labelB in detectedLabels
            
            when {
                // 两个都没出现：这组不完整，有隐患
                !hasA && !hasB -> {
                    Log.d(TAG, "配对不完整: $labelA 和 $labelB 都未出现，判定为有隐患")
                    return HazardJudgeResult.HAS_HAZARD
                }
                // 只出现一个：这组不完整，有隐患
                hasA && !hasB -> {
                    Log.d(TAG, "配对不完整: 检测到 $labelA 但未检测到 $labelB，判定为有隐患")
                    return HazardJudgeResult.HAS_HAZARD
                }
                !hasA && hasB -> {
                    Log.d(TAG, "配对不完整: 检测到 $labelB 但未检测到 $labelA，判定为有隐患")
                    return HazardJudgeResult.HAS_HAZARD
                }
                // 两个都出现了：这组配对完整，继续检查下一组
                else -> {
                    Log.d(TAG, "配对完整: $labelA + $labelB")
                }
            }
        }
        
        // 所有组都配对完整
        Log.d(TAG, "所有配对组都完整，判定为安全")
        return HazardJudgeResult.NO_HAZARD
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
    private lateinit var layoutLoadError: FrameLayout
    private lateinit var tvLoadErrorMessage: TextView
    private lateinit var tvLoadErrorHint: TextView
    private lateinit var layoutLensBlocked: FrameLayout
    private lateinit var layoutConfirmGuide: FrameLayout
    private lateinit var tvConfirmGuideHint: TextView
    private lateinit var layoutDeviceError: FrameLayout
    private lateinit var tvDeviceErrorMessage: TextView
    private lateinit var tvDeviceErrorHint: TextView
    private lateinit var layoutInspectionGuide: FrameLayout
    private lateinit var tvInspectionGuideHint: TextView
    private lateinit var layoutSafeArea: FrameLayout
    private lateinit var tvSafeAreaHint: TextView
    private lateinit var layoutEndReport: FrameLayout
    private lateinit var tvEndReportContent: TextView
    private lateinit var tvEndReportHint: TextView

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
    private var streamCallbackActive = false
    private var lastAnalysisText = ""
    private var latestHazardBitmap: Bitmap? = null

    // 检测状态图标提示
    private var currentDetectionStatus: DetectionStatus = DetectionStatus.NONE
    private var statusIndicatorVisible = false
    private val STATUS_INDICATOR_DURATION_MS = 3000L

    // 拍摄超时恢复机制
    private var consecutiveTimeoutCount = 0
    private var cameraRestartAttempts = 0
    private var isCameraRestarting = false

    private val statusIndicatorHideRunnable = Runnable {
        hideStatusIndicator()
    }

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

        Log.w(TAG, "拍摄超时，连续超时次数: ${consecutiveTimeoutCount + 1}")
        captureInProgress = false
        consecutiveTimeoutCount++

        when {
            consecutiveTimeoutCount >= MAX_CONSECUTIVE_TIMEOUTS -> {
                // 连续3次超时，尝试静默重启摄像头
                Log.w(TAG, "连续3次拍摄超时，准备静默重启摄像头")
                silentRestartCamera()
            }
            else -> {
                // 记录异常但继续自动拍摄
                Log.d(TAG, "拍摄超时，自动获取下一帧")
                scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
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
        layoutLoadError = findViewById(R.id.layoutLoadError)
        tvLoadErrorMessage = findViewById(R.id.tvLoadErrorMessage)
        tvLoadErrorHint = findViewById(R.id.tvLoadErrorHint)
        layoutLensBlocked = findViewById(R.id.layoutLensBlocked)
        layoutConfirmGuide = findViewById(R.id.layoutConfirmGuide)
        tvConfirmGuideHint = findViewById(R.id.tvConfirmGuideHint)
        layoutDeviceError = findViewById(R.id.layoutDeviceError)
        tvDeviceErrorMessage = findViewById(R.id.tvDeviceErrorMessage)
        tvDeviceErrorHint = findViewById(R.id.tvDeviceErrorHint)
        layoutInspectionGuide = findViewById(R.id.layoutInspectionGuide)
        tvInspectionGuideHint = findViewById(R.id.tvInspectionGuideHint)
        layoutSafeArea = findViewById(R.id.layoutSafeArea)
        tvSafeAreaHint = findViewById(R.id.tvSafeAreaHint)
        layoutEndReport = findViewById(R.id.layoutEndReport)
        tvEndReportContent = findViewById(R.id.tvEndReportContent)
        tvEndReportHint = findViewById(R.id.tvEndReportHint)

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
        streamCallbackActive = false
        uiHandler.removeCallbacks(captureDelayRunnable)
        uiHandler.removeCallbacks(captureTimeoutRunnable)
        uiHandler.removeCallbacks(autoCaptureRunnable)
        uiHandler.removeCallbacks(progressRunnable)
        uiHandler.removeCallbacks(statusIndicatorHideRunnable)
        currentDetectionStatus = DetectionStatus.NONE
        statusIndicatorVisible = false
        quickCameraInitializing = false
        quickCameraReady = false
        quickCameraReadyAtElapsedMs = 0L
        // 重置超时恢复计数器
        consecutiveTimeoutCount = 0
        cameraRestartAttempts = 0
        isCameraRestarting = false
        QuickCameraManager.releaseCamera()
        RokidSdkManager.removeListener(this)
        hiddenRiskNcnn?.clearFrameState()
        nativeExecutor.shutdown()
        runCatching { nativeExecutor.awaitTermination(2, TimeUnit.SECONDS) }
        latestHazardBitmap?.recycle()
        latestHazardBitmap = null
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
                    PageState.INSPECTION_GUIDE -> {
                        showPage(PageState.CONFIRM_GUIDE)
                        return true
                    }
                    PageState.CONFIRM_GUIDE -> {
                        transitionToDetection()
                        return true
                    }
                    PageState.LOAD_ERROR, PageState.DEVICE_ERROR -> {
                        resetForRetry()
                        showPage(PageState.LOADING)
                        animateProgressTo(10)
                        RokidSdkManager.ensureInitialized()
                        ensureMediaPermissionOrStart()
                        return true
                    }
                    PageState.HAZARD_ALERT -> {
                        val bitmap = latestHazardBitmap
                        startStreamingAnalysis(bitmap)
                        return true
                    }
                    PageState.DETECTING -> {
                        // 隐患图标显示期间单击：进入流式响应
                        if (currentDetectionStatus == DetectionStatus.HAS_HAZARD && statusIndicatorVisible) {
                            // 清除倒计时和状态
                            uiHandler.removeCallbacks(statusIndicatorHideRunnable)
                            hideStatusIndicator()
                            // 进入流式响应
                            startStreamingAnalysis(latestHazardBitmap)
                            return true
                        }
                    }
                    PageState.STREAM_RESPONSE -> {
                        if (!streamingInProgress) {
                            syncToPhone()
                            return true
                        }
                    }
                    PageState.SYNC_SUCCESS -> {
                        returnToDetecting()
                        return true
                    }
                    PageState.END_REPORT -> {
                        finish()
                        return true
                    }
                    else -> {}
                }
            }
            GlassKeyEvent.KEYCODE_DOUBLE_CLICK -> {
                Log.d(TAG, "双击事件，当前页面状态: $pageState")
                when (pageState) {
                    PageState.INSPECTION_GUIDE,
                    PageState.CONFIRM_GUIDE,
                    PageState.LOAD_ERROR,
                    PageState.DEVICE_ERROR,
                    PageState.LENS_BLOCKED,
                    PageState.SAFE_AREA,
                    PageState.END_REPORT -> {
                        Log.d(TAG, "双击: 退出页面")
                        finish()
                        return true
                    }
                    PageState.HAZARD_ALERT,
                    PageState.STREAM_RESPONSE,
                    PageState.SYNC_SUCCESS -> {
                        Log.d(TAG, "双击: 返回检测页面")
                        returnToDetecting()
                        return true
                    }
                    else -> {
                        Log.d(TAG, "双击: 未匹配状态，调用父类")
                    }
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
                    if (!destroyed) showPage(PageState.INSPECTION_GUIDE)
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

    /**
     * 静默重启摄像头，不显示错误页面。
     * 用于连续超时后的自动恢复。
     */
    private fun silentRestartCamera() {
        if (isCameraRestarting || destroyed) return

        cameraRestartAttempts++

        if (cameraRestartAttempts > MAX_CAMERA_RESTART_ATTEMPTS) {
            // 3次重启都失败，显示错误
            Log.e(TAG, "摄像头重启${MAX_CAMERA_RESTART_ATTEMPTS}次失败，显示错误")
            failWorkflow("相机连续超时，请检查设备")
            return
        }

        Log.i(TAG, "静默重启摄像头 (尝试 ${cameraRestartAttempts}/${MAX_CAMERA_RESTART_ATTEMPTS})")
        isCameraRestarting = true

        // 重置相机状态
        quickCameraReady = false
        quickCameraReadyAtElapsedMs = 0L
        QuickCameraManager.releaseCamera()

        // 延迟后重新初始化
        uiHandler.postDelayed({
            if (destroyed) {
                isCameraRestarting = false
                return@postDelayed
            }

            QuickCameraManager.initialize(
                size = QUICK_CAPTURE_SIZE,
                quickCapture = true,
            ) { success ->
                uiHandler.post {
                    isCameraRestarting = false

                    if (success) {
                        Log.i(TAG, "摄像头静默重启成功")
                        // 重置计数器
                        consecutiveTimeoutCount = 0
                        cameraRestartAttempts = 0
                        quickCameraReady = true
                        quickCameraReadyAtElapsedMs = SystemClock.elapsedRealtime()
                        // 恢复检测
                        pendingCaptureRequest = true
                        startSampleCaptureIfNeeded()
                        scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
                    } else {
                        Log.e(TAG, "摄像头静默重启失败")
                        // 递归调用，再次尝试重启
                        silentRestartCamera()
                    }
                }
            }
        }, 500L)  // 500ms延迟确保资源释放
    }

    private fun returnToDetecting() {
        streamCallbackActive = false
        streamingInProgress = false
        // 清除状态指示器
        uiHandler.removeCallbacks(statusIndicatorHideRunnable)
        hideStatusIndicator()
        currentDetectionStatus = DetectionStatus.NONE
        // 重置超时恢复计数器
        consecutiveTimeoutCount = 0
        cameraRestartAttempts = 0
        isCameraRestarting = false
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

                // 拍摄成功，重置超时计数器
                if (consecutiveTimeoutCount > 0) {
                    Log.d(TAG, "拍摄成功，重置超时计数器")
                    consecutiveTimeoutCount = 0
                }

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

        // 保存检测到隐患时的图片，并回收旧 Bitmap
        latestHazardBitmap?.recycle()
        latestHazardBitmap = frame.previewBitmap

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

                uiHandler.post {
                    inferenceRunning.set(false)
                    Log.d(TAG, "inference success=$success detectionCount=${snapshot?.detectionCount ?: -1}")
                    if (destroyed) return@post
                    if (!success) {
                        scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
                    } else {
                        val count = snapshot?.detectionCount ?: 0
                        if (count == 0) {
                            // 未检测到目标：跳过，不改变任何状态，让现有倒计时继续
                            scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
                        } else {
                            // detectionCount > 0，进入隐患判断
                            val newStatus = when (evaluateHazardWithJudgment(snapshot)) {
                                HazardJudgeResult.NO_HAZARD -> DetectionStatus.NO_HAZARD
                                HazardJudgeResult.HAS_HAZARD -> DetectionStatus.HAS_HAZARD
                            }
                            handleDetectionResult(newStatus)
                        }
                    }
                }
            }) {
            frame.hardwareBuffer.close()
            inferenceRunning.set(false)
            scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
        }
    }

    // ==================== 隐患处理流程 ====================

    /**
     * 处理检测结果，控制图标显示和倒计时。
     */
    private fun handleDetectionResult(newStatus: DetectionStatus) {
        val previousStatus = currentDetectionStatus
        currentDetectionStatus = newStatus

        when {
            // 状态相同且正在显示：重置倒计时
            previousStatus == newStatus && statusIndicatorVisible -> {
                uiHandler.removeCallbacks(statusIndicatorHideRunnable)
                uiHandler.postDelayed(statusIndicatorHideRunnable, STATUS_INDICATOR_DURATION_MS)
            }
            // 状态不同或未显示：立即切换并开始新倒计时
            else -> {
                showStatusIndicator(newStatus)
                uiHandler.removeCallbacks(statusIndicatorHideRunnable)
                uiHandler.postDelayed(statusIndicatorHideRunnable, STATUS_INDICATOR_DURATION_MS)
            }
        }

        // 持续自动拍摄
        scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
    }

    /**
     * 显示状态指示器图标。
     */
    private fun showStatusIndicator(status: DetectionStatus) {
        statusIndicatorVisible = true
        when (status) {
            DetectionStatus.HAS_HAZARD -> {
                layoutHazardAlert.visibility = View.VISIBLE
                layoutHazardAlertBottom.visibility = View.VISIBLE
                layoutSafeArea.visibility = View.GONE
            }
            DetectionStatus.NO_HAZARD -> {
                layoutHazardAlert.visibility = View.GONE
                layoutHazardAlertBottom.visibility = View.GONE
                layoutSafeArea.visibility = View.VISIBLE
            }
            else -> hideStatusIndicator()
        }
    }

    /**
     * 隐藏状态指示器图标。
     */
    private fun hideStatusIndicator() {
        statusIndicatorVisible = false
        layoutHazardAlert.visibility = View.GONE
        layoutHazardAlertBottom.visibility = View.GONE
        layoutSafeArea.visibility = View.GONE
    }

    private fun startStreamingAnalysis(bitmap: Bitmap?) {
        showPage(PageState.STREAM_RESPONSE)
        tvStreamContent.text = ""
        streamingInProgress = true
        streamCallbackActive = true
        tvSyncPrompt.visibility = View.INVISIBLE

        // 调用流式接口（当前使用模拟数据）
        HazardStreamService.analyze(bitmap, object : HazardStreamService.StreamCallback {
            override fun onChunk(text: String) {
                if (destroyed || !streamCallbackActive) return
                tvStreamContent.text = text
                scrollContent.post {
                    scrollContent.fullScroll(View.FOCUS_DOWN)
                }
            }

            override fun onComplete(fullText: String) {
                if (destroyed || !streamCallbackActive) return
                streamingInProgress = false
                lastAnalysisText = fullText
                tvSyncPrompt.visibility = View.VISIBLE
            }

            override fun onError(message: String) {
                if (destroyed || !streamCallbackActive) return
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

    private fun showEndReport(report: String) {
        showPage(PageState.END_REPORT)
        tvEndReportContent.text = report
    }

    private fun showSyncSuccess() {
        showPage(PageState.SYNC_SUCCESS)

        // 8 秒后无操作自动返回检测（兜底）
        uiHandler.postDelayed({
            if (!destroyed && pageState == PageState.SYNC_SUCCESS) {
                returnToDetecting()
            }
        }, 8000L)
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

        layoutLoadError.visibility = if (state == PageState.LOAD_ERROR) View.VISIBLE else View.GONE
        layoutLensBlocked.visibility = if (state == PageState.LENS_BLOCKED) View.VISIBLE else View.GONE
        layoutConfirmGuide.visibility = if (state == PageState.CONFIRM_GUIDE) View.VISIBLE else View.GONE
        layoutDeviceError.visibility = if (state == PageState.DEVICE_ERROR) View.VISIBLE else View.GONE
        layoutInspectionGuide.visibility = if (state == PageState.INSPECTION_GUIDE) View.VISIBLE else View.GONE
        layoutSafeArea.visibility = if (state == PageState.SAFE_AREA) View.VISIBLE else View.GONE
        layoutEndReport.visibility = if (state == PageState.END_REPORT) View.VISIBLE else View.GONE

        if (state == PageState.LOADING) {
            ivLoadingSpinner.startAnimation(loadingRotateAnimation)
        } else {
            ivLoadingSpinner.clearAnimation()
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

    private fun resetForRetry() {
        captureInProgress = false
        pendingCaptureRequest = false
        captureDelayScheduled = false
        autoCaptureScheduled = false
        quickCameraInitializing = false
        quickCameraReady = false
        quickCameraReadyAtElapsedMs = 0L
        modelLoading = false
        modelLoaded = false
        hiddenRiskNcnn?.clearFrameState()
        hiddenRiskNcnn = null
        uiHandler.removeCallbacks(captureDelayRunnable)
        uiHandler.removeCallbacks(captureTimeoutRunnable)
        uiHandler.removeCallbacks(autoCaptureRunnable)
        uiHandler.removeCallbacks(statusIndicatorHideRunnable)
        currentDetectionStatus = DetectionStatus.NONE
        statusIndicatorVisible = false
        // 重置超时恢复计数器
        consecutiveTimeoutCount = 0
        cameraRestartAttempts = 0
        isCameraRestarting = false
        QuickCameraManager.releaseCamera()
    }

    private fun failWorkflow(message: String) {
        Log.e(TAG, "workflow failed: $message")
        resetForRetry()

        if (pageState == PageState.LOADING || pageState == PageState.LOAD_ERROR) {
            tvLoadErrorMessage.text = message
            showPage(PageState.LOAD_ERROR)
        } else {
            tvDeviceErrorMessage.text = message
            showPage(PageState.DEVICE_ERROR)
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
