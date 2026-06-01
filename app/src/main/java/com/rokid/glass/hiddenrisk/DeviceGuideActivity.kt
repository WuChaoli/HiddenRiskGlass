package com.rokid.glass.hiddenrisk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
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
import com.rokid.glass.component.GlassStatusBarUpdater
import com.rokid.glass.component.RokidCameraPreviewView
import com.rokid.glass.component.StatusAlertModel
import com.rokid.glass.component.StatusAlertOverlayView
import com.rokid.glass.config.InspectionConfigRepository
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator.CameraOwner
import com.rokid.glass.input.GlassesWearStateMachine
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.utils.OfflineTtsPlayer
import com.rokid.glesse.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 设备指引独立页。
 * 当前仅依赖远端物品隐患识别，再通过深度分析拉取检查重点文本。
 */
class DeviceGuideActivity : BaseGlassActivity(), RokidSdkManager.Listener {

    override val wearSleepEnabled: Boolean
        get() = true

    override fun shouldEnableWearSleepNow(): Boolean {
        return InspectionConfigRepository.get().aiInspection.enableAutoSleepMonitoring &&
            isActivityResumed &&
            pageState == PageState.DETECTING
    }

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
    private var wearSnapshot: GlassesWearStateMachine.Snapshot? = null
    private val inputSession by lazy {
        UnifiedInputSession(this, TAG)
    }
    private val imageExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val detectSseService by lazy { AiArSseService() }
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
    private var previewRecreateAttempted = false
    private var previewReadyRetryPosted = false
    private var activeDetectHandle: AiArSseService.RequestHandle? = null
    private var activeDetailHandle: AiArSseService.RequestHandle? = null
    private var wearRecoveryFrameCheckInFlight = false
    private val statusBarUpdater by lazy { GlassStatusBarUpdater(this) }

    private val nextDetectRunnable = Runnable {
        runDetectionLoop()
    }
    private val wearRecoveryFrameRunnable = Runnable {
        pollWearRecoveryReadyFrame()
    }

    private val autoGuideDetailRunnable = Runnable {
        if (pageState == PageState.PROMPT_PENDING && currentPayload != null) {
            requestGuideDetails()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_guide)
        initViews()
        showDetectingPage()
        OfflineTtsPlayer.play(
            context = this,
            ownerTag = TAG,
            audioResId = R.raw.start_check_guide,
        )
        RokidSdkManager.initialize(application)
        RokidSdkManager.addListener(this)
        RokidSdkManager.ensureInitialized()
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        ensureFrameStreamReady()
        inputSession.attach()
        refreshInputActions()
        updateWearMonitoringEnabled()
        statusBarUpdater.start(statusBarDetecting, statusBarResult)
        scheduleNextDetection(immediate = true)
    }

    override fun onPause() {
        isActivityResumed = false
        statusBarUpdater.stop()
        cancelActiveRequests()
        uiHandler.removeCallbacks(nextDetectRunnable)
        uiHandler.removeCallbacks(autoGuideDetailRunnable)
        frameStreamInitializing = false
        frameStreamReady = false
        InspectionCameraCoordinator.pause(CameraOwner.DEVICE_GUIDE, reason = "device_guide_on_pause")
        uiHandler.removeCallbacks(wearRecoveryFrameRunnable)
        inputSession.detach()
        super.onPause()
    }

