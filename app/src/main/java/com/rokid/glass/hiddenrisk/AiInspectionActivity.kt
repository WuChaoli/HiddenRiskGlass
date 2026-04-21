package com.rokid.glass.hiddenrisk

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import android.view.animation.AlphaAnimation
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
import com.rokid.glass.camera.QuickCameraManager
import com.rokid.glass.component.AlertActionConfig
import com.rokid.glass.component.AlertBehavior
import com.rokid.glass.component.AlertStatus
import com.rokid.glass.component.AlertStyle
import com.rokid.glass.component.StatusAlertModel
import com.rokid.glass.component.StatusAlertOverlayView
import com.rokid.glass.utils.BitmapUtils
import com.rokid.glass.utils.SSEUtil
import com.rokid.glesse.R
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.sdk.base.data.offlineCmd.bean.VoiceAction
import com.rokid.security.glass3.sdk.base.data.offlineCmd.listener.IVoiceCallback
import okhttp3.Response
import okhttp3.sse.EventSource
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
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
        private const val VOICE_REGISTER_RETRY_MS = 500L
        private const val CAPTURE_TIMEOUT_MS = 1000L  // 从15000改为1000，快速超时并自动重试
        private const val MAX_CONSECUTIVE_TIMEOUTS = 3
        private const val MAX_CAMERA_RESTART_ATTEMPTS = 3
        private const val CAPTURE_WARMUP_MS = 1200L
        private const val AUTO_CAPTURE_INTERVAL_MS = 1000L

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
    private lateinit var viewStatusDot: View
    private lateinit var viewCrosshairHorizontal: View
    private lateinit var viewCrosshairVertical: View
    private lateinit var ivMayHazardLoading: ImageView
    private lateinit var tvStreamContent: TextView
    private lateinit var scrollContent: ScrollView
    private lateinit var tvSyncPrompt: TextView
    private lateinit var layoutSyncSuccess: LinearLayout
    private lateinit var tvSyncSuccessHint: TextView
    // 检测状态UI
    private lateinit var tvCurrentTime: TextView      // 顶部实时时间
    private lateinit var tvBatteryLevel: TextView     // 右下角电量

    private val uiHandler = Handler(Looper.getMainLooper())
    private val nativeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val imageEncodeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val inferenceRunning = AtomicBoolean(false)
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

    // SSE 相关
    private var currentEventSource: EventSource? = null
    private var sseUtil: SSEUtil = SSEUtil()

    // 时间和电量更新
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val timeUpdateRunnable = object : Runnable {
        override fun run() {
            updateCurrentTime()
            uiHandler.postDelayed(this, 1000L) // 每秒更新
        }
    }
    private var batteryReceiver: BroadcastReceiver? = null

    // 本次拍照上传的会话 ID，用于与 save 接口保持一致的指纹
    private var sessionId = ""

    // 拍摄超时恢复机制
    private var consecutiveTimeoutCount = 0
    private var cameraRestartAttempts = 0
    private var isCameraRestarting = false

    /** 录制指示灯闪烁动画：1.0 → 0.2 → 1.0 循环，模拟拍摄状态灯 */
    private val dotBlinkAnimation: AlphaAnimation by lazy {
        AlphaAnimation(1.0f, 0.2f).apply {
            duration = 600L
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
            interpolator = LinearInterpolator()
        }
    }

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
        statusAlertOverlay = findViewById(R.id.statusAlertOverlay)
        layoutStreamResponse = findViewById(R.id.layoutStreamResponse)
        viewStatusDot = findViewById(R.id.viewStatusDot)
        viewCrosshairHorizontal = findViewById(R.id.viewCrosshairHorizontal)
        viewCrosshairVertical = findViewById(R.id.viewCrosshairVertical)
        ivMayHazardLoading = findViewById(R.id.ivMayHazardLoading)
        tvStreamContent = findViewById(R.id.tvStreamContent)
        scrollContent = findViewById(R.id.scrollContent)
        tvSyncPrompt = findViewById(R.id.tvSyncPrompt)
        layoutSyncSuccess = findViewById(R.id.layoutSyncSuccess)
        tvSyncSuccessHint = findViewById(R.id.tvSyncSuccessHint)
        // 检测状态 UI 初始化
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvBatteryLevel = findViewById(R.id.tvBatteryLevel)

        showPage(PageState.DETECTING)
        startTimeAndBatteryUpdate()

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
        pendingCaptureRequest = true
        startSampleCaptureIfNeeded()
        scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
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
        hideStatusAlertOverlay()
        cancelMayHazardVerification()
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
        hideStatusAlertOverlay()
        cancelMayHazardVerification()
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
        hideStatusAlertOverlay()
        cancelMayHazardVerification()
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

                    else -> {}
                }
            }

            GlassKeyEvent.KEYCODE_DOUBLE_CLICK -> {
                Log.d(TAG, "双击事件，当前页面状态: $pageState")
                when (pageState) {
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
                syncPageVoiceCommandState()
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
        activeStreamRequestId++
        cancelMayHazardVerification()
        hideStatusAlertOverlay()
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
                            if (startPendingStreamAnalysis()) {
                                return@post
                            }
                            // 未检测到目标：跳过，不改变任何状态，让现有倒计时继续
                            scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
                        } else {
                            latestHazardPayload = payload
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

                            // 只要检测到任意目标，就直接进入远程识别，不再展示本地判定结果。
                            startMayHazardVerification(capturedFrame = latestHazardPayload)
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
            showCountdownBar = true,
        )

        return when (judgeResult) {
            is HazardJudgeResult.NoHazard -> {
                val groupNames = judgeResult.completedGroups
                    .map { getGroupDisplayName(it) }
                    .distinct()
                    .joinToString("、")
                StatusAlertModel(
                    status = AlertStatus.SUCCESS,
                    titleText = "区域安全",
                    messageText = if (groupNames.isNotEmpty()) "检测到符合${groupNames}" else "未发现安全隐患",
                    action = action,
                    behavior = behavior,
                    style = AlertStyle(iconResId = R.drawable.ic_check_circle),
                )
            }

            is HazardJudgeResult.HasHazard -> StatusAlertModel(
                status = AlertStatus.WARNING,
                titleText = "检测到疑似隐患",
                messageText = buildHazardDescription(judgeResult.presentLabels, judgeResult.missingLabels),
                action = action,
                behavior = behavior,
                style = AlertStyle(iconResId = R.drawable.ic_question_circle),
            )
            is HazardJudgeResult.MayHazard -> error("MayHazard 不应走本地结果弹层")
        }
    }

    private fun showMayHazardLoading() {
        viewCrosshairHorizontal.visibility = View.GONE
        viewCrosshairVertical.visibility = View.GONE
        ivMayHazardLoading.visibility = View.VISIBLE
        ivMayHazardLoading.startAnimation(mayHazardLoadingRotateAnimation)
    }

    private fun hideMayHazardLoading() {
        ivMayHazardLoading.clearAnimation()
        ivMayHazardLoading.visibility = View.GONE
        viewCrosshairHorizontal.visibility = View.VISIBLE
        viewCrosshairVertical.visibility = View.VISIBLE
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
        showMayHazardLoading()
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
                            hideMayHazardLoading()
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
        hideMayHazardLoading()
    }

    private fun hideStatusAlertOverlay() {
        statusAlertOverlay.reset()
    }

    private fun resumeDetectingAfterMayHazardFailure(message: String) {
        Log.w(TAG, "MayHazard 深度识别失败: $message")
        mayHazardRequestHandle = null
        mayHazardVerificationInProgress = false
        hideMayHazardLoading()
        pendingCaptureRequest = true
        startSampleCaptureIfNeeded()
        scheduleAutoCaptureIfNeeded(AUTO_CAPTURE_INTERVAL_MS)
    }

    private fun buildMayHazardResultModel(hasHazard: Boolean): StatusAlertModel {
        return if (hasHazard) {
            StatusAlertModel(
                status = AlertStatus.WARNING,
                titleText = "检测到隐患",
                messageText = "已确认存在安全隐患",
                action = AlertActionConfig(visible = false),
                behavior = AlertBehavior(autoDismissMs = 2000L, showCountdownBar = true),
                style = AlertStyle(iconResId = R.drawable.ic_warning_triangle),
            )
        } else {
            StatusAlertModel(
                status = AlertStatus.SUCCESS,
                titleText = "区域安全",
                messageText = "未发现安全隐患",
                action = AlertActionConfig(visible = false),
                behavior = AlertBehavior(autoDismissMs = 2000L, showCountdownBar = true),
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
        return true
    }

    private fun scheduleAutoCaptureIfNeeded(delayMs: Long) {
        if (autoCaptureScheduled || destroyed || !isActivityResumed || !isWorkflowActive) return
        if (pendingStreamStart || streamingInProgress || streamCallbackActive) return
        if (mayHazardVerificationInProgress) return
        if (pageState != PageState.DETECTING) return
        autoCaptureScheduled = true
        // 连续推理模式下，延迟设为0，推理空闲立即取下一帧
        val actualDelay = if (continuousInferenceMode) 0L else delayMs
        uiHandler.postDelayed(autoCaptureRunnable, actualDelay)
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
        tvSyncSuccessHint.visibility =
            if (state == PageState.SYNC_SUCCESS) View.VISIBLE else View.GONE
        if (state != PageState.DETECTING) {
            hideStatusAlertOverlay()
        }
        if (state != PageState.DETECTING || !mayHazardVerificationInProgress) {
            hideMayHazardLoading()
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

    private fun currentPageVoiceActions(): List<VoiceAction> {
        return when (pageState) {
            PageState.DETECTING -> listOf(detectingDeepAnalysisVoiceAction, detectingExitVoiceAction)
            PageState.STREAM_RESPONSE -> listOf(streamConfirmVoiceAction, streamRejectVoiceAction)
            PageState.SYNC_SUCCESS -> listOf(syncContinueVoiceAction, syncExitVoiceAction)
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
        val currentTime = timeFormat.format(Date())
        tvCurrentTime.text = currentTime
    }

    /**
     * 更新电量显示
     */
    private fun updateBatteryLevel(intent: Intent?) {
        intent?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryPct = (level * 100 / scale.toFloat()).toInt()
            tvBatteryLevel.text = "$batteryPct"
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
        pendingStreamStart = true
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

        val frame = preferredFrame ?: latestHazardPayload
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

    private fun beginStreamingRequest() {
        currentEventSource?.cancel()
        currentEventSource = null
        activeStreamRequestId += 1
        showPage(PageState.STREAM_RESPONSE)
        tvStreamContent.text = "正在准备图像..."
        streamingInProgress = true
        streamCallbackActive = true
        tvSyncPrompt.visibility = View.INVISIBLE
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
            currentEventSource?.cancel()
            currentEventSource = null
            streamCallbackActive = false
            streamingInProgress = false
            pendingStreamStart = false
            activeStreamRequestId += 1
            tvStreamContent.text = "分析失败：$errorMsg"
            tvSyncPrompt.visibility = View.VISIBLE
        }
    }
}
