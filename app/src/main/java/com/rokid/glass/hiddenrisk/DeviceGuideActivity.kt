package com.rokid.glass.hiddenrisk

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rokid.glass.AiInspectionMenuActivity
import com.rokid.glass.InspectionEndReportActivity
import com.rokid.glass.InspectionEndReportReturnDestination
import com.rokid.glass.component.AlertBehavior
import com.rokid.glass.component.AlertStatus
import com.rokid.glass.component.AlertStyle
import com.rokid.glass.component.FunctionMenuView
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.component.RokidCameraPreviewView
import com.rokid.glass.component.StatusAlertModel
import com.rokid.glass.component.StatusAlertOverlayView
import com.rokid.glass.config.InspectionConfigRepository
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator.CameraOwner
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glesse.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 设备指引独立页。
 * 当前仅依赖远端 ctype=1 识别物品隐患，再通过 ctype=0 深度分析拉取检查重点文本。
 */
class DeviceGuideActivity : BaseGlassActivity(), RokidSdkManager.Listener {

    private enum class PageState {
        DETECTING,
        PROMPT_PENDING,
        DETAIL,
    }

    private lateinit var layoutDetection: FrameLayout
    private lateinit var layoutResult: FrameLayout
    private lateinit var layoutLivePreviewCard: FrameLayout
    private lateinit var viewLivePreview: RokidCameraPreviewView
    private lateinit var statusAlertOverlay: StatusAlertOverlayView
    private lateinit var tvDetectingBottomHint: TextView
    private lateinit var operationGuideDetecting: FunctionMenuView
    private lateinit var operationGuideResult: FunctionMenuView
    private lateinit var statusBarDetecting: GlassStatusBar
    private lateinit var statusBarResult: GlassStatusBar
    private lateinit var layoutResultThumbnailCard: FrameLayout
    private lateinit var layoutResultThumbnailPlaceholder: FrameLayout
    private lateinit var ivResultThumbnail: ImageView
    private lateinit var scrollContent: ScrollView
    private lateinit var tvResultContent: TextView
    private lateinit var tvResultBottomHint: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private val imageExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val detectSseService by lazy {
        AiArSseService(apiConfig = InspectionConfigRepository.get().network.deviceGuideDetectApi)
    }
    private val detailSseService by lazy { AiArSseService() }
    private val frameCaptureService by lazy {
        InspectionFrameCaptureService(
            staleFrameThresholdMs = STALE_FRAME_THRESHOLD_MS,
            selectWindowMs = SELECT_WINDOW_MS,
            selectMaxFrames = SELECT_MAX_FRAMES,
            selectPollIntervalMs = SELECT_POLL_INTERVAL_MS,
            jpegQuality = JPEG_QUALITY,
            logger = { stage, extra -> Log.i(TAG, "$stage $extra") },
        )
    }

    private var pageState = PageState.DETECTING
    private var isActivityResumed = false
    private var frameStreamReady = false
    private var frameStreamInitializing = false
    private var mediaPermissionRequested = false
    private var currentPayload: InspectionFrameCaptureService.CapturedFramePayload? = null
    private var detectInFlight = false
    private var detailInFlight = false
    private var previewRestartAttempted = false
    private var activeDetectHandle: AiArSseService.RequestHandle? = null
    private var activeDetailHandle: AiArSseService.RequestHandle? = null
    private var batteryReceiver: BroadcastReceiver? = null

    private val statusUpdateRunnable = object : Runnable {
        override fun run() {
            updateStatusBars()
            uiHandler.postDelayed(this, STATUS_UPDATE_DELAY_MS)
        }
    }

    private val nextDetectRunnable = Runnable {
        runDetectionLoop()
    }

