package com.rokid.glass.hiddenrisk

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.View
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
import com.rokid.glass.InspectionEndReportActivity
import com.rokid.glass.InspectionModeActivity
import com.rokid.glass.camera.QuickCameraManager
import com.rokid.glass.component.AlertActionConfig
import com.rokid.glass.component.AlertBehavior
import com.rokid.glass.component.AlertStatus
import com.rokid.glass.component.AlertStyle
import com.rokid.glass.component.StatusAlertModel
import com.rokid.glass.component.BottomPromptView
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.component.OperationGuideView
import com.rokid.glass.component.StatusAlertOverlayView
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.utils.BitmapUtils
import com.rokid.glass.utils.SSEUtil
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glass.workflow.InspectionWorkflowSession.WorkflowMode
import com.rokid.glesse.R
import okhttp3.Response
import okhttp3.sse.EventSource
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AI 巡检页面。
 * 流程：加载初始化 -> 自动拍照检测 -> 隐患提示/疑似隐患深度识别 -> 流式回答 -> 同步确认。
 */
class AiInspectionActivity : BaseGlassActivity(), RokidSdkManager.Listener {

    private lateinit var layoutLoading: View

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
        private const val CAPTURE_TIMEOUT_MS = 1000L  // 从15000改为1000，快速超时并自动重试
        private const val MAX_CONSECUTIVE_TIMEOUTS = 3
        private const val MAX_CAMERA_RESTART_ATTEMPTS = 3
        private const val CAPTURE_WARMUP_MS = 1200L
        private const val AUTO_CAPTURE_INTERVAL_MS = 1000L
        private const val ONLINE_FRAME_CAPTURE_INTERVAL_MS = 500L
        private const val ONLINE_DETECT_INTERVAL_MS = 1000L
        private const val ONLINE_DETECTION_FIRST_PACKET_TIMEOUT_MS = 2000L
        private const val ONLINE_FRAME_BUFFER_CAPACITY = 4