    override fun onDestroy() {
        cancelActiveRequests()
        statusBarUpdater.stop()
        uiHandler.removeCallbacksAndMessages(null)
        OfflineTtsPlayer.release(TAG)
        inputSession.release()
        RokidSdkManager.removeListener(this)
        InspectionCameraCoordinator.pause(CameraOwner.DEVICE_GUIDE, reason = "device_guide_on_destroy")
        imageExecutor.shutdownNow()
        // 释放 OkHttp 空闲连接，避免服务器端残留 ESTABLISHED 连接
        detectSseService.releaseConnections()
        super.onDestroy()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        if (isWearInteractionBlocked()) {
            return true
        }
        return inputSession.dispatchTouch(keyEvent) || super.onGlassKeyEvent(keyEvent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        if (!isActivityResumed) {
            Log.i(TAG, "restore resumed state from window focus")
            isActivityResumed = true
        }
        if (pageState == PageState.DETECTING) {
            ensureFrameStreamReady()
        }
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
        statusBarUpdater.refreshNow(statusBarDetecting, statusBarResult)
    }

    private fun buildInputActions(): List<UnifiedInputSession.InputActionSpec> {
        return listOf(
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("device_guide_realtime_analysis"),
                label = "实时分析",
                triggers = listOf(UnifiedInputSession.InputTrigger.Voice("实时分析", "shi shi fen xi")),
                enabled = { canHandleDetectingInput() },
            ) {
                startActivity(Intent(this, AiInspectionActivity::class.java))
                finish()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("device_guide_hazard_record"),
                label = "隐患拍照",
                triggers = listOf(UnifiedInputSession.InputTrigger.Voice("隐患拍照", "yin huan pai zhao")),
                enabled = { canHandleDetectingInput() },
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
                enabled = { !isWearInteractionBlocked() },
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
                enabled = {
                    !isWearInteractionBlocked() &&
                        (pageState == PageState.PROMPT_PENDING || pageState == PageState.DETAIL)
                },
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
                enabled = { !isWearInteractionBlocked() },
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

    private fun canHandleDetectingInput(): Boolean {
        return pageState == PageState.DETECTING && !isWearInteractionBlocked()
    }

    private fun ensureFrameStreamReady() {
        if (!isActivityResumed) {
            Log.i(TAG, "skip frame stream because device guide is not resumed")
            return
        }
        if (wearSnapshot?.state == GlassesWearStateMachine.State.SLEEP) {
            Log.i(TAG, "skip frame stream while glasses are removed")
            return
        }
        if (!hasRequiredPermissions()) {
            requestPermissionsIfNeeded()
            return
        }
        if (pageState == PageState.DETECTING && !isLivePreviewViewReady()) {
            scheduleFrameStreamAfterPreviewReady()
            return
        }
        previewReadyRetryPosted = false
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
                if (success && !viewLivePreview.isPreviewStarted() && !previewRecreateAttempted) {
                    recreateDeviceGuidePreviewView(reason = "acquire_preview_not_started")
                    return@post
                }
                if (success) {
                    if (isWearRecovering()) {
                        pollWearRecoveryReadyFrame()
                    } else {
                        scheduleNextDetection(immediate = true)
                    }
                    scheduleDeviceGuidePreviewDrawCheck(requestGeneration)
                } else {
                    if (isWearRecovering()) statusAlertOverlay.reset()
                    tvDetectingBottomHint.setText(R.string.device_guide_frame_stream_failed)
                    tvDetectingBottomHint.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun scheduleFrameStreamAfterPreviewReady() {
        Log.i(
            TAG,
            "defer frame stream until preview view ready attached=${viewLivePreview.isAttachedToWindow} width=${viewLivePreview.width} height=${viewLivePreview.height}",
        )
        if (previewReadyRetryPosted) return
        previewReadyRetryPosted = true
        viewLivePreview.postDelayed(
            {
                previewReadyRetryPosted = false
                if (isActivityResumed && pageState == PageState.DETECTING) {
                    ensureFrameStreamReady()
                }
            },
            PREVIEW_READY_RETRY_DELAY_MS,
        )
    }

    private fun isLivePreviewViewReady(): Boolean {
        return viewLivePreview.isAttachedToWindow &&
            viewLivePreview.width > 0 &&
            viewLivePreview.height > 0
    }

    private fun scheduleDeviceGuidePreviewDrawCheck(generation: Long) {
        uiHandler.postDelayed(
            {
                if (
                    !isActivityResumed ||
                    pageState != PageState.DETECTING ||
                    generation != InspectionCameraCoordinator.getGeneration()
                ) {
                    return@postDelayed
                }
                if (viewLivePreview.isPreviewStarted() && !viewLivePreview.isPreviewFrameDrawn()) {
                    Log.w(
                        TAG,
                        "device guide preview has no drawn frame generation=$generation state=${InspectionCameraCoordinator.getState()}",
                    )
                    if (!previewRecreateAttempted) {
                        recreateDeviceGuidePreviewView(reason = "preview_no_drawn_frame")
                    }
                }
            },
            PREVIEW_DRAW_CHECK_DELAY_MS,
        )
    }

    private fun recreateDeviceGuidePreviewView(reason: String) {
        previewRecreateAttempted = true
        Log.i(
            TAG,
            "recreateDeviceGuidePreviewView reason=$reason state=${InspectionCameraCoordinator.getState()} previewStarted=${viewLivePreview.isPreviewStarted()} frameDrawn=${viewLivePreview.isPreviewFrameDrawn()} pageState=$pageState",
        )
        InspectionCameraCoordinator.updatePreview(
            owner = CameraOwner.DEVICE_GUIDE,
            needPreview = false,
            previewView = null,
        ) {
            val oldPreview = viewLivePreview
            val oldIndex = layoutLivePreviewCard.indexOfChild(oldPreview).takeIf { it >= 0 }
                ?: layoutLivePreviewCard.childCount
            val oldLayoutParams = oldPreview.layoutParams
            val oldVisibility = oldPreview.visibility
            oldPreview.detachPreview()
            layoutLivePreviewCard.removeView(oldPreview)
            val newPreview = RokidCameraPreviewView(this).apply {
                id = R.id.viewLivePreview
                visibility = oldVisibility
                layoutParams = oldLayoutParams ?: FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            layoutLivePreviewCard.addView(newPreview, oldIndex)
            viewLivePreview = newPreview
            layoutLivePreviewCard.visibility = View.VISIBLE
            viewLivePreview.visibility = View.VISIBLE
            InspectionCameraCoordinator.updatePreview(
                owner = CameraOwner.DEVICE_GUIDE,
                needPreview = pageState == PageState.DETECTING,
                previewView = viewLivePreview,
            ) rebindPreview@{ success ->
                frameStreamReady = success
                if (!success) {
                    Log.w(TAG, "recreated device guide preview bind failed reason=$reason")
                    if (isWearRecovering()) statusAlertOverlay.reset()
                    tvDetectingBottomHint.setText(R.string.device_guide_frame_stream_failed)
                    tvDetectingBottomHint.visibility = View.VISIBLE
                    return@rebindPreview
                }
                Log.i(
                    TAG,
                    "recreated device guide preview ready reason=$reason generation=${InspectionCameraCoordinator.getGeneration()}",
                )
                if (isWearRecovering()) {
                    pollWearRecoveryReadyFrame()
                } else {
                    scheduleNextDetection(immediate = true)
                }
                scheduleDeviceGuidePreviewDrawCheck(InspectionCameraCoordinator.getGeneration())
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
        if (!isActivityResumed || pageState != PageState.DETECTING || isWearInteractionBlocked() || detectInFlight || detailInFlight) {
            return
        }
        if (immediate) {
            uiHandler.post(nextDetectRunnable)
        } else {
            uiHandler.postDelayed(nextDetectRunnable, DETECT_INTERVAL_MS)
        }
    }

    private fun runDetectionLoop() {
        if (!isActivityResumed || pageState != PageState.DETECTING || isWearInteractionBlocked() || detectInFlight || detailInFlight) {
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
                    if (!isActivityResumed || pageState != PageState.DETECTING || isWearInteractionBlocked()) {
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
                                labels: List<String>,
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
        updateWearSleepEligibility(false)
        uiHandler.removeCallbacks(nextDetectRunnable)
        uiHandler.removeCallbacks(autoGuideDetailRunnable)
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
                behavior = AlertBehavior(autoDismissMs = PROMPT_AUTO_DETAIL_DELAY_MS, showCountdownBar = false),
                style = AlertStyle(iconResId = R.drawable.hidden_risk_alert),
            ),
        )
        refreshFunctionMenuVisibility()
        refreshInputActions()
        uiHandler.postDelayed(autoGuideDetailRunnable, PROMPT_AUTO_DETAIL_DELAY_MS)
    }

    private fun requestGuideDetails() {
        updateWearSleepEligibility(false)
        val payload = currentPayload ?: return
        uiHandler.removeCallbacks(autoGuideDetailRunnable)
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
        OfflineTtsPlayer.release(TAG)
        val base64Image = Base64.encodeToString(payload.jpegBytes, Base64.NO_WRAP)
        activeDetailHandle?.cancel()
        activeDetailHandle = detectSseService.fetchInspectionGuide(
            base64Image = base64Image,
            onChunk = { partialText ->
                uiHandler.post {
                    if (pageState != PageState.DETAIL) return@post
                    if (tvResultContent.text.isNullOrBlank() || tvResultContent.text == getString(R.string.device_guide_fetching_detail)) {
                        OfflineTtsPlayer.play(
                            context = this@DeviceGuideActivity,
                            ownerTag = TAG,
                            audioResId = R.raw.device_guide,
                        )
                    }
                    tvResultContent.text = buildGuideDetailDisplayText(partialText)
                }
            },
            callback = object : AiArSseService.DetailCallback {
                override fun onOpened(handle: AiArSseService.RequestHandle) = Unit

                override fun onSuccess(handle: AiArSseService.RequestHandle, fullText: String) {
                    uiHandler.post {
                        if (activeDetailHandle != handle) return@post
                        activeDetailHandle = null
                        detailInFlight = false
                        tvResultContent.text = buildGuideDetailDisplayText(fullText)
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

    private fun buildGuideDetailDisplayText(detailText: String): String {
        val trimmedText = detailText.trim()
        return when {
            trimmedText.isBlank() -> getString(R.string.device_guide_detail_empty)
            trimmedText.startsWith(DEVICE_GUIDE_DETAIL_DISPLAY_PREFIX) -> trimmedText
            else -> "$DEVICE_GUIDE_DETAIL_DISPLAY_PREFIX\n$trimmedText"
        }
    }

    private fun returnToDetecting(message: String? = null) {
        uiHandler.removeCallbacks(autoGuideDetailRunnable)
        cancelActiveRequests()
        OfflineTtsPlayer.release(TAG)
        pageState = PageState.DETECTING
        // 检测态重新显示预览并恢复检测循环
        layoutLivePreviewCard.visibility = View.VISIBLE
        viewLivePreview.visibility = View.VISIBLE
        InspectionCameraCoordinator.updatePreview(
            owner = CameraOwner.DEVICE_GUIDE,
            needPreview = true,
            previewView = viewLivePreview,
        ) { success ->
            if (!success) {
                Log.w(TAG, "device guide preview return bind failed")
                return@updatePreview
            }
            if (!viewLivePreview.isPreviewStarted() && !previewRecreateAttempted) {
                recreateDeviceGuidePreviewView(reason = "return_preview_not_started")
                return@updatePreview
            }
            scheduleDeviceGuidePreviewDrawCheck(InspectionCameraCoordinator.getGeneration())
        }
        layoutDetection.visibility = View.VISIBLE
        layoutResult.visibility = View.GONE
        statusAlertOverlay.reset()
        clearResultUi()
        tvDetectingBottomHint.text = message ?: getString(R.string.device_guide_detecting_bottom_hint)
        tvDetectingBottomHint.visibility = View.VISIBLE
        refreshFunctionMenuVisibility()
        refreshInputActions()
        updateWearMonitoringEnabled()
        scheduleNextDetection(immediate = false)
    }

    private fun showDetectingPage() {
        returnToDetecting()
    }

    private fun returnToMenuHome() {
        updateWearSleepEligibility(false)
        cancelActiveRequests()
        startActivity(Intent(this, AiInspectionMenuActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun cancelActiveRequests() {
        uiHandler.removeCallbacks(autoGuideDetailRunnable)
        detectInFlight = false
        detailInFlight = false
        activeDetectHandle?.cancel()
        activeDetailHandle?.cancel()
        activeDetectHandle = null
        activeDetailHandle = null
    }

    private fun updateWearMonitoringEnabled() {
        val enabled = shouldEnableWearSleepNow()
        if (!enabled) {
            uiHandler.removeCallbacks(wearRecoveryFrameRunnable)
        }
        updateWearSleepEligibility(enabled)
    }

    override fun onWearStateChanged(snapshot: GlassesWearStateMachine.Snapshot?) {
        val previousState = wearSnapshot?.state
        wearSnapshot = snapshot
        refreshInputActions()
        when (snapshot?.state) {
            GlassesWearStateMachine.State.SLEEP -> showWearSleep()
            GlassesWearStateMachine.State.WAKE -> beginWearRecovery()
            GlassesWearStateMachine.State.ACTIVE -> {
                if (previousState == GlassesWearStateMachine.State.WAKE) {
                    finishWearRecovery()
                }
            }
            else -> Unit
        }
    }

    private fun showWearSleep() {
        if (pageState != PageState.DETECTING) {
            updateWearSleepEligibility(false)
            return
        }
        uiHandler.removeCallbacks(wearRecoveryFrameRunnable)
        wearRecoveryFrameCheckInFlight = false
        cancelActiveRequests()
        uiHandler.removeCallbacks(nextDetectRunnable)
        frameStreamInitializing = false
        frameStreamReady = false
        previewRecreateAttempted = false
        InspectionCameraCoordinator.pause(CameraOwner.DEVICE_GUIDE, reason = "device_guide_wear_sleep")
        layoutLivePreviewCard.visibility = View.INVISIBLE
        viewLivePreview.visibility = View.INVISIBLE
        tvDetectingBottomHint.visibility = View.GONE
        operationGuideDetecting.visibility = View.GONE
        statusAlertOverlay.render(
            StatusAlertModel(
                status = AlertStatus.WARNING,
                titleText = getString(R.string.inspection_wear_removed_title),
                messageText = getString(R.string.inspection_wear_removed_message),
                behavior = AlertBehavior(autoDismissMs = null, showCountdownBar = false),
                style = AlertStyle(iconResId = R.drawable.hidden_risk_alert),
            ),
        )
    }

    private fun beginWearRecovery() {
        if (!isActivityResumed || pageState != PageState.DETECTING) {
            updateWearMonitoringEnabled()
            return
        }
        statusAlertOverlay.render(
            StatusAlertModel(
                status = AlertStatus.WARNING,
                titleText = getString(R.string.inspection_wear_recovering_title),
                messageText = getString(R.string.inspection_wear_recovering_message),
                behavior = AlertBehavior(autoDismissMs = null, showCountdownBar = false),
                style = AlertStyle(iconResId = R.drawable.hidden_risk_alert),
            ),
        )
        layoutLivePreviewCard.visibility = View.VISIBLE
        viewLivePreview.visibility = View.VISIBLE
        tvDetectingBottomHint.visibility = View.GONE
        refreshFunctionMenuVisibility()
        ensureFrameStreamReady()
        pollWearRecoveryReadyFrame()
    }

    private fun pollWearRecoveryReadyFrame() {
        uiHandler.removeCallbacks(wearRecoveryFrameRunnable)
        if (!isWearRecovering() || !frameStreamReady || wearRecoveryFrameCheckInFlight) return
        wearRecoveryFrameCheckInFlight = true
        try {
            imageExecutor.execute {
                val payload = frameCaptureService.selectBestFramePayload(Long.MIN_VALUE)
                uiHandler.post {
                    wearRecoveryFrameCheckInFlight = false
                    if (!isWearRecovering()) return@post
                    if (payload != null) {
                        reportWearRecoveryReady()
                    } else {
                        uiHandler.postDelayed(wearRecoveryFrameRunnable, DETECT_INTERVAL_MS)
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            wearRecoveryFrameCheckInFlight = false
            Log.w(TAG, "wear recovery frame task rejected", error)
        }
    }

    private fun finishWearRecovery() {
        uiHandler.removeCallbacks(wearRecoveryFrameRunnable)
        statusAlertOverlay.reset()
        tvDetectingBottomHint.setText(R.string.device_guide_detecting_bottom_hint)
        tvDetectingBottomHint.visibility = View.VISIBLE
        refreshFunctionMenuVisibility()
        scheduleNextDetection(immediate = true)
    }

    private fun isWearInteractionBlocked(): Boolean {
        return wearSnapshot?.state in setOf(GlassesWearStateMachine.State.SLEEP, GlassesWearStateMachine.State.WAKE)
    }

    private fun isWearRecovering(): Boolean = wearSnapshot?.state == GlassesWearStateMachine.State.WAKE

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
            if ((pageState == PageState.DETECTING || pageState == PageState.PROMPT_PENDING) &&
                !isWearInteractionBlocked()
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
        operationGuideResult.visibility = View.GONE
    }

    companion object {
        private const val TAG = "DeviceGuideActivity"
        private const val DEVICE_GUIDE_DETAIL_DISPLAY_PREFIX = "识别到此处有设备，建议您重点关注以下问题："
        private const val REQUEST_MEDIA_PERMISSION = 302
        private const val STALE_FRAME_THRESHOLD_MS = 1200L
        private const val SELECT_WINDOW_MS = 240L
        private const val SELECT_MAX_FRAMES = 3
        private const val SELECT_POLL_INTERVAL_MS = 80L
        private const val JPEG_QUALITY = 97
        private const val DETECT_INTERVAL_MS = 1000L
        private const val PROMPT_AUTO_DETAIL_DELAY_MS = 2000L
        private const val PREVIEW_READY_RETRY_DELAY_MS = 100L
        private const val PREVIEW_DRAW_CHECK_DELAY_MS = 700L
    }
}