    private val promptTimeoutRunnable = Runnable {
        if (pageState == PageState.PROMPT_PENDING) {
            returnToDetecting()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_guide)
        initViews()
        RokidSdkManager.initialize(application)
        RokidSdkManager.addListener(this)
        RokidSdkManager.ensureInitialized()
        showDetectingPage()
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        ensureFrameStreamReady()
        inputSession.attach()
        refreshInputActions()
        startStatusBarUpdates()
        scheduleNextDetection(immediate = true)
    }

    override fun onPause() {
        isActivityResumed = false
        stopStatusBarUpdates()
        cancelActiveRequests()
        uiHandler.removeCallbacks(nextDetectRunnable)
        uiHandler.removeCallbacks(promptTimeoutRunnable)
        frameStreamInitializing = false
        frameStreamReady = false
        InspectionCameraCoordinator.release(CameraOwner.DEVICE_GUIDE, reason = "device_guide_on_pause")
        inputSession.detach()
        super.onPause()
    }

    override fun onDestroy() {
        cancelActiveRequests()
        uiHandler.removeCallbacksAndMessages(null)
        inputSession.release()
        RokidSdkManager.removeListener(this)
        InspectionCameraCoordinator.release(CameraOwner.DEVICE_GUIDE, reason = "device_guide_on_destroy")
        imageExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        return inputSession.dispatchTouch(keyEvent) || super.onGlassKeyEvent(keyEvent)
    }