        private const val BACKEND_GPU = 1
        private const val GPU_PROFILE_BALANCED_FP16 = 1
        private const val DEFAULT_TARGET_INPUT_SIZE = 640
        private const val ENABLE_HIT_CAPTURE_SAVE = false
        private val QUICK_CAPTURE_SIZE = Size(640, 640)
    }

    /**
     * 页面的可见状态。
     */
    private enum class PageState {
        DETECTING,        // 自动取景识别中
        STREAM_RESPONSE,  // 深度识别隐患，流式回答 + 保存确认
        SYNC_SUCCESS,     // 保存成功
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

    private data class CapturedFramePayload(
        val jpegBytes: ByteArray,
        val width: Int,
        val height: Int,
        val timestamp: Long,
    )

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
    private lateinit var layoutDetection: FrameLayout
    private lateinit var statusAlertOverlay: StatusAlertOverlayView
    private lateinit var layoutStreamResponse: FrameLayout
    private lateinit var tvStreamContent: TextView
    private lateinit var scrollContent: ScrollView
    private lateinit var ivStreamThumbnail: ImageView
    private lateinit var bottomPromptSync: BottomPromptView
    private lateinit var operationGuideStream: OperationGuideView
    private var currentStreamThumbnail: Bitmap? = null
    private lateinit var layoutSyncSuccess: FrameLayout
    private lateinit var bottomPromptSuccess: BottomPromptView
    // 检测状态UI
    private lateinit var statusBarDetecting: GlassStatusBar
    private lateinit var statusBarStream: GlassStatusBar
    private lateinit var statusBarSyncSuccess: GlassStatusBar
    private lateinit var operationGuideSync: OperationGuideView
    private lateinit var tvDetectionStatus: TextView
    private lateinit var layoutHazardBanner: LinearLayout
    private lateinit var tvHazardBannerCount: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val nativeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val imageEncodeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val inferenceRunning = AtomicBoolean(false)
    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private val motionStabilityTracker by lazy { MotionStabilityTracker(this) }
    private val onlineHazardDetectionService by lazy { OnlineHazardDetectionService() }

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
    private var pageState = PageState.DETECTING
    private var streamingInProgress = false
    private var streamCallbackActive = false
    private var pendingStreamStart = false
    private var activeStreamRequestId = 0L
    private var lastAnalysisText = ""
    private var latestHazardPayload: CapturedFramePayload? = null
    private var hazardCaptureService: HazardCaptureService? = null
    private val mayHazardVerifyService by lazy { MayHazardDeepVerifyService() }
    private var mayHazardVerificationInProgress = false
    private var activeMayHazardRequestId = 0L
    private var mayHazardRequestHandle: MayHazardDeepVerifyService.RequestHandle? = null

    // 连续推理模式：不设固定间隔，推理空闲立即取下一帧
    private var continuousInferenceMode = true
    private var isMotionStable = false
    private var stableQualifiedAtMillis: Long? = null
    private var nextOnlineDetectRequestId = 0L
    private val onlineFrameBuffer = ArrayDeque<CapturedFramePayload>()
    private var onlineDetectHandle: OnlineHazardDetectionService.DetectionHandle? = null
    private var onlineFrameCaptureScheduled = false
    private var onlineDetectScheduled = false
    private var awaitingFirstOnlineDetect = false

    // SSE 相关
    private var currentEventSource: EventSource? = null
    private var sseUtil: SSEUtil = SSEUtil()
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
            if (!isOnlineMode()) {
                return
            }
            if (isStable) {
                Log.i(
                    TAG,
                    "motion stable qualified stableSinceMillis=$stableSinceMillis"
                )
                awaitingFirstOnlineDetect = true
                clearOnlineFrameBuffer("stable_qualified")
                if (pendingStreamStart && pageState == PageState.DETECTING && !captureInProgress && !inferenceRunning.get()) {
                    requestStreamingAnalysis()
                    return
                }
                scheduleOnlineFrameCaptureIfNeeded(0L)
            } else {
                Log.i(TAG, "motion unstable reset online pipeline")
                stopOnlinePipeline("motion_unstable", clearBufferedFrames = true)
            }
        }
    }

    // 本次拍照上传的会话 ID，用于与 save 接口保持一致的指纹
    private var sessionId = ""

    // 拍摄超时恢复机制
    private var consecutiveTimeoutCount = 0
    private var cameraRestartAttempts = 0
    private var isCameraRestarting = false

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
        logOnlineWorkflowCheckpoint("autoCaptureRunnable fired")
        if (!shouldAutoCaptureNow()) {
            logOnlineWorkflowCheckpoint("autoCaptureRunnable deferred")
            scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
            return@Runnable
        }
        pendingCaptureRequest = true
        logOnlineWorkflowCheckpoint("autoCaptureRunnable requestCapture")
        startSampleCaptureIfNeeded()
    }

    private val onlineFrameCaptureRunnable = Runnable {
        onlineFrameCaptureScheduled = false
        if (!shouldRunOnlineFrameCaptureLoop()) {
            logOnlineWorkflowCheckpoint("onlineFrameCaptureRunnable skip")
            return@Runnable
        }
        logOnlineWorkflowCheckpoint("onlineFrameCaptureRunnable tick")
        if (canStartOnlineFrameCaptureNow()) {
            pendingCaptureRequest = true
            startSampleCaptureIfNeeded()
        } else {
            logOnlineWorkflowCheckpoint("onlineFrameCaptureRunnable deferred")
        }
        scheduleOnlineFrameCaptureIfNeeded(ONLINE_FRAME_CAPTURE_INTERVAL_MS)
    }

    private val onlineDetectRunnable = Runnable {
        onlineDetectScheduled = false
        if (!shouldRunOnlineDetectLoop()) {
            logOnlineWorkflowCheckpoint("onlineDetectRunnable skip")
            return@Runnable
        }
        if (onlineDetectHandle != null) {
            Log.i(TAG, "online detect tick skipped reason=request_in_flight requestId=${onlineDetectHandle?.requestId}")
            scheduleOnlineDetectIfNeeded(ONLINE_DETECT_INTERVAL_MS)
            return@Runnable
        }
        val frame = consumeLatestOnlineFrameOrNull("detect_tick")
        if (frame == null) {
            Log.i(TAG, "online detect tick skipped reason=buffer_empty")
            scheduleOnlineDetectIfNeeded(ONLINE_DETECT_INTERVAL_MS)
            return@Runnable
        }
        awaitingFirstOnlineDetect = false
        startOnlineDetection(frame, "detect_tick")
    }

    // ==================== 生命周期 ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_inspection)

        layoutLoading = findViewById(R.id.layoutLoading)
        layoutDetection = findViewById(R.id.layoutDetection)
        statusAlertOverlay = findViewById(R.id.statusAlertOverlay)
        layoutStreamResponse = findViewById(R.id.layoutStreamResponse)
        tvStreamContent = findViewById(R.id.tvStreamContent)
        scrollContent = findViewById(R.id.scrollContent)
        ivStreamThumbnail = findViewById(R.id.ivStreamThumbnail)
        bottomPromptSync = findViewById(R.id.bottomPromptSync)
        operationGuideStream = findViewById(R.id.operationGuideStream)
        // 流式结果卡片高度限制在 onMessage / applyDebugSnapshotState 中动态处理
        layoutSyncSuccess = findViewById(R.id.layoutSyncSuccess)
        bottomPromptSuccess = findViewById(R.id.bottomPromptSuccess)
        // 检测状态 UI 初始化
        statusBarDetecting = findViewById(R.id.statusBarDetecting)
        statusBarStream = findViewById(R.id.statusBarStream)
        statusBarSyncSuccess = findViewById(R.id.statusBarSyncSuccess)
        operationGuideSync = findViewById(R.id.operationGuideSync)
        tvDetectionStatus = findViewById(R.id.tvDetectionStatus)
        layoutHazardBanner = findViewById(R.id.layoutHazardBanner)
        tvHazardBannerCount = findViewById(R.id.tvHazardBannerCount)

        // 设置检测页操作指引内容
        val operationGuideDetecting = findViewById<OperationGuideView>(R.id.operationGuideDetecting)
        operationGuideDetecting.setGuide(
            title = "操作指引",
            content = "说出\"分析\"\n说出\"返回\"\n说出\"结束\"\n单击 分析\n双击 返回"
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
        quickCameraReady = InspectionSession.isCameraReady
        if (quickCameraReady) {
            quickCameraReadyAtElapsedMs = SystemClock.elapsedRealtime()
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
        if (!isOnlineMode()) {
            pendingCaptureRequest = true
            startSampleCaptureIfNeeded()
        } else {
            pendingCaptureRequest = false
        }
        if (isOnlineMode()) {
            if (isMotionStable) {
                awaitingFirstOnlineDetect = true
                scheduleOnlineFrameCaptureIfNeeded(0L)
            }
        } else {
            scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
        }
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
            if (isOnlineMode()) {
                if (isMotionStable) {
                    scheduleOnlineFrameCaptureIfNeeded(0L)
                }
            } else {
                scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
            }
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
        uiHandler.removeCallbacks(captureDelayRunnable)
        uiHandler.removeCallbacks(autoCaptureRunnable)
        captureDelayScheduled = false
        autoCaptureScheduled = false
        stopOnlinePipeline("onPause", clearBufferedFrames = true)
        hideStatusAlertOverlay()
        cancelMayHazardVerification()
        cancelAllOnlineDetection()
        // 关闭当前 SSE 连接
        currentEventSource?.cancel()
        currentEventSource = null
        super.onPause()
    }

    override fun onStop() {
        isWorkflowActive = false
        if (debugSnapshotState != null) {
            super.onStop()
            return
        }
        uiHandler.removeCallbacks(captureDelayRunnable)
        uiHandler.removeCallbacks(autoCaptureRunnable)
        captureDelayScheduled = false
        autoCaptureScheduled = false
        stopOnlinePipeline("onStop", clearBufferedFrames = true)
        hideStatusAlertOverlay()
        cancelMayHazardVerification()
        cancelAllOnlineDetection()
        // 关闭当前 SSE 连接
        currentEventSource?.cancel()
        currentEventSource = null
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        streamCallbackActive = false
        inputSession.release()
        if (debugSnapshotState != null) {
            stopTimeAndBatteryUpdate()
            super.onDestroy()
            return
        }
        motionStabilityTracker.removeListener(motionStabilityListener)
        motionStabilityTracker.stop()
        uiHandler.removeCallbacks(captureDelayRunnable)
        uiHandler.removeCallbacks(captureTimeoutRunnable)
        uiHandler.removeCallbacks(autoCaptureRunnable)
        hideStatusAlertOverlay()
        cancelMayHazardVerification()
        cancelAllOnlineDetection()
        quickCameraInitializing = false
        quickCameraReady = false
        quickCameraReadyAtElapsedMs = 0L
        // 重置超时恢复计数器
        consecutiveTimeoutCount = 0
        cameraRestartAttempts = 0
        isCameraRestarting = false
        RokidSdkManager.removeListener(this)
        // 注意：不单独释放 QuickCameraManager 和 hiddenRiskNcnn，由 InspectionSession 管理生命周期
        nativeExecutor.shutdown()
        imageEncodeExecutor.shutdown()
        runCatching { nativeExecutor.awaitTermination(2, TimeUnit.SECONDS) }
        runCatching { imageEncodeExecutor.awaitTermination(2, TimeUnit.SECONDS) }
        latestHazardPayload = null
        hazardCaptureService?.shutdown()
        // 只有当真正结束巡检（不是返回重新检测）时才释放 InspectionSession
        if (isFinishing && !isChangingConfigurations) {
            InspectionSession.release()
        }
        // 关闭当前 SSE 连接
        currentEventSource?.cancel()
        currentEventSource = null
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
            if (isOnlineMode()) {
                if (isMotionStable) {
                    scheduleOnlineFrameCaptureIfNeeded(0L)
                }
            } else {
                scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
            }
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
        if (quickCameraReady && !QuickCameraManager.isGpuCaptureWarm()) {
            quickCameraReady = false
            quickCameraReadyAtElapsedMs = 0L
        }
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
                if (!isActivityResumed || !isWorkflowActive) {
                    quickCameraReady = false
                    quickCameraReadyAtElapsedMs = 0L
                    QuickCameraManager.releaseCamera()
                    return@post
                }
                if (!success) {
                    failWorkflow("相机初始化失败")
                    return@post
                }
                Log.i(
                    TAG,
                    "camera init ready pending=$pendingCaptureRequest onlineMode=${isOnlineMode()} pageState=$pageState"
                )
                if (pendingCaptureRequest) {
                    startSampleCaptureIfNeeded()
                } else if (pageState == PageState.DETECTING) {
                    scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
                }
                refreshInputActions()
            }
        }
    }

    private fun transitionToDetection() {
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
        activeStreamRequestId++
        cancelMayHazardVerification()
        hideStatusAlertOverlay()
        // 重置超时恢复计数器
        consecutiveTimeoutCount = 0
        cameraRestartAttempts = 0
        isCameraRestarting = false
        showPage(PageState.DETECTING)
        applyDefaultDetectionStatus()
        if (isOnlineMode()) {
            pendingCaptureRequest = false
            awaitingFirstOnlineDetect = isMotionStable
            if (isMotionStable) {
                scheduleOnlineFrameCaptureIfNeeded(0L)
            }
        } else {
            pendingCaptureRequest = true
            startSampleCaptureIfNeeded()
            scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
        }
    }

    private fun returnDirectlyToHome() {
        currentEventSource?.cancel()
        currentEventSource = null
        streamCallbackActive = false
        streamingInProgress = false
        pendingStreamStart = false
        activeStreamRequestId++
        cancelMayHazardVerification()
        cancelAllOnlineDetection()
        hideStatusAlertOverlay()
        refreshInputActions()
        InspectionWorkflowSession.clearForNewInspection()
        startActivity(Intent(this, InspectionLoadingActivity::class.java).apply {
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
        activeStreamRequestId++
        cancelMayHazardVerification()
        cancelAllOnlineDetection()
        hideStatusAlertOverlay()
        refreshInputActions()
        InspectionWorkflowSession.recordAnalysis(lastAnalysisText)
        startActivity(Intent(this, InspectionEndReportActivity::class.java))
        finish()
    }

    // ==================== 拍照与推理 ====================

    private fun startSampleCaptureIfNeeded() {
        if (!pendingCaptureRequest || captureInProgress) return
        if (!isActivityResumed || !isWorkflowActive) return
        if (pageState != PageState.DETECTING) return
        if (pendingStreamStart) return

        if (quickCameraReady && !QuickCameraManager.isGpuCaptureWarm()) {
            quickCameraReady = false
            quickCameraReadyAtElapsedMs = 0L
        }

        if (!quickCameraReady) {
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
        val captureRequestStartMs = SystemClock.elapsedRealtime()

        QuickCameraManager.takeGpuFrame { frame ->
            uiHandler.post {
                if (destroyed || !captureInProgress) {
                    frame?.previewBitmap?.takeIf { !it.isRecycled }?.recycle()
                    frame?.hardwareBuffer?.close()
                    return@post
                }

                captureInProgress = false
                uiHandler.removeCallbacks(captureTimeoutRunnable)

                if (consecutiveTimeoutCount > 0) {
                    Log.d(TAG, "拍摄成功，重置超时计数器")
                    consecutiveTimeoutCount = 0
                }

                if (frame == null) {
                    Log.w(
                        TAG,
                        "takeGpuFrame failed elapsed=${SystemClock.elapsedRealtime() - captureRequestStartMs}ms warm=${QuickCameraManager.isGpuCaptureWarm()}",
                    )
                    if (startPendingStreamAnalysis()) {
                        return@post
                    }
                    scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
                    return@post
                }

                Log.i(
                    TAG,
                    "takeGpuFrame submitted width=${frame.width} height=${frame.height} rotation=${frame.rotationDegrees} elapsed=${SystemClock.elapsedRealtime() - captureRequestStartMs}ms warm=${QuickCameraManager.isGpuCaptureWarm()}",
                )
                triggerInference(frame)
            }
        }
    }

    private fun triggerInference(frame: QuickCameraManager.GpuFrame) {
        if (isOnlineMode()) {
            val payload = buildCapturedFramePayload(frame.previewBitmap, System.currentTimeMillis())
            frame.previewBitmap?.takeIf { !it.isRecycled }?.recycle()
            frame.hardwareBuffer.close()
            if (payload == null) {
                Log.w(TAG, "online frame dropped reason=payload_null")
                return
            }
            InspectionWorkflowSession.recordCapture(
                BitmapFactory.decodeByteArray(payload.jpegBytes, 0, payload.jpegBytes.size),
            )
            bufferOnlineFrame(payload)
            tryStartImmediateOnlineDetection("frame_buffered")
            return
        }

        val local = hiddenRiskNcnn ?: run {
            frame.previewBitmap?.takeIf { !it.isRecycled }?.recycle()
            frame.hardwareBuffer.close()
            return
        }
        if (!inferenceRunning.compareAndSet(false, true)) {
            frame.previewBitmap?.takeIf { !it.isRecycled }?.recycle()
            frame.hardwareBuffer.close()
            return
        }

        if (!submitNativeTask {
                val nativeStartElapsedMs = SystemClock.elapsedRealtime()
                val frameBitmap = frame.previewBitmap
                val captureTimestamp = System.currentTimeMillis()
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
                val nativeElapsedMs = SystemClock.elapsedRealtime() - nativeStartElapsedMs
                val inferenceMs = snapshot?.inferenceTimeMs ?: -1L
                val detectionCount = snapshot?.detectionCount ?: 0
                val payload = if (success && detectionCount > 0) {
                    buildCapturedFramePayload(frameBitmap, captureTimestamp)
                } else {
                    null
                }
                frameBitmap?.takeIf { !it.isRecycled }?.recycle()
                uiHandler.post {
                    inferenceRunning.set(false)
                    Log.d(
                        TAG,
                        "inference success=$success detectionCount=$detectionCount nativeElapsedMs=$nativeElapsedMs inferenceMs=$inferenceMs"
                    )
                    if (destroyed) return@post
                    if (!success) {
                        latestHazardPayload = null
                        if (startPendingStreamAnalysis()) {
                            return@post
                        }
                        scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
                    } else {
                        if (detectionCount == 0) {
                            latestHazardPayload = null
                            InspectionWorkflowSession.updateSummary { summary ->
                                summary.copy(analyzedCount = summary.analyzedCount + 1)
                            }
                            if (startPendingStreamAnalysis()) {
                                return@post
                            }
                            // 未检测到目标：跳过，不改变任何状态，让现有倒计时继续
                            scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
                        } else {
                            latestHazardPayload = payload
                            payload?.let {
                                InspectionWorkflowSession.recordCapture(
                                    BitmapFactory.decodeByteArray(it.jpegBytes, 0, it.jpegBytes.size),
                                )
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

                            if (startPendingStreamAnalysis(latestHazardPayload)) {
                                return@post
                            }

                            if (isOnlineMode()) {
                                startMayHazardVerification(capturedFrame = latestHazardPayload)
                            } else {
                                val judgeResult = evaluateHazardWithJudgment(snapshot)
                                val resultModel = when (judgeResult) {
                                    is HazardJudgeResult.MayHazard -> StatusAlertModel(
                                        status = AlertStatus.WARNING,
                                        titleText = "",
                                        messageText = "检测到隐患：检测到疑似风险目标，请进一步确认现场状态",
                                        action = AlertActionConfig(visible = false),
                                        behavior = AlertBehavior(autoDismissMs = 3000L, showCountdownBar = false),
                                        style = AlertStyle(iconResId = R.drawable.ic_warning_triangle),
                                    )
                                    else -> buildStatusAlertModel(judgeResult).copy(
                                        action = AlertActionConfig(visible = false),
                                        behavior = AlertBehavior(autoDismissMs = 3000L, showCountdownBar = false),
                                    )
                                }
                                InspectionWorkflowSession.recordDetection(resultModel.titleText, resultModel.messageText)
                                InspectionWorkflowSession.updateSummary { summary ->
                                    when (judgeResult) {
                                        is HazardJudgeResult.NoHazard -> summary.copy(
                                            analyzedCount = summary.analyzedCount + 1,
                                            noHazardCount = summary.noHazardCount + 1,
                                        )
                                        is HazardJudgeResult.MayHazard -> summary.copy(
                                            analyzedCount = summary.analyzedCount + 1,
                                            mayHazardCount = summary.mayHazardCount + 1,
                                        )
                                        is HazardJudgeResult.HasHazard -> summary.copy(
                                            analyzedCount = summary.analyzedCount + 1,
                                            hasHazardCount = summary.hasHazardCount + 1,
                                        )
                                    }
                                }
                                updateHazardBanner()
                                statusAlertOverlay.render(resultModel)
                                scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
                            }
                        }
                    }
                }
            }) {
            frame.previewBitmap?.takeIf { !it.isRecycled }?.recycle()
            frame.hardwareBuffer.close()
            inferenceRunning.set(false)
            if (startPendingStreamAnalysis()) {
                return
            }
            scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
        }
    }

    // ==================== 隐患处理流程 ====================

    private fun buildStatusAlertModel(judgeResult: HazardJudgeResult): StatusAlertModel {
        val action = AlertActionConfig(
            visible = true,
            text = "点击开始深度分析",
        )
        val behavior = AlertBehavior(
            autoDismissMs = 2000L,
            showCountdownBar = false,
        )

        return when (judgeResult) {
            is HazardJudgeResult.NoHazard -> {
                val groupNames = judgeResult.completedGroups
                    .map { getGroupDisplayName(it) }
                    .distinct()
                    .joinToString("、")
                val message = if (groupNames.isNotEmpty()) "区域安全：检测到符合${groupNames}" else "区域安全：未发现安全隐患"
                StatusAlertModel(
                    status = AlertStatus.SUCCESS,
                    titleText = "",
                    messageText = message,
                    action = action,
                    behavior = behavior,
                    style = AlertStyle(iconResId = R.drawable.ic_check_circle),
                )
            }

            is HazardJudgeResult.HasHazard -> StatusAlertModel(
                status = AlertStatus.WARNING,
                titleText = "",
                messageText = "检测到疑似隐患：${buildHazardDescription(judgeResult.presentLabels, judgeResult.missingLabels)}",
                action = action,
                behavior = behavior,
                style = AlertStyle(iconResId = R.drawable.ic_question_circle),
            )
            is HazardJudgeResult.MayHazard -> error("MayHazard 不应走本地结果弹层")
        }
    }

    /**
     * 只要当前帧检测到目标，就调用远程接口做最终隐患识别。
     */
    private fun startMayHazardVerification(capturedFrame: CapturedFramePayload?) {
        if (capturedFrame == null) {
            Log.w(TAG, "MayHazard 无可用帧，跳过深度识别")
            resumeDetectingAfterMayHazardFailure("无可用图像")
            return
        }
        cancelMayHazardVerification()
        pauseAutoCaptureForStreaming()
        mayHazardVerificationInProgress = true
        activeMayHazardRequestId += 1
        val requestId = activeMayHazardRequestId
        val verifyStartElapsedMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "mayHazard verify start requestId=$requestId jpegBytes=${capturedFrame.jpegBytes.size}")

        try {
            imageEncodeExecutor.execute {
                val encodeStartMs = SystemClock.elapsedRealtime()
                val base64Image = runCatching {
                    Base64.encodeToString(capturedFrame.jpegBytes, Base64.NO_WRAP)
                }.getOrElse { error ->
                    Log.e(TAG, "MayHazard Base64 编码失败", error)
                    uiHandler.post {
                        if (!shouldHandleMayHazardResult(requestId)) return@post
                        resumeDetectingAfterMayHazardFailure("图像编码失败")
                    }
                    return@execute
                }

                val encodeMs = SystemClock.elapsedRealtime() - encodeStartMs
                Log.i(TAG, "mayHazard encode done requestId=$requestId encodeMs=$encodeMs base64Length=${base64Image.length}")

                val handle = runCatching {
                    mayHazardVerifyService.verify(base64Image, object : MayHazardDeepVerifyService.VerifyCallback {
                        override fun onSuccess(hasHazard: Boolean, metrics: MayHazardDeepVerifyService.VerifyMetrics) {
                            if (!shouldHandleMayHazardResult(requestId)) return
                            val endToEndMs = SystemClock.elapsedRealtime() - verifyStartElapsedMs
                            Log.i(
                                TAG,
                                "mayHazard verify success requestId=$requestId hasHazard=$hasHazard encodeMs=$encodeMs answerMs=${metrics.answerMs} httpTotalMs=${metrics.httpTotalMs} endToEndMs=$endToEndMs"
                            )
                            mayHazardVerificationInProgress = false
                            mayHazardRequestHandle = null
                            statusAlertOverlay.render(buildMayHazardResultModel(hasHazard))
                            pendingCaptureRequest = true
                            startSampleCaptureIfNeeded()
                            scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
                        }

                        override fun onFailure(message: String, metrics: MayHazardDeepVerifyService.VerifyMetrics) {
                            if (!shouldHandleMayHazardResult(requestId)) return
                            val endToEndMs = SystemClock.elapsedRealtime() - verifyStartElapsedMs
                            Log.w(
                                TAG,
                                "mayHazard verify failed requestId=$requestId message=$message encodeMs=$encodeMs answerMs=${metrics.answerMs} httpTotalMs=${metrics.httpTotalMs} endToEndMs=$endToEndMs"
                            )
                            resumeDetectingAfterMayHazardFailure(message)
                        }
                    })
                }.getOrElse { error ->
                    Log.e(TAG, "MayHazard 深度识别请求启动失败", error)
                    uiHandler.post {
                        if (!shouldHandleMayHazardResult(requestId)) return@post
                        resumeDetectingAfterMayHazardFailure(error.message ?: "深度识别启动失败")
                    }
                    return@execute
                }

                uiHandler.post {
                    if (!shouldHandleMayHazardResult(requestId)) {
                        handle.cancel()
                        return@post
                    }
                    mayHazardRequestHandle = handle
                }
            }
        } catch (error: RejectedExecutionException) {
            Log.w(TAG, "MayHazard image encode task rejected", error)
            resumeDetectingAfterMayHazardFailure("图像编码任务提交失败")
        }
    }

    private fun shouldHandleMayHazardResult(requestId: Long): Boolean {
        return !destroyed &&
            isActivityResumed &&
            isWorkflowActive &&
            pageState == PageState.DETECTING &&
            mayHazardVerificationInProgress &&
            requestId == activeMayHazardRequestId
    }

    private fun cancelMayHazardVerification() {
        mayHazardRequestHandle?.cancel()
        mayHazardRequestHandle = null
        mayHazardVerificationInProgress = false
        activeMayHazardRequestId += 1
    }

    private fun hideStatusAlertOverlay() {
        statusAlertOverlay.reset()
    }

    private fun updateHazardBanner() {
        val summary = InspectionWorkflowSession.summary
        val countText = "已处理隐患${summary.hasHazardCount + summary.mayHazardCount}/10"
        tvHazardBannerCount.text = countText
        layoutHazardBanner.visibility = View.VISIBLE
    }

    private fun hideHazardBanner() {
        layoutHazardBanner.visibility = View.GONE
    }

    private fun resumeDetectingAfterMayHazardFailure(message: String) {
        Log.w(TAG, "MayHazard 深度识别失败: $message")
        mayHazardRequestHandle = null
        mayHazardVerificationInProgress = false
        pendingCaptureRequest = true
        startSampleCaptureIfNeeded()
        scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
    }

    private fun buildMayHazardResultModel(hasHazard: Boolean): StatusAlertModel {
        return if (hasHazard) {
            StatusAlertModel(
                status = AlertStatus.WARNING,
                titleText = "",
                messageText = "已确认存在安全隐患",
                action = AlertActionConfig(visible = false),
                behavior = AlertBehavior(autoDismissMs = 2000L, showCountdownBar = false),
                style = AlertStyle(iconResId = R.drawable.ic_warning_triangle),
            )
        } else {
            StatusAlertModel(
                status = AlertStatus.SUCCESS,
                titleText = "",
                messageText = "未发现安全隐患",
                action = AlertActionConfig(visible = false),
                behavior = AlertBehavior(autoDismissMs = 2000L, showCountdownBar = false),
                style = AlertStyle(iconResId = R.drawable.ic_check_circle),
            )
        }
    }

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

    private fun showSyncSuccess() {
        showPage(PageState.SYNC_SUCCESS)
    }

    private fun isOnlineMode(): Boolean {
        return InspectionWorkflowSession.workflowMode == WorkflowMode.ONLINE
    }

    private fun cancelAllOnlineDetection() {
        onlineDetectHandle?.timeoutRunnable?.let(uiHandler::removeCallbacks)
        onlineDetectHandle?.cancel()
        onlineDetectHandle = null
        stopOnlinePipeline("cancelAllOnlineDetection", clearBufferedFrames = true)
    }

    private fun applyDefaultDetectionStatus() {
        if (isOnlineMode()) {
            tvDetectionStatus.text = ""
        } else {
            tvDetectionStatus.setText(R.string.ai_detection_offline_running)
        }
    }

    private fun shouldRunOnlineFrameCaptureLoop(): Boolean {
        if (!isOnlineMode() || !isMotionStable || stableQualifiedAtMillis == null) return false
        if (destroyed || !isActivityResumed || !isWorkflowActive) return false
        if (!hasRequiredPermissions()) return false
        if (RokidSdkManager.state != RokidSdkManager.SdkState.READY) return false
        if (!modelLoaded || modelLoading) return false
        if (pendingStreamStart || streamingInProgress || streamCallbackActive) return false
        if (mayHazardVerificationInProgress) return false
        if (pageState != PageState.DETECTING) return false
        return true
    }

    private fun canStartOnlineFrameCaptureNow(): Boolean {
        if (!shouldRunOnlineFrameCaptureLoop()) return false
        if (captureInProgress || captureDelayScheduled || quickCameraInitializing || pendingCaptureRequest) return false
        if (inferenceRunning.get()) return false
        return true
    }

    private fun shouldRunOnlineDetectLoop(): Boolean {
        if (!shouldRunOnlineFrameCaptureLoop()) return false
        return !awaitingFirstOnlineDetect
    }

    private fun scheduleOnlineFrameCaptureIfNeeded(delayMs: Long) {
        if (!shouldRunOnlineFrameCaptureLoop() || onlineFrameCaptureScheduled) return
        onlineFrameCaptureScheduled = true
        val actualDelay = delayMs.coerceAtLeast(0L)
        Log.i(TAG, "schedule online frame capture delayMs=$actualDelay")
        uiHandler.postDelayed(onlineFrameCaptureRunnable, actualDelay)
    }

    private fun scheduleOnlineDetectIfNeeded(delayMs: Long) {
        if (!shouldRunOnlineFrameCaptureLoop() || onlineDetectScheduled) return
        onlineDetectScheduled = true
        val actualDelay = delayMs.coerceAtLeast(0L)
        Log.i(TAG, "schedule online detect delayMs=$actualDelay")
        uiHandler.postDelayed(onlineDetectRunnable, actualDelay)
    }

    private fun stopOnlinePipeline(reason: String, clearBufferedFrames: Boolean) {
        onlineFrameCaptureScheduled = false
        onlineDetectScheduled = false
        awaitingFirstOnlineDetect = false
        pendingCaptureRequest = false
        uiHandler.removeCallbacks(onlineFrameCaptureRunnable)
        uiHandler.removeCallbacks(onlineDetectRunnable)
        if (clearBufferedFrames) {
            clearOnlineFrameBuffer(reason)
        }
        if (isOnlineMode()) {
            Log.i(TAG, "stop online pipeline reason=$reason")
        }
    }

    private fun clearOnlineFrameBuffer(reason: String) {
        val clearedCount = onlineFrameBuffer.size
        if (clearedCount > 0) {
            Log.i(TAG, "online frame buffer cleared reason=$reason count=$clearedCount")
        }
        onlineFrameBuffer.clear()
    }

    private fun bufferOnlineFrame(frame: CapturedFramePayload) {
        while (onlineFrameBuffer.size >= ONLINE_FRAME_BUFFER_CAPACITY) {
            onlineFrameBuffer.removeFirst()
        }
        onlineFrameBuffer.addLast(frame)
        Log.i(TAG, "online frame buffered size=${onlineFrameBuffer.size} timestamp=${frame.timestamp}")
    }

    private fun consumeLatestOnlineFrameOrNull(reason: String): CapturedFramePayload? {
        if (onlineFrameBuffer.isEmpty()) {
            return null
        }
        val latestFrame = onlineFrameBuffer.last()
        val clearedCount = onlineFrameBuffer.size
        onlineFrameBuffer.clear()
        Log.i(TAG, "online buffer consumed reason=$reason latestFrameTs=${latestFrame.timestamp} clearedCount=$clearedCount")
        return latestFrame
    }

    private fun tryStartImmediateOnlineDetection(reason: String) {
        if (!awaitingFirstOnlineDetect || onlineDetectHandle != null) {
            return
        }
        if (!shouldRunOnlineFrameCaptureLoop()) {
            return
        }
        val frame = consumeLatestOnlineFrameOrNull(reason) ?: return
        Log.i(TAG, "online first frame triggers send reason=$reason")
        awaitingFirstOnlineDetect = false
        startOnlineDetection(frame, reason)
    }

    private fun startOnlineDetection(frame: CapturedFramePayload, reason: String) {
        if (!isOnlineMode()) return
        if (onlineDetectHandle != null) {
            Log.i(TAG, "skip startOnlineDetection reason=in_flight requestId=${onlineDetectHandle?.requestId}")
            return
        }
        val requestId = ++nextOnlineDetectRequestId
        Log.i(
            TAG,
            "startOnlineDetection requestId=$requestId jpegBytes=${frame.jpegBytes.size} reason=$reason"
        )
        val handle = onlineHazardDetectionService.detect(
            requestId = requestId,
            jpegBytes = frame.jpegBytes,
            snCode = RokidSdkManager.getSerialNumber(),
            timeoutMs = ONLINE_DETECTION_FIRST_PACKET_TIMEOUT_MS,
            callback = object : OnlineHazardDetectionService.Callback {
                override fun onFirstPacket(
                    handle: OnlineHazardDetectionService.DetectionHandle,
                    hasHazard: Boolean,
                    message: String,
                ) {
                    if (onlineDetectHandle?.requestId == handle.requestId) {
                        onlineDetectHandle = null
                    }
                    if (destroyed || pageState != PageState.DETECTING) {
                        return
                    }
                    InspectionWorkflowSession.updateSummary { summary ->
                        if (hasHazard) {
                            summary.copy(
                                analyzedCount = summary.analyzedCount + 1,
                                hasHazardCount = summary.hasHazardCount + 1,
                            )
                        } else {
                            summary.copy(
                                analyzedCount = summary.analyzedCount + 1,
                                noHazardCount = summary.noHazardCount + 1,
                            )
                        }
                    }
                    val resultModel = buildMayHazardResultModel(hasHazard).copy(
                        behavior = AlertBehavior(autoDismissMs = 3000L, showCountdownBar = false),
                    )
                    InspectionWorkflowSession.recordDetection(resultModel.titleText, resultModel.messageText)
                    if (hasHazard) {
                        updateHazardBanner()
                    }
                    statusAlertOverlay.render(resultModel)
                    if (awaitingFirstOnlineDetect) {
                        tryStartImmediateOnlineDetection("first_packet_complete")
                    }
                }

                override fun onTimeout(handle: OnlineHazardDetectionService.DetectionHandle) {
                    if (onlineDetectHandle?.requestId == handle.requestId) {
                        onlineDetectHandle = null
                    }
                    if (!destroyed && awaitingFirstOnlineDetect) {
                        tryStartImmediateOnlineDetection("timeout_complete")
                    }
                }

                override fun onFailure(
                    handle: OnlineHazardDetectionService.DetectionHandle,
                    message: String,
                ) {
                    if (onlineDetectHandle?.requestId == handle.requestId) {
                        onlineDetectHandle = null
                    }
                    if (!destroyed && awaitingFirstOnlineDetect) {
                        tryStartImmediateOnlineDetection("failure_complete")
                    }
                }

                override fun onClosed(handle: OnlineHazardDetectionService.DetectionHandle) = Unit
            },
        )
        onlineDetectHandle = handle
        scheduleOnlineDetectIfNeeded(ONLINE_DETECT_INTERVAL_MS)
    }

    // ==================== 自动拍摄调度 ====================

    private fun shouldAutoCaptureNow(): Boolean {
        if (destroyed || !isActivityResumed || !isWorkflowActive) return false
        if (!hasRequiredPermissions()) return false
        if (RokidSdkManager.state != RokidSdkManager.SdkState.READY) return false
        if (!modelLoaded || modelLoading) return false
        if (pendingStreamStart || streamingInProgress || streamCallbackActive) return false
        if (mayHazardVerificationInProgress) return false
        if (captureInProgress || captureDelayScheduled || quickCameraInitializing || pendingCaptureRequest) return false
        if (inferenceRunning.get()) return false
        if (pageState != PageState.DETECTING) return false
        if (isOnlineMode()) return false
        return true
    }

    private fun scheduleAutoCaptureIfNeeded(delayMs: Long) {
        if (autoCaptureScheduled || destroyed || !isActivityResumed || !isWorkflowActive) return
        if (pendingStreamStart || streamingInProgress || streamCallbackActive) return
        if (mayHazardVerificationInProgress) return
        if (pageState != PageState.DETECTING) return
        if (isOnlineMode()) return
        autoCaptureScheduled = true
        // 连续推理模式下，延迟设为0，推理空闲立即取下一帧
        val actualDelay = if (continuousInferenceMode) 0L else delayMs
        uiHandler.postDelayed(autoCaptureRunnable, actualDelay)
    }

    private fun logOnlineWorkflowCheckpoint(reason: String) {
        if (!isOnlineMode()) return
        val permissionReady = runCatching { hasRequiredPermissions() }.getOrDefault(false)
        Log.i(
            TAG,
            "online checkpoint=$reason resumed=$isActivityResumed active=$isWorkflowActive pageState=$pageState permissions=$permissionReady sdk=${RokidSdkManager.state} stable=$isMotionStable stableSince=$stableQualifiedAtMillis pending=$pendingCaptureRequest capture=$captureInProgress captureDelay=$captureDelayScheduled quickInit=$quickCameraInitializing quickReady=$quickCameraReady infer=${inferenceRunning.get()} autoScheduled=$autoCaptureScheduled frameCaptureScheduled=$onlineFrameCaptureScheduled detectScheduled=$onlineDetectScheduled awaitingFirst=$awaitingFirstOnlineDetect streamPending=$pendingStreamStart streaming=$streamingInProgress callbackActive=$streamCallbackActive mayHazard=$mayHazardVerificationInProgress inFlight=${onlineDetectHandle?.requestId ?: -1} bufferSize=${onlineFrameBuffer.size}",
        )
    }

    // ==================== UI 页面切换 ====================

    private fun showPage(state: PageState) {
        pageState = state
        layoutLoading.visibility = View.GONE
        layoutDetection.visibility = if (state == PageState.DETECTING) View.VISIBLE else View.GONE
        layoutStreamResponse.visibility =
            if (state == PageState.STREAM_RESPONSE) View.VISIBLE else View.GONE
        layoutSyncSuccess.visibility =
            if (state == PageState.SYNC_SUCCESS) View.VISIBLE else View.GONE
        bottomPromptSuccess.visibility =
            if (state == PageState.SYNC_SUCCESS) View.VISIBLE else View.GONE
        if (state != PageState.DETECTING) {
            hideStatusAlertOverlay()
            hideHazardBanner()
        }
        if (state != PageState.STREAM_RESPONSE) {
            ivStreamThumbnail.setImageBitmap(null)
            ivStreamThumbnail.visibility = View.GONE
            currentStreamThumbnail?.takeIf { !it.isRecycled }?.recycle()
            currentStreamThumbnail = null
        }
        // DETECTING 状态时显示隐患 banner
        if (state == PageState.DETECTING) {
            val summary = InspectionWorkflowSession.summary
            if (summary.hasHazardCount + summary.mayHazardCount > 0) {
                updateHazardBanner()
            }
        }
        if (state == PageState.SYNC_SUCCESS) {
            // 重置透明度（上次渐隐后可能为0）
            layoutSyncSuccess.alpha = 1f
            bottomPromptSuccess.alpha = 1f
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
                    ?: getString(R.string.ai_detection_online_running)
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
                    UnifiedInputSession.InputTrigger.Voice("分析", "fen xi"),
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
                enabled = { pageState == PageState.STREAM_RESPONSE && !streamingInProgress },
            ) {
                syncToPhone()
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
                enabled = { pageState == PageState.STREAM_RESPONSE },
            ) {
                returnToDetecting()
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
        bottomPromptSuccess.setPrompt(
            title = getString(R.string.ai_inspection_continue_prompt)
        )
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

    // ==================== SSE 流式分析相关方法 ====================

    /**
     * 拍照并通过 SSE 接口发送数据
     */
    private fun captureAndSendToSSE() {
        beginStreamingRequest()
        val requestId = activeStreamRequestId
        QuickCameraManager.takeGpuFrame { frame ->
            uiHandler.post {
                if (!shouldDeliverStreamRequest(requestId)) {
                    frame?.previewBitmap?.takeIf { !it.isRecycled }?.recycle()
                    frame?.hardwareBuffer?.close()
                    return@post
                }
                if (frame == null) {
                    Log.e(TAG, "拍照失败：无可用 GPU 帧")
                    handleSSEError("拍照失败")
                    return@post
                }
                val previewBitmap = frame.previewBitmap
                frame.hardwareBuffer.close()
                try {
                    imageEncodeExecutor.execute {
                        // 先创建缩略图（复用原始 bitmap，避免二次解码）
                        previewBitmap?.takeIf { !it.isRecycled }?.let { setStreamThumbnail(it) }

                        val payload = buildCapturedFramePayload(previewBitmap, System.currentTimeMillis())
                        previewBitmap?.takeIf { !it.isRecycled }?.recycle()
                        if (payload == null) {
                            Log.e(TAG, "当前 GPU 帧编码失败")
                            handleSSEError("图像编码失败")
                            return@execute
                        }
                        encodePayloadToBase64AndSend(requestId, payload)
                    }
                } catch (error: RejectedExecutionException) {
                    previewBitmap?.takeIf { !it.isRecycled }?.recycle()
                    Log.w(TAG, "image encode task rejected", error)
                    handleSSEError("图像编码任务提交失败")
                }
            }
        }
    }

    private fun sendPayloadToSSE(frame: CapturedFramePayload) {
        val requestId = activeStreamRequestId
        try {
            imageEncodeExecutor.execute {
                // 从已有 JPEG 创建缩略图
                setStreamThumbnail(frame.jpegBytes)
                encodePayloadToBase64AndSend(requestId, frame)
            }
        } catch (error: RejectedExecutionException) {
            Log.w(TAG, "image encode task rejected", error)
            handleSSEError("图像编码任务提交失败")
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
        if (mayHazardVerificationInProgress) {
            Log.i(TAG, "stream request interrupts mayHazard verification")
            cancelMayHazardVerification()
        }
        if (!isMotionStable) {
            pendingStreamStart = true
            tvDetectionStatus.setText(R.string.ai_detection_deep_analysis_wait)
            return
        }
        pendingStreamStart = true
        tvDetectionStatus.setText(R.string.ai_detection_deep_analysis_running)
        pauseAutoCaptureForStreaming()
        if (captureInProgress || inferenceRunning.get()) {
            Log.i(
                TAG,
                "stream request queued captureInProgress=$captureInProgress inferenceRunning=${inferenceRunning.get()}",
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
    private fun startPendingStreamAnalysis(preferredFrame: CapturedFramePayload? = null): Boolean {
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

        val frame = preferredFrame ?: if (isOnlineMode()) {
            consumeLatestOnlineFrameOrNull("start_pending_stream")
        } else {
            latestHazardPayload
        }
        pendingStreamStart = false
        if (frame != null) {
            latestHazardPayload = null
            Log.i(TAG, "start pending stream with latest detection frame")
            beginStreamingRequest()
            sendPayloadToSSE(frame)
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
        stopOnlinePipeline("pause_for_streaming", clearBufferedFrames = true)
    }

    private fun buildCapturedFramePayload(bitmap: Bitmap?, timestamp: Long): CapturedFramePayload? {
        if (bitmap == null || bitmap.isRecycled) {
            return null
        }
        val cropped = BitmapUtils.cropCenterTo640(bitmap) ?: return null
        val jpegBytes = ByteArrayOutputStream().use { outputStream ->
            if (cropped.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)) {
                outputStream.toByteArray()
            } else {
                null
            }
        } ?: run {
            if (cropped !== bitmap && !cropped.isRecycled) {
                cropped.recycle()
            }
            return null
        }
        if (cropped !== bitmap && !cropped.isRecycled) {
            cropped.recycle()
        }
        return CapturedFramePayload(
            jpegBytes = jpegBytes,
            width = 640,
            height = 640,
            timestamp = timestamp,
        )
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

    private fun setStreamThumbnail(source: Bitmap) {
        val thumbnail = ThumbnailUtils.extractThumbnail(source, 120, 120)
        uiHandler.post {
            ivStreamThumbnail.setImageBitmap(thumbnail)
            ivStreamThumbnail.visibility = View.VISIBLE
            currentStreamThumbnail?.takeIf { !it.isRecycled }?.recycle()
            currentStreamThumbnail = thumbnail
        }
    }

    private fun setStreamThumbnail(jpegBytes: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return
        setStreamThumbnail(bitmap)
        bitmap.takeIf { !it.isRecycled }?.recycle()
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
        ivStreamThumbnail.setImageBitmap(bitmap)
        ivStreamThumbnail.visibility = View.VISIBLE
        currentStreamThumbnail?.takeIf { !it.isRecycled }?.recycle()
        currentStreamThumbnail = bitmap
    }

    private fun beginStreamingRequest() {
        currentEventSource?.cancel()
        currentEventSource = null
        activeStreamRequestId += 1
        showPage(PageState.STREAM_RESPONSE)
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
        // 每次拍照生成新 sessionId，格式：时间戳_snCode
        val snCode = RokidSdkManager.getSerialNumber()
        sessionId = "${System.currentTimeMillis()}_${snCode}"

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
                        adjustStreamScrollHeight()
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
                        bottomPromptSync.visibility = View.VISIBLE
                        refreshInputActions()
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
            currentEventSource?.cancel()
            currentEventSource = null
            streamCallbackActive = false
            streamingInProgress = false
            pendingStreamStart = false
            activeStreamRequestId += 1
            tvStreamContent.text = "分析失败：$errorMsg"
            bottomPromptSync.visibility = View.VISIBLE
            refreshInputActions()
        }
    }
}
