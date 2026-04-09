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
import android.util.Base64
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
import com.rokid.glass.utils.BitmapUtils
import com.rokid.glass.utils.HttpUtils
import com.rokid.glass.utils.SSEUtil
import com.rokid.glesse.R
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.sdk.base.data.offlineCmd.bean.VoiceAction
import com.rokid.security.glass3.sdk.base.data.offlineCmd.listener.IVoiceCallback
import okhttp3.Response
import okhttp3.sse.EventSource
import java.io.ByteArrayOutputStream
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

    private val labelDisplayNames = mapOf(
        "T_btn" to "T字按钮",
        "tee_joint" to "三通接口",
        "cutoff_linkage" to "切断联动装置",
        "cassette_stove" to "卡式炉",
        "gas_alarm" to "可燃气体报警器",
        "exit_sign" to "安全出口标志",
        "fire_cabinet" to "室内消火栓箱",
        "hydrant_outdoor" to "室外消火栓",
        "industrial_gas_detector" to "工业可燃气体探测器",
        "emergency_light" to "应急灯",
        "exhaust_fan" to "排气扇",
        "hydrant_nozzle" to "栓口",
        "regulator" to "气瓶调压阀",
        "oxygen_cylinder" to "氧气瓶",
        "hose" to "水带",
        "nozzle" to "水枪",
        "pump_connector" to "水泵接合器",
        "lpg_cylinder" to "液化石油气瓶",
        "extinguisher" to "灭火器",
        "extinguisher_box" to "灭火器箱",
        "charcoal_stove" to "炭炉",
        "igniter" to "点火针",
        "coal_stove" to "煤炉",
        "lighting_fixture" to "照明灯具",
        "flameout_protection" to "熄火保护装置",
        "gas_range" to "燃气灶",
        "electric_tricycle" to "电动三轮车",
        "electric_bike" to "电动车",
        "load_switch" to "空气开关",
        "gas_hose" to "软管",
        "door_closer" to "防火门闭门器",
        "door_sequencer" to "防火门顺序器",
        "security_window" to "防盗窗",
    )

    companion object {
        private const val TAG = "AiInspection"
        private const val REQUEST_MEDIA_PERMISSION = 201
        private const val VOICE_REGISTER_RETRY_MS = 500L
        private const val CAPTURE_TIMEOUT_MS = 1000L  // 从15000改为1000，快速超时并自动重试
        private const val MAX_CONSECUTIVE_TIMEOUTS = 3
        private const val MAX_CAMERA_RESTART_ATTEMPTS = 3
        private const val CAPTURE_WARMUP_MS = 1200L
        private const val HAZARD_ALERT_HOLD_MS = 2500L
        private const val AUTO_CAPTURE_INTERVAL_MS = 1000L
        // 传感器层面5x裁剪，直接输出640x640，无需软件裁剪
        private val QUICK_CAPTURE_SIZE = Size(640, 640)

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
        DEVICE_ERROR,     // 设备异常
        INSPECTION_GUIDE, // 巡检操作说明（含确认开始）
        DETECTING,        // 自动取景识别中
        SAFE_AREA,        // 安全区域
        HAZARD_ALERT,     // 发现安全隐患
        STREAM_RESPONSE,  // 深度识别隐患，流式回答 + 保存确认
        SYNC_SUCCESS,     // 保存成功
        END_REPORT,       // 巡检结束报告
    }

    /**
     * 隐患判断结果。sealed class 携带附加数据用于 UI 展示。
     */
    private sealed class HazardJudgeResult {
        /** 所有配对组完整，判定为安全；completedGroups=已完整的配对组列表 */
        data class NoHazard(val completedGroups: List<List<String>>) : HazardJudgeResult()
        /** 某配对组不完整：presentLabels=已检测到，missingLabels=缺失的 */
        data class HasHazard(
            val presentLabels: List<String>,
            val missingLabels: List<String>,
        ) : HazardJudgeResult()
        /** 未检测到任何配对相关 label，疑似隐患：detectedLabels=所有检测到的标签 */
        data class MayHazard(val detectedLabels: List<String>) : HazardJudgeResult()
    }

    /**
     * 检测状态，用于图标提示逻辑。
     */
    private enum class DetectionStatus {
        NONE,       // 初始/检测中
        MAY_HAZARD, // 疑似有隐患
        HAS_HAZARD, // 有隐患
        NO_HAZARD   // 无隐患
    }

    private fun evaluateHazardWithJudgment(snapshot: NativeInferenceStats?): HazardJudgeResult {
        // 调用方已保证 detectionCount > 0
        if (snapshot == null || snapshot.detections == null) {
            return HazardJudgeResult.HasHazard(emptyList(), emptyList())
        }

        // 定义必须同时出现的label组，每组内所有标签必须全部检测到才算完整
        val requiredGroups = listOf(
            listOf("T_btn", "load_switch"),                              // T字按钮 + 负荷开关
            listOf("lpg_cylinder", "gas_alarm"),                        // 液化石油气瓶 + 可燃气体报警器
            listOf("gas_range", "flameout_protection"),                  // 燃气灶 + 熄火保护装置
            listOf("fire_cabinet", "hydrant_nozzle", "hose", "nozzle")   // 室内消火栓箱 + 栓口 + 水带 + 水枪
        )

        // 收集当前检测到的所有label
        val detectedLabels = snapshot.detections.map { it.label }.toSet()
        val allRelevantLabels = requiredGroups.flatten().toSet()

        // 情况4：没有检测到任何配对相关的label → 疑似隐患
        if (detectedLabels.none { it in allRelevantLabels }) {
            val detected = snapshot.detections.map { it.label }.distinct()
            Log.d(TAG, "未检测到任何配对相关的label，判定为疑似隐患，检测到: $detected")
            return HazardJudgeResult.MayHazard(detected)
        }

        // 只校验当前命中了至少一个成员的配对组，整组都未出现不视为当前画面的配对错误。
        val matchedGroups = requiredGroups.filter { group ->
            group.any { it in detectedLabels }
        }

        // 检查每一组：组内所有label必须全部出现
        val completedGroups = mutableListOf<List<String>>()
        for (group in matchedGroups) {
            val present = group.filter { it in detectedLabels }
            val missing = group.filter { it !in detectedLabels }

            when {
                // 部分出现：这组不完整，有隐患
                missing.isNotEmpty() -> {
                    Log.d(TAG, "配对不完整: 检测到 ${present.joinToString("、")} 但缺少 ${missing.joinToString("、")}，判定为有隐患")
                    return HazardJudgeResult.HasHazard(present, missing)
                }
                // 全部出现：这组完整，记录
                else -> {
                    Log.d(TAG, "配对完整: ${group.joinToString(" + ")}")
                    completedGroups.add(group)
                }
            }
        }

        // 所有组都完整
        Log.d(TAG, "所有配对组都完整，判定为安全")
        return HazardJudgeResult.NoHazard(completedGroups)
    }

    private fun localizeLabel(label: String): String {
        return labelDisplayNames[label] ?: label
    }

    private fun localizeLabels(labels: List<String>): String {
        return labels
            .map(::localizeLabel)
            .distinct()
            .joinToString("、")
    }

    /** 根据配对组内包含的标签，返回该组的代表名（用于 NO_HAZARD 显示） */
    private fun getGroupDisplayName(group: List<String>): String {
        return when {
            "load_switch" in group -> "空气开关"
            "lpg_cylinder" in group -> "液化石油气瓶"
            "gas_range" in group -> "燃气灶"
            "fire_cabinet" in group -> "消火栓"
            else -> localizeLabels(group)
        }
    }

    /** 根据 HasHazard 的 present/missing 标签生成专属描述（用于 HAS_HAZARD 行2） */
    private fun buildHazardDescription(presentLabels: List<String>, missingLabels: List<String>): String {
        return when {
            "load_switch" in presentLabels || "T_btn" in presentLabels ->
                "负荷开关疑似未安装漏电保护器"
            "lpg_cylinder" in presentLabels || "gas_alarm" in presentLabels ->
                "请检查液化石油气瓶附近是否安装燃气报警器"
            "gas_range" in presentLabels || "flameout_protection" in presentLabels ->
                "燃气灶炉头内部暂未发现熄火保护装置"
            presentLabels.any { it in listOf("fire_cabinet", "hydrant_nozzle", "hose", "nozzle") } -> {
                val presentNames = localizeLabels(presentLabels)
                val missingNames = localizeLabels(missingLabels)
                "消火栓内部发现${presentNames}，疑似缺少${missingNames}"
            }
            else -> localizeLabels(presentLabels + missingLabels) + "没有同时出现"
        }
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
    private lateinit var viewStatusDot: View
    private lateinit var ivHazardIcon: ImageView  // 隐患/疑似隐患状态图标
    private lateinit var tvHazardTitle: TextView   // 隐患提示标题
    private lateinit var tvLabel: TextView         // 检测到的标签文字
    private lateinit var tvActionHint: TextView    // 操作提示：点击进行深度识别
    private lateinit var tvStreamContent: TextView
    private lateinit var scrollContent: ScrollView
    private lateinit var tvSyncPrompt: TextView
    private lateinit var layoutSyncSuccess: LinearLayout
    private lateinit var tvSyncSuccessHint: TextView
    private lateinit var layoutLoadError: FrameLayout
    private lateinit var tvLoadErrorMessage: TextView
    private lateinit var tvLoadErrorHint: TextView
    private lateinit var layoutLensBlocked: FrameLayout
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
    private val guideVoiceAction = VoiceAction("开始", "kai shi", object : IVoiceCallback.Stub() {
        override fun onVoiceTriggered() {
            runOnUiThread {
                if (pageState == PageState.INSPECTION_GUIDE) {
                    transitionToDetection()
                }
            }
        }
    })
    private val detectingDeepAnalysisVoiceAction = VoiceAction("分析", "fen xi", object : IVoiceCallback.Stub() {
        override fun onVoiceTriggered() {
            runOnUiThread {
                if (pageState == PageState.DETECTING) {
                    requestStreamingAnalysis()
                }
            }
        }
    })
    private val detectingExitVoiceAction = VoiceAction("退出", "tui chu", object : IVoiceCallback.Stub() {
        override fun onVoiceTriggered() {
            runOnUiThread {
                if (pageState == PageState.DETECTING) {
                    finishInspection()
                }
            }
        }
    })
    private val streamConfirmVoiceAction = VoiceAction("确认", "que ren", object : IVoiceCallback.Stub() {
        override fun onVoiceTriggered() {
            runOnUiThread {
                if (pageState == PageState.STREAM_RESPONSE && !streamingInProgress) {
                    syncToPhone()
                }
            }
        }
    })
    private val streamRejectVoiceAction = VoiceAction("取消", "qu xiao", object : IVoiceCallback.Stub() {
        override fun onVoiceTriggered() {
            runOnUiThread {
                if (pageState == PageState.STREAM_RESPONSE) {
                    returnToDetecting()
                }
            }
        }
    })
    private val syncContinueVoiceAction = VoiceAction("继续", "ji xu", object : IVoiceCallback.Stub() {
        override fun onVoiceTriggered() {
            runOnUiThread {
                if (pageState == PageState.SYNC_SUCCESS) {
                    returnToDetecting()
                }
            }
        }
    })
    private val syncExitVoiceAction = VoiceAction("退出", "tui chu", object : IVoiceCallback.Stub() {
        override fun onVoiceTriggered() {
            runOnUiThread {
                if (pageState == PageState.SYNC_SUCCESS) {
                    finishInspection()
                }
            }
        }
    })

    private var hiddenRiskNcnn: HiddenRiskNcnn? = null
    private var destroyed = false
    private var isActivityResumed = false
    private var isWorkflowActive = false
    private var registeredPageVoiceActions: List<VoiceAction> = emptyList()
    private var pageVoiceRegisterRetryCount = 0
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
    private var pendingStreamStart = false
    private var lastAnalysisText = ""
    private var latestHazardBitmap: Bitmap? = null
    private var hazardCaptureService: HazardCaptureService? = null

    // 连续推理模式：不设固定间隔，推理空闲立即取下一帧
    private var continuousInferenceMode = true

    // SSE 相关
    private var currentEventSource: EventSource? = null
    private var sseUtil: SSEUtil = SSEUtil()

    // 本次拍照上传的会话 ID，用于与 save 接口保持一致的指纹
    private var sessionId = ""

    // 检测状态图标提示
    private var currentDetectionStatus: DetectionStatus = DetectionStatus.NONE
    private var statusIndicatorVisible = false
    private val STATUS_INDICATOR_DURATION_MS = 2000L

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

    /** 录制指示灯闪烁动画：1.0 → 0.2 → 1.0 循环，模拟拍摄状态灯 */
    private val dotBlinkAnimation: AlphaAnimation by lazy {
        AlphaAnimation(1.0f, 0.2f).apply {
            duration = 600L
            repeatMode = Animation.REVERSE
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

    private val pageVoiceRegisterRetryRunnable = object : Runnable {
        override fun run() {
            if (!shouldEnablePageVoiceCommands()) {
                return
            }
            if (registerPageVoiceCommandsIfReady()) {
                return
            }
            RokidSdkManager.ensureInitialized()
            uiHandler.postDelayed(this, VOICE_REGISTER_RETRY_MS)
        }
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
        viewStatusDot = findViewById(R.id.viewStatusDot)
        ivHazardIcon = findViewById(R.id.ivHazardIcon)
        tvHazardTitle = findViewById(R.id.tvHazardTitle)
        tvLabel = findViewById(R.id.tvLabel)
        tvActionHint = findViewById(R.id.tvActionHint)
        tvStreamContent = findViewById(R.id.tvStreamContent)
        scrollContent = findViewById(R.id.scrollContent)
        tvSyncPrompt = findViewById(R.id.tvSyncPrompt)
        layoutSyncSuccess = findViewById(R.id.layoutSyncSuccess)
        tvSyncSuccessHint = findViewById(R.id.tvSyncSuccessHint)
        layoutLoadError = findViewById(R.id.layoutLoadError)
        tvLoadErrorMessage = findViewById(R.id.tvLoadErrorMessage)
        tvLoadErrorHint = findViewById(R.id.tvLoadErrorHint)
        layoutLensBlocked = findViewById(R.id.layoutLensBlocked)
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
        syncPageVoiceCommandState()
        if (pageState == PageState.DETECTING) {
            scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
        }
    }

    override fun onStart() {
        super.onStart()
        isWorkflowActive = true
        syncPageVoiceCommandState()
    }

    override fun onPause() {
        isActivityResumed = false
        stopPageVoiceRegisterRetry()
        unregisterPageVoiceCommands()
        uiHandler.removeCallbacks(captureDelayRunnable)
        uiHandler.removeCallbacks(autoCaptureRunnable)
        captureDelayScheduled = false
        autoCaptureScheduled = false
        // 关闭当前 SSE 连接
        currentEventSource?.cancel()
        currentEventSource = null
        super.onPause()
    }

    override fun onStop() {
        isWorkflowActive = false
        stopPageVoiceRegisterRetry()
        unregisterPageVoiceCommands()
        uiHandler.removeCallbacks(captureDelayRunnable)
        uiHandler.removeCallbacks(autoCaptureRunnable)
        captureDelayScheduled = false
        autoCaptureScheduled = false
        // 关闭当前 SSE 连接
        currentEventSource?.cancel()
        currentEventSource = null
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        streamCallbackActive = false
        stopPageVoiceRegisterRetry()
        unregisterPageVoiceCommands()
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
        hazardCaptureService?.shutdown()
        if (isFinishing && !isChangingConfigurations) {
            RokidSdkManager.release()
        }
        // 关闭当前 SSE 连接
        currentEventSource?.cancel()
        currentEventSource = null
        super.onDestroy()
    }

    // ==================== 输入事件 ====================

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        // 拦截 back/双击：在流式回答和同步成功页面应返回检测，而不是退出 Activity
        if (keyEvent == GlassKeyEvent.KEYCODE_BACK || keyEvent == GlassKeyEvent.KEYCODE_DOUBLE_CLICK) {
            Log.d(TAG, "back/双击事件，当前页面状态: $pageState")
            when (pageState) {
                PageState.STREAM_RESPONSE -> {
                    returnToDetecting()
                    return true
                }

                PageState.SYNC_SUCCESS -> {
                    finishInspection()
                    return true
                }

                PageState.DETECTING -> {
                    // 检测页面双击返回菜单
                    finishInspection()
                    return true
                }

                else -> {}
            }
        }
        when (keyEvent) {
            GlassKeyEvent.KEYCODE_CLICK -> {
                when (pageState) {
                    PageState.INSPECTION_GUIDE -> {
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

                    PageState.DETECTING -> {
                        // DETECTING 状态下，单击进入流式分析，避免与自动检测抢占相机。
                        requestStreamingAnalysis()
                        return true
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
                    PageState.LOAD_ERROR,
                    PageState.DEVICE_ERROR,
                    PageState.LENS_BLOCKED,
                    PageState.SAFE_AREA,
                    PageState.END_REPORT -> {
                        Log.d(TAG, "双击: 退出页面")
                        finish()
                        return true
                    }

                    PageState.STREAM_RESPONSE,
                    PageState.SYNC_SUCCESS -> {
                        Log.d(TAG, "双击: 返回检测页面")
                        returnToDetecting()
                        return true
                    }

                    PageState.DETECTING -> {
                        // 检测页面双击返回菜单
                        finishInspection()
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
            syncPageVoiceCommandState()
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
            scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
        }
    }

    private fun startModelLoadIfNeeded(local: HiddenRiskNcnn) {
        if (modelLoading) return

        modelLoading = true
        animateProgressTo(50)
        tvLoadingSubtitle.text = "正在加载检测模型…"

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
        currentEventSource?.cancel()
        currentEventSource = null
        streamCallbackActive = false
        streamingInProgress = false
        pendingStreamStart = false
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

    private fun finishInspection() {
        currentEventSource?.cancel()
        currentEventSource = null
        streamCallbackActive = false
        streamingInProgress = false
        pendingStreamStart = false
        finish()
    }

    // ==================== 拍照与推理 ====================

    private fun startSampleCaptureIfNeeded() {
        if (!pendingCaptureRequest || captureInProgress) return
        if (!isActivityResumed || !isWorkflowActive) return
        if (pageState != PageState.DETECTING) return
        if (pendingStreamStart) return

        if (!quickCameraReady || !QuickCameraManager.isGpuCaptureWarm()) {
            if (!quickCameraReady) {
                initCameraAndTransition()
            }
            return
        }

        val readyElapsedMs = quickCameraReadyAtElapsedMs.takeIf { it > 0L } ?: sdkReadyAtElapsedMs
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
                    if (startPendingStreamAnalysis()) {
                        return@post
                    }
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
                    Log.d(
                        TAG,
                        "inference success=$success detectionCount=${snapshot?.detectionCount ?: -1}"
                    )
                    if (destroyed) return@post
                    if (!success) {
                        if (startPendingStreamAnalysis()) {
                            return@post
                        }
                        scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
                    } else {
                        val count = snapshot?.detectionCount ?: 0
                        if (count == 0) {
                            if (startPendingStreamAnalysis()) {
                                return@post
                            }
                            // 未检测到目标：跳过，不改变任何状态，让现有倒计时继续
                            scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
                        } else {
                            // detectionCount > 0，进入隐患判断
                            val judgeResult = evaluateHazardWithJudgment(snapshot)
                            val newStatus: DetectionStatus
                            val titleText: String
                            val labelText: String
                            when (judgeResult) {
                                is HazardJudgeResult.NoHazard -> {
                                    newStatus = DetectionStatus.NO_HAZARD
                                    titleText = "区域安全"
                                    // 显示已完整的配对组代表名
                                    val groupNames = judgeResult.completedGroups
                                        .map { getGroupDisplayName(it) }
                                        .distinct()
                                        .joinToString("、")
                                    labelText = if (groupNames.isNotEmpty()) "检测到符合${groupNames}" else "未发现安全隐患"
                                }
                                is HazardJudgeResult.HasHazard -> {
                                    // 传感器已直接输出640x640，无需软件裁剪
                                    ensureHazardCaptureService().saveHazardCapture(
                                        latestHazardBitmap,
                                        snapshot
                                    )
                                    newStatus = DetectionStatus.HAS_HAZARD
                                    titleText = "检测到疑似隐患"
                                    labelText = buildHazardDescription(judgeResult.presentLabels, judgeResult.missingLabels)
                                }
                                is HazardJudgeResult.MayHazard -> {
                                    newStatus = DetectionStatus.MAY_HAZARD
                                    titleText = "检测到疑似隐患"
                                    val detected = localizeLabels(judgeResult.detectedLabels)
                                    labelText = "检测到${detected}"
                                }
                            }

                            if (startPendingStreamAnalysis(latestHazardBitmap)) {
                                return@post
                            }
                            handleDetectionResult(newStatus, titleText, labelText)
                        }
                    }
                }
            }) {
            frame.hardwareBuffer.close()
            inferenceRunning.set(false)
            if (startPendingStreamAnalysis()) {
                return
            }
            scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
        }
    }

    // ==================== 隐患处理流程 ====================

    /**
     * 处理检测结果，控制图标显示和倒计时。
     */
    private fun handleDetectionResult(newStatus: DetectionStatus, titleText: String, labelText: String) {
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
                showStatusIndicator(newStatus, titleText, labelText)
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
    private fun showStatusIndicator(status: DetectionStatus, titleText: String = "", labelText: String = "") {
        statusIndicatorVisible = true
        when (status) {
            DetectionStatus.HAS_HAZARD -> {
                ivHazardIcon.setImageResource(R.drawable.ic_question_circle)
                tvHazardTitle.text = titleText
                tvLabel.text = labelText
                tvActionHint.visibility = View.VISIBLE
                layoutHazardAlert.visibility = View.VISIBLE
                layoutHazardAlertBottom.visibility = View.VISIBLE
                layoutSafeArea.visibility = View.GONE
            }

            DetectionStatus.MAY_HAZARD -> {
                ivHazardIcon.setImageResource(R.drawable.ic_question_circle)
                tvHazardTitle.text = titleText
                tvLabel.text = labelText
                tvActionHint.visibility = View.VISIBLE
                layoutHazardAlert.visibility = View.VISIBLE
                layoutHazardAlertBottom.visibility = View.VISIBLE
                layoutSafeArea.visibility = View.GONE
            }

            DetectionStatus.NO_HAZARD -> {
                ivHazardIcon.setImageResource(R.drawable.ic_check_circle)
                tvHazardTitle.text = titleText
                tvLabel.text = labelText
                tvActionHint.visibility = View.VISIBLE
                layoutHazardAlert.visibility = View.VISIBLE
                layoutHazardAlertBottom.visibility = View.VISIBLE
                layoutSafeArea.visibility = View.GONE
            }

            else -> hideStatusIndicator()
        }
    }

    /**
     * 隐藏状态指示器图标。
     */
    private fun hideStatusIndicator() {
        statusIndicatorVisible = false
        tvActionHint.visibility = View.GONE
        layoutHazardAlert.visibility = View.GONE
        layoutHazardAlertBottom.visibility = View.GONE
        layoutSafeArea.visibility = View.GONE
    }

    /**
     * 开始流式分析（直接使用 SSE 实现）
     */
    private fun startStreamingAnalysis(bitmap: Bitmap?) {
        val targetBitmap = bitmap?.takeIf { !it.isRecycled }
        if (targetBitmap != null) {
            sendBitmapToSSE(targetBitmap)
            return
        }
        captureAndSendToSSE()
    }

    /**
     * 调用后端接口同步隐患记录，成功后显示同步成功页面。
     */
    private fun syncToPhone() {
        HazardStreamService.syncToPhone(
            lastAnalysisText,
            sessionId,
            object : HazardStreamService.SyncCallback {
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
        if (pendingStreamStart || streamingInProgress || streamCallbackActive) return false
        if (captureInProgress || captureDelayScheduled || quickCameraInitializing || pendingCaptureRequest) return false
        if (inferenceRunning.get()) return false
        if (pageState != PageState.DETECTING) return false
        return true
    }

    private fun scheduleAutoCaptureIfNeeded(delayMs: Long) {
        if (autoCaptureScheduled || destroyed || !isActivityResumed || !isWorkflowActive) return
        if (pendingStreamStart || streamingInProgress || streamCallbackActive) return
        if (pageState != PageState.DETECTING) return
        autoCaptureScheduled = true
        // 连续推理模式下，延迟设为0，推理空闲立即取下一帧
        val actualDelay = if (continuousInferenceMode) 0L else delayMs
        uiHandler.postDelayed(autoCaptureRunnable, actualDelay)
    }

    // ==================== UI 页面切换 ====================

    private fun showPage(state: PageState) {
        pageState = state
        layoutLoading.visibility = if (state == PageState.LOADING) View.VISIBLE else View.GONE
        tvLoadingHint.visibility = if (state == PageState.LOADING) View.VISIBLE else View.GONE
        layoutDetection.visibility = if (state == PageState.DETECTING) View.VISIBLE else View.GONE
        layoutHazardAlert.visibility =
            if (state == PageState.HAZARD_ALERT) View.VISIBLE else View.GONE

        layoutHazardAlertBottom.visibility =
            if (state == PageState.HAZARD_ALERT) View.VISIBLE else View.GONE
        layoutStreamResponse.visibility =
            if (state == PageState.STREAM_RESPONSE) View.VISIBLE else View.GONE
        layoutSyncSuccess.visibility =
            if (state == PageState.SYNC_SUCCESS) View.VISIBLE else View.GONE
        tvSyncSuccessHint.visibility =
            if (state == PageState.SYNC_SUCCESS) View.VISIBLE else View.GONE

        layoutLoadError.visibility = if (state == PageState.LOAD_ERROR) View.VISIBLE else View.GONE
        layoutLensBlocked.visibility =
            if (state == PageState.LENS_BLOCKED) View.VISIBLE else View.GONE
        layoutDeviceError.visibility =
            if (state == PageState.DEVICE_ERROR) View.VISIBLE else View.GONE
        layoutInspectionGuide.visibility =
            if (state == PageState.INSPECTION_GUIDE) View.VISIBLE else View.GONE
        layoutSafeArea.visibility = if (state == PageState.SAFE_AREA) View.VISIBLE else View.GONE
        layoutEndReport.visibility = if (state == PageState.END_REPORT) View.VISIBLE else View.GONE

        if (state == PageState.LOADING) {
            ivLoadingSpinner.startAnimation(loadingRotateAnimation)
        } else {
            ivLoadingSpinner.clearAnimation()
        }
        // DETECTING 状态时启动指示灯闪烁，其他状态停止
        if (state == PageState.DETECTING) {
            viewStatusDot.startAnimation(dotBlinkAnimation)
        } else {
            viewStatusDot.clearAnimation()
            viewStatusDot.alpha = 1f
        }
        if (state == PageState.SYNC_SUCCESS) {
            // 重置透明度（上次渐隐后可能为0）
            layoutSyncSuccess.alpha = 1f
            tvSyncSuccessHint.alpha = 1f
        }
        syncPageVoiceCommandState()
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
        pendingStreamStart = false
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

    private fun currentPageVoiceActions(): List<VoiceAction> {
        return when (pageState) {
            PageState.INSPECTION_GUIDE -> listOf(guideVoiceAction)
            PageState.DETECTING -> listOf(detectingDeepAnalysisVoiceAction, detectingExitVoiceAction)
            PageState.STREAM_RESPONSE -> listOf(streamConfirmVoiceAction, streamRejectVoiceAction)
            PageState.SYNC_SUCCESS -> listOf(syncContinueVoiceAction, syncExitVoiceAction)
            else -> emptyList()
        }
    }

    private fun shouldEnablePageVoiceCommands(): Boolean {
        return !destroyed &&
            isActivityResumed &&
            isWorkflowActive &&
            currentPageVoiceActions().isNotEmpty()
    }

    private fun syncPageVoiceCommandState() {
        val targetActions = currentPageVoiceActions()
        if (!shouldEnablePageVoiceCommands()) {
            stopPageVoiceRegisterRetry()
            unregisterPageVoiceCommands()
            return
        }
        if (registeredPageVoiceActions == targetActions) {
            return
        }
        unregisterPageVoiceCommands()
        schedulePageVoiceRegisterRetry(immediate = true)
    }

    private fun schedulePageVoiceRegisterRetry(immediate: Boolean) {
        if (!shouldEnablePageVoiceCommands()) {
            return
        }
        uiHandler.removeCallbacks(pageVoiceRegisterRetryRunnable)
        if (immediate) {
            uiHandler.post(pageVoiceRegisterRetryRunnable)
        } else {
            uiHandler.postDelayed(pageVoiceRegisterRetryRunnable, VOICE_REGISTER_RETRY_MS)
        }
    }

    private fun stopPageVoiceRegisterRetry() {
        uiHandler.removeCallbacks(pageVoiceRegisterRetryRunnable)
        pageVoiceRegisterRetryCount = 0
    }

    private fun registerPageVoiceCommandsIfReady(): Boolean {
        val targetActions = currentPageVoiceActions()
        if (!shouldEnablePageVoiceCommands() || targetActions.isEmpty()) {
            return false
        }
        if (registeredPageVoiceActions == targetActions) {
            return true
        }

        val offlineCmdService = runCatching { GlassSdk.getGlassOfflineCmdService() }
            .onFailure { error ->
                Log.w(TAG, "获取页面离线语音服务失败: ${error.message}")
            }
            .getOrNull()
        if (offlineCmdService == null) {
            pageVoiceRegisterRetryCount++
            if (pageVoiceRegisterRetryCount == 1 || pageVoiceRegisterRetryCount % 10 == 0) {
                Log.w(
                    TAG,
                    "页面离线语音服务未就绪，继续重试: attempt=$pageVoiceRegisterRetryCount pageState=$pageState sdkState=${RokidSdkManager.state}"
                )
            }
            return false
        }

        targetActions.forEach { offlineCmdService.add(it) }
        registeredPageVoiceActions = targetActions
        pageVoiceRegisterRetryCount = 0
        Log.i(TAG, "已注册页面语音命令 pageState=$pageState commands=${targetActions.joinToString { it.toString() }}")
        return true
    }

    private fun unregisterPageVoiceCommands() {
        if (registeredPageVoiceActions.isEmpty()) {
            return
        }

        runCatching {
            GlassSdk.getGlassOfflineCmdService()?.let { service ->
                registeredPageVoiceActions.forEach(service::remove)
            }
        }.onFailure { error ->
            Log.w(TAG, "移除页面语音命令失败: ${error.message}")
        }
        registeredPageVoiceActions = emptyList()
        Log.i(TAG, "已移除页面语音命令")
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

    // ==================== SSE 流式分析相关方法 ====================

    /**
     * 拍照并通过 SSE 接口发送数据
     */
    private fun captureAndSendToSSE() {
        QuickCameraManager.takeGpuFrame { frame ->
            uiHandler.post {
                if (frame == null) {
                    Log.e(TAG, "拍照失败")
                    handleSSEError("拍照失败")
                    return@post
                }

                // 将 HardwareBuffer 转换为 Bitmap
                val bitmap = frame.previewBitmap
                frame.hardwareBuffer.close()

                if (bitmap == null) {
                    Log.e(TAG, "HardwareBuffer 转换为 Bitmap 失败")
                    handleSSEError("拍照失败")
                    return@post
                }

                sendBitmapToSSE(bitmap)
            }
        }
    }

    /**
     * 请求进入流式分析。
     * 若自动检测正在占用相机，则等待本轮检测完成后复用最近帧继续。
     */
    private fun requestStreamingAnalysis() {
        if (streamingInProgress || streamCallbackActive) {
            return
        }
        pendingStreamStart = true
        pauseAutoCaptureForStreaming()
        if (captureInProgress || inferenceRunning.get() || QuickCameraManager.isCameraDoing()) {
            Log.i(
                TAG,
                "stream request queued captureInProgress=$captureInProgress inferenceRunning=${inferenceRunning.get()} cameraDoing=${QuickCameraManager.isCameraDoing()}",
            )
            return
        }
        pendingStreamStart = false
        captureAndSendToSSE()
    }

    /**
     * 在当前检测结束后启动流式分析。
     * 优先复用刚完成检测的最近一帧，避免再次与自动抓拍竞争相机。
     */
    private fun startPendingStreamAnalysis(preferredBitmap: Bitmap? = null): Boolean {
        if (!pendingStreamStart || destroyed) {
            return false
        }
        if (streamingInProgress || streamCallbackActive) {
            pendingStreamStart = false
            return true
        }
        if (captureInProgress || inferenceRunning.get() || QuickCameraManager.isCameraDoing()) {
            return false
        }

        val bitmap = preferredBitmap?.takeIf { !it.isRecycled }
            ?: latestHazardBitmap?.takeIf { !it.isRecycled }
        pendingStreamStart = false
        if (bitmap != null) {
            Log.i(TAG, "start pending stream with latest detection frame")
            sendBitmapToSSE(bitmap)
        } else {
            Log.i(TAG, "start pending stream with fresh capture")
            captureAndSendToSSE()
        }
        return true
    }

    private fun pauseAutoCaptureForStreaming() {
        pendingCaptureRequest = false
        captureDelayScheduled = false
        autoCaptureScheduled = false
        uiHandler.removeCallbacks(captureDelayRunnable)
        uiHandler.removeCallbacks(captureTimeoutRunnable)
        uiHandler.removeCallbacks(autoCaptureRunnable)
    }

    private fun sendBitmapToSSE(bitmap: Bitmap) {
        val base64Image = bitmapToBase64(bitmap)
        sendImageToSSE(base64Image)
    }

    /**
     * 将 Bitmap 转换为 Base64 字符串
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    /**
     * 通过 SSE 接口发送图像数据
     */
    private fun sendImageToSSE(base64Image: String) {
        // 关闭之前的连接
        currentEventSource?.cancel()
        // 每次拍照生成新 sessionId，格式：时间戳_snCode
        val snCode = RokidSdkManager.getSerialNumber()
        sessionId = "${System.currentTimeMillis()}_${snCode}"

        showPage(PageState.STREAM_RESPONSE)
        tvStreamContent.text = ""
        streamingInProgress = true
        streamCallbackActive = true
        tvSyncPrompt.visibility = View.INVISIBLE

        sseUtil.connect(
            imageUrl = base64Image,
            snCode = snCode,
            sessionId = sessionId,
            listener = object : SSEUtil.SSEListener {
                override fun onOpened() {
                    Log.d(TAG, "SSE 连接已建立")
                    uiHandler.post {
                        tvStreamContent.text = "正在分析隐患..."
                    }
                }

                override fun onMessage(data: String) {
                    Log.d(TAG, "收到 SSE 消息: $data")
                    uiHandler.post {
                        tvStreamContent.text = data
                        scrollContent.post {
                            scrollContent.fullScroll(View.FOCUS_DOWN)
                        }
                    }
                }

                override fun onClosed() {
                    Log.d(TAG, "SSE 连接已关闭")
                    uiHandler.post {
                        streamCallbackActive = false
                        streamingInProgress = false
                        lastAnalysisText = tvStreamContent.text.toString()
                        tvSyncPrompt.visibility = View.VISIBLE
                    }
                }

                override fun onFailure(t: Throwable?, response: Response?) {
                    Log.e(TAG, "SSE 连接失败", t)
                    val errorMessage = t?.message ?: response?.message ?: "未知错误"
                    handleSSEError(errorMessage)
                }

                override fun onEventSourceCreated(eventSource: EventSource) {
                    currentEventSource = eventSource
                }
            }
        )
    }

    /**
     * 处理 SSE 错误
     */
    private fun handleSSEError(errorMsg: String) {
        uiHandler.post {
            streamCallbackActive = false
            streamingInProgress = false
            pendingStreamStart = false
            tvStreamContent.text = "分析失败：$errorMsg"
            tvSyncPrompt.visibility = View.VISIBLE
        }
    }
}