    override fun onSdkStateChanged(state: RokidSdkManager.SdkState) {
        if (state == RokidSdkManager.SdkState.READY) {
            ensureFrameStreamReady()
            scheduleNextDetection(immediate = true)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MEDIA_PERMISSION) return
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            ensureFrameStreamReady()
            scheduleNextDetection(immediate = true)
        } else {
            tvDetectingBottomHint.setText(R.string.ai_inspection_loading_missing_camera_permission)
            refreshInputActions()
        }
    }

    private fun initViews() {
        layoutDetection = findViewById(R.id.layoutDetection)
        layoutResult = findViewById(R.id.layoutResult)
        layoutLivePreviewCard = findViewById(R.id.layoutLivePreviewCard)
        viewLivePreview = findViewById(R.id.viewLivePreview)
        statusAlertOverlay = findViewById(R.id.statusAlertOverlay)
        tvDetectingBottomHint = findViewById(R.id.tvDetectingBottomHint)
        operationGuideDetecting = findViewById(R.id.operationGuideDetecting)
        operationGuideResult = findViewById(R.id.operationGuideResult)
        statusBarDetecting = findViewById(R.id.statusBarDetecting)
        statusBarResult = findViewById(R.id.statusBarResult)
        layoutResultThumbnailCard = findViewById(R.id.layoutResultThumbnailCard)
        layoutResultThumbnailPlaceholder = findViewById(R.id.layoutResultThumbnailPlaceholder)
        ivResultThumbnail = findViewById(R.id.ivResultThumbnail)
        scrollContent = findViewById(R.id.scrollContent)
        tvResultContent = findViewById(R.id.tvResultContent)
        tvResultBottomHint = findViewById(R.id.tvResultBottomHint)

        val menuContent = getString(R.string.device_guide_function_menu_content)
        operationGuideDetecting.setMenu(content = menuContent)
        operationGuideResult.setMenu(content = menuContent)
        updateStatusBars()
    }

    private fun buildInputActions(): List<UnifiedInputSession.InputActionSpec> {
        return listOf(
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("device_guide_realtime_analysis"),
                label = "实时分析",
                triggers = listOf(UnifiedInputSession.InputTrigger.Voice("实时分析", "shi shi fen xi")),
                enabled = { pageState == PageState.DETECTING },
            ) {
                startActivity(Intent(this, AiInspectionActivity::class.java))
                finish()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("device_guide_hazard_record"),
                label = "隐患录入",
                triggers = listOf(UnifiedInputSession.InputTrigger.Voice("隐患录入", "yin huan lu ru")),
                enabled = { pageState == PageState.DETECTING },
            ) {
                startActivity(Intent(this, HazardRecordActivity::class.java))
                finish()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("device_guide_finish"),
                label = "结束任务",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Voice("结束任务", "jie shu ren wu"),
                    UnifiedInputSession.InputTrigger.Voice("结速任务", "jie su ren wu"),
                ),
            ) {
                startActivity(
                    InspectionEndReportActivity.createIntent(
                        this,
                        InspectionEndReportReturnDestination.DEVICE_GUIDE_HOME,
                    ),
                )
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Confirm,
                label = "确认",
                triggers = buildConfirmTriggers(),
                enabled = { pageState == PageState.PROMPT_PENDING || pageState == PageState.DETAIL },
            ) {
                when (pageState) {
                    PageState.PROMPT_PENDING -> requestGuideDetails()
                    PageState.DETAIL -> returnToDetecting()
                    PageState.DETECTING -> Unit
                }
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Cancel,
                label = "返回",
                triggers = buildReturnTriggers(),
            ) {
                if (pageState == PageState.DETECTING) {
                    returnToMenuHome()
                } else {
                    returnToDetecting()
                }
            },
        )
    }

    private fun buildConfirmTriggers(): List<UnifiedInputSession.InputTrigger> {
        return listOf(
            UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK),
            UnifiedInputSession.InputTrigger.Voice("确认", "que ren"),
            UnifiedInputSession.InputTrigger.Voice("确定", "que ding"),
            UnifiedInputSession.InputTrigger.Voice("继续", "ji xu"),
        )
    }

    private fun buildReturnTriggers(): List<UnifiedInputSession.InputTrigger> {
        return listOf(
            UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK),
            UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK),
            UnifiedInputSession.InputTrigger.Voice("取消", "qu xiao"),
            UnifiedInputSession.InputTrigger.Voice("返回", "fan hui"),
        )
    }

    private fun refreshInputActions() {
        inputSession.updateActions(buildInputActions())
    }

    private fun ensureFrameStreamReady() {
        if (!hasRequiredPermissions()) {
            requestPermissionsIfNeeded()
            return
        }
        if (
            frameStreamReady &&
            InspectionCameraCoordinator.isFrameStreamReady() &&
            (pageState != PageState.DETECTING || viewLivePreview.isPreviewStarted())
        ) {
            return
        }
        if (frameStreamInitializing || RokidSdkManager.state != RokidSdkManager.SdkState.READY) {
            return
        }
        frameStreamInitializing = true
        var requestGeneration = 0L
        requestGeneration = InspectionCameraCoordinator.acquire(
            owner = CameraOwner.DEVICE_GUIDE,
            needPreview = true,
            previewView = viewLivePreview,
        ) { success ->
            uiHandler.post {
                if (requestGeneration != InspectionCameraCoordinator.getGeneration()) {
                    Log.i(
                        TAG,
                        "ignore stale device guide acquire callback requestGeneration=$requestGeneration currentGeneration=${InspectionCameraCoordinator.getGeneration()} success=$success",
                    )
                    return@post
                }
                frameStreamInitializing = false
                frameStreamReady = success
                Log.i(
                    TAG,
                    "ensureFrameStreamReady end success=$success generation=$requestGeneration previewStarted=${viewLivePreview.isPreviewStarted()} state=${InspectionCameraCoordinator.getState()}",
                )
                if (success && !viewLivePreview.isPreviewStarted() && !previewRestartAttempted) {
                    restartFrameStreamForPreview()
                    return@post
                }
                if (success) {
                    previewRestartAttempted = false
                    scheduleNextDetection(immediate = true)
                } else {
                    tvDetectingBottomHint.setText(R.string.device_guide_frame_stream_failed)
                }
            }
        }
    }

    private fun restartFrameStreamForPreview() {
        previewRestartAttempted = true
        frameStreamInitializing = true
        frameStreamReady = false
        Log.i(
            TAG,
            "restartFrameStreamForPreview state=${InspectionCameraCoordinator.getState()} previewStarted=${viewLivePreview.isPreviewStarted()}",
        )
        InspectionCameraCoordinator.restart(
            owner = CameraOwner.DEVICE_GUIDE,
            reason = "device_guide_preview_not_started",
            needPreview = true,
            previewView = viewLivePreview,
        ) { restartSuccess ->
            uiHandler.post {
                frameStreamInitializing = false
                frameStreamReady = restartSuccess
                Log.i(
                    TAG,
                    "restartFrameStreamForPreview end success=$restartSuccess previewStarted=${viewLivePreview.isPreviewStarted()} state=${InspectionCameraCoordinator.getState()}",
                )
                if (restartSuccess) {
                    previewRestartAttempted = false
                    scheduleNextDetection(immediate = true)
                } else {
                    tvDetectingBottomHint.setText(R.string.device_guide_frame_stream_failed)
                }
            }
        }
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

    private fun requestPermissionsIfNeeded() {
        if (mediaPermissionRequested) return
        mediaPermissionRequested = true
        ActivityCompat.requestPermissions(this, requiredPermissions(), REQUEST_MEDIA_PERMISSION)
    }

    private fun scheduleNextDetection(immediate: Boolean) {
        uiHandler.removeCallbacks(nextDetectRunnable)
        if (!isActivityResumed || pageState != PageState.DETECTING || detectInFlight || detailInFlight) {
            return
        }
        if (immediate) {
            uiHandler.post(nextDetectRunnable)
        } else {
            uiHandler.postDelayed(nextDetectRunnable, DETECT_INTERVAL_MS)
        }
    }

    private fun runDetectionLoop() {
        if (!isActivityResumed || pageState != PageState.DETECTING || detectInFlight || detailInFlight) {
            return
        }
        if (!frameStreamReady) {
            ensureFrameStreamReady()
            scheduleNextDetection(immediate = false)
            return
        }
        try {
            imageExecutor.execute {
                val payload = frameCaptureService.selectBestFramePayload(Long.MIN_VALUE)
                if (payload == null) {
                    uiHandler.post { scheduleNextDetection(immediate = false) }
                    return@execute
                }
                val base64Image = Base64.encodeToString(payload.jpegBytes, Base64.NO_WRAP)
                uiHandler.post {
                    if (!isActivityResumed || pageState != PageState.DETECTING) {
                        scheduleNextDetection(immediate = false)
                        return@post
                    }
                    detectInFlight = true
                    currentPayload = payload
                    activeDetectHandle?.cancel()
                    activeDetectHandle = detectSseService.identifyItemHazard(
                        base64Image = base64Image,
                        callback = object : AiArSseService.DetectCallback {
                            override fun onOpened(handle: AiArSseService.RequestHandle) = Unit

                            override fun onSuccess(
                                handle: AiArSseService.RequestHandle,
                                hasHazard: Boolean,
                                fullText: String,
                            ) {
                                if (activeDetectHandle != handle) return
                                activeDetectHandle = null
                                detectInFlight = false
                                if (hasHazard) {
                                    showPromptPending(payload)
                                } else {
                                    scheduleNextDetection(immediate = false)
                                }
                            }

                            override fun onFailure(handle: AiArSseService.RequestHandle, message: String) {
                                if (activeDetectHandle == handle) {
                                    activeDetectHandle = null
                                }
                                detectInFlight = false
                                tvDetectingBottomHint.text = message.ifBlank {
                                    getString(R.string.device_guide_detect_failed)
                                }
                                scheduleNextDetection(immediate = false)
                            }
                        },
                    )
                }
            }
        } catch (error: RejectedExecutionException) {
            Log.w(TAG, "device guide detect task rejected", error)
            scheduleNextDetection(immediate = false)
        }
    }

    private fun showPromptPending(payload: InspectionFrameCaptureService.CapturedFramePayload) {
        uiHandler.removeCallbacks(nextDetectRunnable)
        uiHandler.removeCallbacks(promptTimeoutRunnable)
        pageState = PageState.PROMPT_PENDING
        currentPayload = payload
        layoutDetection.visibility = View.VISIBLE
        layoutResult.visibility = View.GONE
        layoutLivePreviewCard.visibility = View.VISIBLE
        viewLivePreview.visibility = View.VISIBLE
        tvResultContent.text = ""
        scrollContent.visibility = View.GONE
        tvResultBottomHint.visibility = View.GONE
        tvDetectingBottomHint.visibility = View.GONE
        statusAlertOverlay.render(
            StatusAlertModel(
                status = AlertStatus.WARNING,
                titleText = "",
                messageText = getString(R.string.device_guide_prompt_message),
                behavior = AlertBehavior(autoDismissMs = PROMPT_TIMEOUT_MS, showCountdownBar = false),
                style = AlertStyle(iconResId = R.drawable.hidden_risk_alert),
            ),
        )
        refreshFunctionMenuVisibility()
        refreshInputActions()
        uiHandler.postDelayed(promptTimeoutRunnable, PROMPT_TIMEOUT_MS)
    }

    private fun requestGuideDetails() {
        val payload = currentPayload ?: return
        uiHandler.removeCallbacks(promptTimeoutRunnable)
        detailInFlight = true
        pageState = PageState.DETAIL
        statusAlertOverlay.reset()
        layoutDetection.visibility = View.GONE
        layoutResult.visibility = View.VISIBLE
        layoutLivePreviewCard.visibility = View.GONE
        viewLivePreview.visibility = View.GONE
        InspectionCameraCoordinator.updatePreview(
            owner = CameraOwner.DEVICE_GUIDE,
            needPreview = false,
        )
        clearResultUi()
        scrollContent.visibility = View.VISIBLE
        tvResultContent.text = getString(R.string.device_guide_fetching_detail)
        refreshFunctionMenuVisibility()
        val base64Image = Base64.encodeToString(payload.jpegBytes, Base64.NO_WRAP)
        activeDetailHandle?.cancel()
        activeDetailHandle = detailSseService.requestDeepAnalysis(
            base64Image = base64Image,
            onChunk = { partialText ->
                uiHandler.post {
                    if (pageState != PageState.DETAIL) return@post
                    tvResultContent.text = partialText.trim()
                }
            },
            callback = object : AiArSseService.DetailCallback {
                override fun onOpened(handle: AiArSseService.RequestHandle) = Unit

                override fun onSuccess(handle: AiArSseService.RequestHandle, fullText: String) {
                    uiHandler.post {
                        if (activeDetailHandle != handle) return@post
                        activeDetailHandle = null
                        detailInFlight = false
                        val resolved = runCatching {
                            AiArHazardDetailParser.parse(
                                text = fullText,
                                jpegBytes = payload.jpegBytes,
                                displayTitle = getString(R.string.device_guide_title),
                            )
                        }.getOrNull()
                        tvResultContent.text = resolved?.let { formatResolvedGuideText(it) }
                            ?.ifBlank { fullText.trim() }
                            ?: fullText.trim().ifBlank { getString(R.string.device_guide_detail_empty) }
                        refreshInputActions()
                    }
                }

                override fun onFailure(handle: AiArSseService.RequestHandle, message: String) {
                    uiHandler.post {
                        if (activeDetailHandle == handle) {
                            activeDetailHandle = null
                        }
                        detailInFlight = false
                        returnToDetecting(message.ifBlank { getString(R.string.device_guide_detail_failed) })
                    }
                }
            },
        )
        refreshInputActions()
    }

    private fun formatResolvedGuideText(resolved: ResolvedHazardContent): String {
        val primary = resolved.primaryHazard()
        val description = primary?.description?.trim().orEmpty()
        val advice = primary?.advice?.trim().orEmpty()
        return buildList {
            if (description.isNotBlank()) {
                add("检查品说明：$description")
            }
            if (advice.isNotBlank()) {
                add("检查重点：$advice")
            }
        }.joinToString("\n\n")
    }

    private fun returnToDetecting(message: String? = null) {
        uiHandler.removeCallbacks(promptTimeoutRunnable)
        cancelActiveRequests()
        pageState = PageState.DETECTING
        // 检测态重新显示预览并恢复检测循环
        layoutLivePreviewCard.visibility = View.VISIBLE
        viewLivePreview.visibility = View.VISIBLE
        InspectionCameraCoordinator.updatePreview(
            owner = CameraOwner.DEVICE_GUIDE,
            needPreview = true,
            previewView = viewLivePreview,
        )
        layoutDetection.visibility = View.VISIBLE
        layoutResult.visibility = View.GONE
        statusAlertOverlay.reset()
        clearResultUi()
        tvDetectingBottomHint.text = message ?: getString(R.string.device_guide_detecting_bottom_hint)
        tvDetectingBottomHint.visibility = View.VISIBLE
        refreshFunctionMenuVisibility()
        refreshInputActions()
        scheduleNextDetection(immediate = false)
    }

    private fun showDetectingPage() {
        returnToDetecting()
    }

    private fun returnToMenuHome() {
        cancelActiveRequests()
        startActivity(Intent(this, AiInspectionMenuActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun cancelActiveRequests() {
        uiHandler.removeCallbacks(promptTimeoutRunnable)
        detectInFlight = false
        detailInFlight = false
        activeDetectHandle?.cancel()
        activeDetailHandle?.cancel()
        activeDetectHandle = null
        activeDetailHandle = null
    }

    private fun clearResultUi() {
        tvResultContent.text = ""
        scrollContent.visibility = View.GONE
        tvResultBottomHint.visibility = View.GONE
        ivResultThumbnail.setImageBitmap(null)
        ivResultThumbnail.visibility = View.GONE
        layoutResultThumbnailCard.visibility = View.GONE
        layoutResultThumbnailPlaceholder.visibility = View.VISIBLE
    }

    private fun refreshFunctionMenuVisibility() {
        operationGuideDetecting.visibility =
            if (pageState == PageState.DETECTING || pageState == PageState.PROMPT_PENDING) {
                View.VISIBLE
            } else {
                View.GONE
            }
        operationGuideResult.visibility = View.GONE
    }

    private fun startStatusBarUpdates() {
        updateStatusBars()
        uiHandler.removeCallbacks(statusUpdateRunnable)
        uiHandler.post(statusUpdateRunnable)
        if (batteryReceiver == null) {
            batteryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    updateBatteryLevel(intent)
                }
            }
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
    }

    private fun stopStatusBarUpdates() {
        uiHandler.removeCallbacks(statusUpdateRunnable)
        batteryReceiver?.let {
            unregisterReceiver(it)
            batteryReceiver = null
        }
    }

    private fun updateStatusBars() {
        statusBarDetecting.updateTime()
        statusBarResult.updateTime()
        updateBatteryLevel()
    }

    private fun updateBatteryLevel(intent: Intent? = null) {
        val batteryStatus = intent ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryStatus?.let { batteryIntent ->
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level != -1 && scale != -1) {
                val batteryPct = (level * 100 / scale.toFloat()).toInt()
                statusBarDetecting.setBatteryPercent(batteryPct)
                statusBarResult.setBatteryPercent(batteryPct)
            }
        }
    }

    companion object {
        private const val TAG = "DeviceGuideActivity"
        private const val REQUEST_MEDIA_PERMISSION = 302
        private const val STALE_FRAME_THRESHOLD_MS = 1200L
        private const val SELECT_WINDOW_MS = 240L
        private const val SELECT_MAX_FRAMES = 3
        private const val SELECT_POLL_INTERVAL_MS = 80L
        private const val JPEG_QUALITY = 97
        private const val DETECT_INTERVAL_MS = 1000L
        private const val PROMPT_TIMEOUT_MS = 3000L
        private const val STATUS_UPDATE_DELAY_MS = 1000L
    }
}
