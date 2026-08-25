package com.rokid.glass.hiddenrisk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaActionSound
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
import com.rokid.glass.InspectionEndReportReturnDestination
import com.rokid.glass.InspectionEndReportActivity
import com.rokid.glass.InspectionFeatureFlags
import com.rokid.glass.camera.RokidFrameSource
import com.rokid.glass.camera.CameraStreamProfile
import com.rokid.glass.component.FunctionMenuView
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.component.GlassStatusBarUpdater
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator.CameraOwner
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.utils.OfflineTtsPlayer
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glesse.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 独立隐患录入页。
 * 页面不展示实时预览，只复用隐患识别链路中的 NV21 帧源完成截帧、分析和保存。
 */
class HazardRecordActivity : BaseGlassActivity(), RokidSdkManager.Listener {

    private enum class PageState {
        IDLE,
        COUNTDOWN,
        ANALYSIS,
        STRUCTURED_RESULT,
    }

    private lateinit var layoutIdle: FrameLayout
    private lateinit var layoutCountdown: FrameLayout
    private lateinit var layoutAnalysis: FrameLayout
    private lateinit var functionMenuIdle: FunctionMenuView
    private lateinit var functionMenuCountdown: FunctionMenuView
    private lateinit var statusBarIdle: GlassStatusBar
    private lateinit var statusBarCountdown: GlassStatusBar
    private lateinit var statusBarAnalysis: GlassStatusBar
    private lateinit var tvIdleHint: TextView
    private lateinit var tvSuccessToast: TextView
    private lateinit var tvCountdownValue: TextView
    private lateinit var ivThumbnail: ImageView
    private lateinit var scrollAnalysis: ScrollView
    private lateinit var tvAnalysisContent: TextView
    private lateinit var tvAnalysisHint: TextView
    private lateinit var layoutDeepV2Result: FrameLayout
    private lateinit var ivDeepV2ResultImage: ImageView
    private lateinit var viewDeepV2ResultOverlay: DeepV2ResultOverlayView
    private lateinit var viewDeepV2HazardDetail: HazardDetailOverlayView
    private lateinit var layoutDeepV2SaveDialog: View
    private lateinit var tvDeepV2SaveConfirm: TextView
    private lateinit var tvDeepV2SaveCancel: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private val aiArSseService by lazy { AiArSseService() }
    private val deepV2Client by lazy { DeepV2Client.create() }
    private val deepV2Normalizer = DeepV2ResultNormalizer()
    private val deepV2Coordinator = HazardRecordV2Coordinator()
    private val localHazardPushService by lazy { LocalHazardPushService() }
    private val imageExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val shutterSound by lazy { MediaActionSound() }
    private val frameCaptureService by lazy {
        InspectionFrameCaptureService(
            frameProvider = RokidFullFrameProvider,
            staleFrameThresholdMs = STALE_FRAME_THRESHOLD_MS,
            selectWindowMs = SELECT_WINDOW_MS,
            selectMaxFrames = SELECT_MAX_FRAMES,
            selectPollIntervalMs = SELECT_POLL_INTERVAL_MS,
            jpegQuality = JPEG_QUALITY,
            logger = { stage, extra -> Log.i(TAG, "$stage $extra") },
        )
    }

    private var pageState = PageState.IDLE
    private var frameStreamReady = false
    private var frameStreamInitializing = false
    private var cameraSessionGeneration = 0L
    private var cameraRequestToken: Long = -1L
    private var mediaPermissionRequested = false
    private var countdownRemaining = 0
    private var captureInProgress = false
    private var streamingInProgress = false
    private var saveSubmitting = false
    private var activeRequestId = 0L
    private var activeAnalysisHandle: DeepV2Client.RequestHandle? = null
    private var localHazardUploadHandle: RetryRequestHandle? = null
    private var activeHazardContent: ResolvedHazardContent? = null
    private var lastStreamText = ""
    private var currentThumbnail: Bitmap? = null
    private var resultBitmap: Bitmap? = null
    private var resultSession: StructuredHazardResultSession? = null
    private var resultTargets: List<Pair<String?, List<DeepV2PresentationHazard>>> = emptyList()
    private var resultNavigation: DeepV2PresentationStateMachine? = null
    private var resultRenderGeneration = 0L
    private var isActivityResumed = false
    private var navigatingToDeviceGuide = false
    private val statusBarUpdater by lazy { GlassStatusBarUpdater(this) }

    private val hideSuccessToastRunnable = Runnable {
        tvSuccessToast.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hazard_record)
        initViews()
        OfflineTtsPlayer.play(
            context = this,
            ownerTag = TAG,
            audioResId = R.raw.start_hazard_record,
        )
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)
        RokidSdkManager.initialize(application)
        RokidSdkManager.addListener(this)
        RokidSdkManager.ensureInitialized()
        ensureFrameStreamReady()
        showPage(PageState.IDLE)
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        Log.i(
            TAG,
            "onResume pageState=$pageState frameReady=$frameStreamReady frameInitializing=$frameStreamInitializing frameOpen=${RokidFrameSource.isFrameStreamOpen()} frameWarm=${RokidFrameSource.isFrameStreamWarm()} captureInProgress=$captureInProgress streamingInProgress=$streamingInProgress saveSubmitting=$saveSubmitting",
        )
        ensureFrameStreamReady()
        inputSession.attach()
        refreshInputActions()
        statusBarUpdater.start(statusBarIdle, statusBarCountdown, statusBarAnalysis)
    }

    override fun onPause() {
        isActivityResumed = false
        Log.i(
            TAG,
            "onPause pageState=$pageState frameReady=$frameStreamReady frameInitializing=$frameStreamInitializing frameOpen=${RokidFrameSource.isFrameStreamOpen()} frameWarm=${RokidFrameSource.isFrameStreamWarm()} captureInProgress=$captureInProgress streamingInProgress=$streamingInProgress saveSubmitting=$saveSubmitting",
        )
        frameStreamInitializing = false
        frameStreamReady = false
        cameraSessionGeneration = 0L
        activeAnalysisHandle?.cancel()
        activeAnalysisHandle = null
        deepV2Coordinator.cancel()
        streamingInProgress = false
        InspectionCameraCoordinator.pauseTemporarily(CameraOwner.HAZARD_RECORD, reason = "hazard_record_on_pause")
        statusBarUpdater.stop()
        inputSession.detach()
        super.onPause()
    }

    override fun onDestroy() {
        Log.i(
            TAG,
            "onDestroy pageState=$pageState frameReady=$frameStreamReady frameInitializing=$frameStreamInitializing frameOpen=${RokidFrameSource.isFrameStreamOpen()} frameWarm=${RokidFrameSource.isFrameStreamWarm()} captureInProgress=$captureInProgress streamingInProgress=$streamingInProgress saveSubmitting=$saveSubmitting",
        )
        cancelActiveWork()
        InspectionCameraCoordinator.releaseForNavigation(CameraOwner.HAZARD_RECORD, reason = "hazard_record_on_destroy")
        statusBarUpdater.stop()
        uiHandler.removeCallbacksAndMessages(null)
        OfflineTtsPlayer.release(TAG)
        inputSession.release()
        RokidSdkManager.removeListener(this)
        imageExecutor.shutdownNow()
        runCatching { shutterSound.release() }
        currentThumbnail?.takeIf { !it.isRecycled }?.recycle()
        currentThumbnail = null
        // 释放 OkHttp 空闲连接，避免服务器端残留 ESTABLISHED 连接
        aiArSseService.releaseConnections()
        super.onDestroy()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        return inputSession.dispatchTouch(keyEvent) || super.onGlassKeyEvent(keyEvent)
    }

    override fun onSdkStateChanged(state: RokidSdkManager.SdkState) {
        if (state == RokidSdkManager.SdkState.READY) {
            ensureFrameStreamReady()
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
        } else {
            tvIdleHint.setText(R.string.ai_inspection_loading_missing_camera_permission)
            refreshInputActions()
        }
    }

    private fun initViews() {
        layoutIdle = findViewById(R.id.layoutIdle)
        layoutCountdown = findViewById(R.id.layoutCountdown)
        layoutAnalysis = findViewById(R.id.layoutAnalysis)
        functionMenuIdle = findViewById(R.id.functionMenuIdle)
        functionMenuCountdown = findViewById(R.id.functionMenuCountdown)
        statusBarIdle = findViewById(R.id.statusBarIdle)
        statusBarCountdown = findViewById(R.id.statusBarCountdown)
        statusBarAnalysis = findViewById(R.id.statusBarAnalysis)
        tvIdleHint = findViewById(R.id.tvIdleHint)
        tvSuccessToast = findViewById(R.id.tvSuccessToast)
        tvCountdownValue = findViewById(R.id.tvCountdownValue)
        ivThumbnail = findViewById(R.id.ivThumbnail)
        scrollAnalysis = findViewById(R.id.scrollAnalysis)
        tvAnalysisContent = findViewById(R.id.tvAnalysisContent)
        tvAnalysisHint = findViewById(R.id.tvAnalysisHint)
        layoutDeepV2Result = findViewById(R.id.layoutDeepV2Result)
        ivDeepV2ResultImage = findViewById(R.id.ivDeepV2ResultImage)
        viewDeepV2ResultOverlay = findViewById(R.id.viewDeepV2ResultOverlay)
        viewDeepV2HazardDetail = findViewById(R.id.viewDeepV2HazardDetail)
        layoutDeepV2SaveDialog = findViewById(R.id.layoutDeepV2SaveDialog)
        tvDeepV2SaveConfirm = findViewById(R.id.tvDeepV2SaveConfirm)
        tvDeepV2SaveCancel = findViewById(R.id.tvDeepV2SaveCancel)
        val menuContent = getString(R.string.hazard_record_function_menu_content)
        functionMenuIdle.setMenu(content = menuContent)
        functionMenuCountdown.setMenu(content = menuContent)
        statusBarUpdater.refreshNow(statusBarIdle, statusBarCountdown, statusBarAnalysis)
    }

    private fun buildInputActions(): List<UnifiedInputSession.InputActionSpec> {
        return listOf(
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("hazard_record_capture"),
                label = "拍照",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK),
                    UnifiedInputSession.InputTrigger.Voice("拍照", "pai zhao"),
                ),
                enabled = { pageState == PageState.IDLE && !captureInProgress },
            ) {
                startCountdown()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("hazard_record_realtime_analysis"),
                label = "实时分析",
                triggers = listOf(UnifiedInputSession.InputTrigger.Voice("实时分析", "shi shi fen xi")),
                enabled = { pageState == PageState.IDLE },
            ) {
                startRealtimeAnalysis()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("hazard_record_device_guide"),
                label = "设备指引",
                triggers = listOf(UnifiedInputSession.InputTrigger.Voice("设备指引", "she bei zhi yin")),
                enabled = { pageState == PageState.IDLE },
            ) {
                navigateToDeviceGuide()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("hazard_record_finish_task"),
                label = "结束任务",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Voice("结束任务", "jie shu ren wu"),
                    UnifiedInputSession.InputTrigger.Voice("结速任务", "jie su ren wu"),
                ),
                enabled = { pageState == PageState.IDLE },
            ) {
                InspectionCameraCoordinator.releaseForNavigation(CameraOwner.HAZARD_RECORD, reason = "hazard_record_end_task")
                startActivity(
                    InspectionEndReportActivity.createIntent(
                        this,
                        InspectionEndReportReturnDestination.HAZARD_RECORD_HOME,
                    ),
                )
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("hazard_record_result_forward"),
                label = "下一个",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BEHIND),
                    UnifiedInputSession.InputTrigger.Voice("下一个", "xia yi ge"),
                ),
                enabled = { pageState == PageState.STRUCTURED_RESULT && !saveSubmitting },
            ) {
                resultNavigation?.let { machine ->
                    val previous = machine.state
                    renderResultTransition(previous, if (previous is DeepV2NavigationState.SaveDialog) machine.selectNextDialogChoice() else machine.forward())
                }
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("hazard_record_result_backward"),
                label = "上一个",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.FRONT),
                    UnifiedInputSession.InputTrigger.Voice("上一个", "shang yi ge"),
                ),
                enabled = { pageState == PageState.STRUCTURED_RESULT && !saveSubmitting },
            ) {
                resultNavigation?.let { machine ->
                    val previous = machine.state
                    renderResultTransition(previous, if (previous is DeepV2NavigationState.SaveDialog) machine.selectPreviousDialogChoice() else machine.backward())
                }
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Confirm,
                label = "确认",
                triggers = buildConfirmTriggers(),
                enabled = {
                    (pageState == PageState.ANALYSIS || pageState == PageState.STRUCTURED_RESULT) &&
                        !streamingInProgress && !saveSubmitting
                },
            ) {
                if (pageState == PageState.STRUCTURED_RESULT) {
                    resultNavigation?.let { machine ->
                        val previous = machine.state
                        renderResultTransition(previous, machine.confirm())
                    }
                } else {
                    handleAnalysisConfirm()
                }
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Cancel,
                label = "返回",
                triggers = buildReturnTriggers(),
                enabled = { pageState != PageState.IDLE },
            ) {
                returnToIdle()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Exit,
                label = "退出",
                triggers = buildReturnTriggers(),
                enabled = { pageState == PageState.IDLE },
            ) {
                InspectionCameraCoordinator.releaseForNavigation(CameraOwner.HAZARD_RECORD, reason = "hazard_record_exit")
                finish()
            },
        )
    }

    private fun buildConfirmTriggers(): List<UnifiedInputSession.InputTrigger> {
        return listOf(
            UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK),
            UnifiedInputSession.InputTrigger.Voice("确认", "que ren"),
            UnifiedInputSession.InputTrigger.Voice("确定", "que ding"),
        )
    }

    private fun buildReturnTriggers(): List<UnifiedInputSession.InputTrigger> {
        return listOf(
            UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK),
            UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK),
            UnifiedInputSession.InputTrigger.Voice("返回", "fan hui"),
            UnifiedInputSession.InputTrigger.Voice("取消", "qu xiao"),
        )
    }

    private fun refreshInputActions() {
        inputSession.updateActions(buildInputActions())
    }

    private fun ensureFrameStreamReady() {
        if (!isActivityResumed || navigatingToDeviceGuide || isFinishing || isDestroyed) {
            Log.i(
                TAG,
                "skip ensureFrameStreamReady resumed=$isActivityResumed finishing=$isFinishing destroyed=$isDestroyed navigatingToDeviceGuide=$navigatingToDeviceGuide",
            )
            return
        }
        if (!hasRequiredPermissions()) {
            requestPermissionsIfNeeded()
            return
        }
        if (frameStreamReady && InspectionCameraCoordinator.isFrameStreamReady() && RokidFrameSource.isFrameStreamWarm()) {
            refreshInputActions()
            return
        }
        if (frameStreamInitializing) {
            return
        }
        if (RokidSdkManager.state != RokidSdkManager.SdkState.READY) {
            return
        }
        Log.i(
            TAG,
            "ensureFrameStreamReady start pageState=$pageState frameReady=$frameStreamReady frameInitializing=$frameStreamInitializing frameOpen=${RokidFrameSource.isFrameStreamOpen()} frameWarm=${RokidFrameSource.isFrameStreamWarm()} sdkState=${RokidSdkManager.state}",
        )
        frameStreamInitializing = true
        cameraRequestToken = InspectionCameraCoordinator.acquireForActivity(
            owner = CameraOwner.HAZARD_RECORD,
            needPreview = false,
            streamProfile = CameraStreamProfile.FULL_FRAME_OVERLAY_TEST,
        ) { success ->
            uiHandler.post {
                frameStreamInitializing = false
                frameStreamReady = success
                Log.i(
                    TAG,
                    "ensureFrameStreamReady end success=$success pageState=$pageState frameReady=$frameStreamReady frameOpen=${RokidFrameSource.isFrameStreamOpen()} frameWarm=${RokidFrameSource.isFrameStreamWarm()}",
                )
                if (!success && pageState == PageState.IDLE) {
                    tvIdleHint.setText(R.string.hazard_record_frame_stream_failed)
                } else if (success && pageState == PageState.IDLE) {
                    tvIdleHint.setText(R.string.hazard_record_photo_prompt)
                }
                refreshInputActions()
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
        if (mediaPermissionRequested) {
            return
        }
        mediaPermissionRequested = true
        ActivityCompat.requestPermissions(this, requiredPermissions(), REQUEST_MEDIA_PERMISSION)
    }

    private fun startCountdown() {
        if (!frameStreamReady) {
            ensureFrameStreamReady()
            tvIdleHint.setText(R.string.hazard_record_frame_stream_failed)
            return
        }
        uiHandler.removeCallbacks(hideSuccessToastRunnable)
        tvSuccessToast.visibility = View.GONE
        countdownRemaining = COUNTDOWN_SECONDS
        showPage(PageState.COUNTDOWN)
        tickCountdown()
    }

    private fun tickCountdown() {
        if (pageState != PageState.COUNTDOWN) {
            return
        }
        tvCountdownValue.text = countdownRemaining.toString()
        if (countdownRemaining <= 1) {
            uiHandler.postDelayed({ finishCountdownAndCapture() }, COUNTDOWN_TICK_MS)
            return
        }
        countdownRemaining -= 1
        uiHandler.postDelayed({ tickCountdown() }, COUNTDOWN_TICK_MS)
    }

    private fun finishCountdownAndCapture() {
        if (pageState != PageState.COUNTDOWN) {
            return
        }
        runCatching { shutterSound.play(MediaActionSound.SHUTTER_CLICK) }
        captureAndAnalyze()
    }

    private fun captureAndAnalyze() {
        if (captureInProgress) return
        captureInProgress = true
        try {
            imageExecutor.execute {
                val payload = frameCaptureService.selectBestFramePayload(Long.MIN_VALUE)
                val alignedImage = payload?.let {
                    AlignedDeepImagePayloadEncoder.encode(it.jpegBytes, JPEG_QUALITY)
                }
                uiHandler.post {
                    captureInProgress = false
                    if (alignedImage == null || alignedImage.jpegBytes.isEmpty()) {
                        showPage(PageState.IDLE)
                        tvIdleHint.setText(R.string.hazard_record_capture_failed)
                        refreshInputActions()
                        return@post
                    }
                    beginAnalysis(alignedImage)
                }
            }
        } catch (error: RejectedExecutionException) {
            Log.w(TAG, "capture task rejected", error)
            captureInProgress = false
            showPage(PageState.IDLE)
            tvIdleHint.setText(R.string.hazard_record_capture_failed)
            refreshInputActions()
        }
    }

    private fun beginAnalysis(image: DeepV2ImagePayload) {
        activeRequestId += 1
        val requestId = activeRequestId
        val jpegBytes = image.jpegBytes.copyOf()
        InspectionWorkflowSession.recordCapture(jpegBytes)
        val ownedImage = image.copy(jpegBytes = jpegBytes)
        showCapturedResultBackground(ownedImage)
        sendImageToAiAr(requestId = requestId, image = ownedImage)
    }

    private fun showCapturedResultBackground(image: DeepV2ImagePayload) {
        check(HazardRecordPresentationPolicy.afterCapture() == HazardRecordPresentation.RESULT_BACKGROUND)
        resultBitmap?.takeIf { !it.isRecycled }?.recycle()
        resultBitmap = BitmapFactory.decodeByteArray(image.jpegBytes, 0, image.jpegBytes.size)
        ivDeepV2ResultImage.setImageBitmap(resultBitmap)
        resultTargets = emptyList()
        resultNavigation = null
        viewDeepV2ResultOverlay.clear()
        viewDeepV2HazardDetail.clear()
        layoutDeepV2SaveDialog.visibility = View.GONE
        showPage(PageState.STRUCTURED_RESULT)
    }

    private fun sendImageToAiAr(requestId: Long, image: DeepV2ImagePayload) {
        streamingInProgress = true
        refreshInputActions()
        activeAnalysisHandle?.cancel()
        deepV2Coordinator.begin(requestId)
        val route = StructuredHazardRequestPolicy.route(
            StructuredHazardSource.HAZARD_RECORD,
            InspectionWorkflowSession.enterpriseInfo?.placeCode,
        ) ?: return
        activeAnalysisHandle = deepV2Client.request(
            requestId = requestId,
            route = route,
            imageBytes = image.jpegBytes,
            callback = object : DeepV2Client.Callback {
                override fun onSuccess(requestId: Long, response: DeepV2Response) {
                    if (!deepV2Coordinator.complete(requestId) || !shouldDeliverAnalysis(requestId)) return
                    activeAnalysisHandle = null
                    streamingInProgress = false
                    val presentation = deepV2Normalizer.normalize(response)
                    if (HazardRecordPresentationPolicy.afterResponse(presentation.hasDisplayableHazards) == HazardRecordPresentation.IDLE) {
                        returnToIdle(showSuccess = false)
                        return
                    }
                    if (HazardRecordPresentationPolicy.shouldPlayHazardAlert(presentation.hasDisplayableHazards)) {
                        OfflineTtsPlayer.play(
                            context = this@HazardRecordActivity,
                            ownerTag = TAG,
                            audioResId = R.raw.hazard_alert,
                        )
                    }
                    val session = StructuredHazardResultSession(
                        source = StructuredHazardSource.HAZARD_RECORD,
                        imagePayload = image,
                        presentation = presentation,
                        requestId = requestId,
                        epoch = requestId,
                    )
                    resultSession = session
                    activeHazardContent = session.toResolvedHazardContent()
                    InspectionWorkflowSession.recordDetection(
                        activeHazardContent?.displayTitle.orEmpty(),
                        activeHazardContent?.descriptionPageText().orEmpty(),
                    )
                    presentStructuredResult(session)
                }

                override fun onFailure(requestId: Long, error: DeepV2ClientError) {
                    if (!deepV2Coordinator.complete(requestId)) return
                    activeAnalysisHandle = null
                    streamingInProgress = false
                    returnToIdle(showSuccess = false)
                    tvIdleHint.text = error.toString().ifBlank { getString(R.string.hazard_record_stream_failed) }
                }
            },
        )
    }

    private fun shouldDeliverAnalysis(requestId: Long): Boolean {
        return pageState == PageState.STRUCTURED_RESULT &&
            streamingInProgress &&
            requestId == activeRequestId &&
            !isFinishing &&
            !isDestroyed
    }

    private fun handleAnalysisSuccess(fullText: String, jpegBytes: ByteArray) {
        streamingInProgress = false
        val resolved = runCatching {
            AiArHazardDetailParser.parse(
                text = fullText,
                jpegBytes = jpegBytes,
                displayTitle = getString(R.string.hazard_record_title),
            )
        }.getOrElse { error ->
            Log.w(TAG, "record detail parse failed", error)
            ResolvedHazardContent(
                source = HazardSource.ONLINE,
                description = "",
                advice = "",
                hidLevel = "",
                hidNum = "",
                lawBasis = "",
                displayTitle = getString(R.string.hazard_record_title),
                jpegBytes = jpegBytes.copyOf(),
                rawDetailText = fullText.trim(),
            )
        }
        activeHazardContent = resolved
        val displayText = formatAnalysisText(resolved, fullText)
        lastStreamText = displayText
        updateAnalysisText(displayText)
        tvAnalysisHint.visibility = View.VISIBLE
        InspectionWorkflowSession.recordDetection(resolved.displayTitle, displayText)
        InspectionWorkflowSession.recordAnalysis(displayText)
        refreshInputActions()
    }

    private fun handleAnalysisFailure(message: String) {
        streamingInProgress = false
        tvAnalysisHint.visibility = View.VISIBLE
        updateAnalysisText(message.ifBlank { getString(R.string.hazard_record_stream_failed) })
        refreshInputActions()
    }

    private fun formatAnalysisText(
        resolved: ResolvedHazardContent,
        fallbackText: String,
    ): String {
        val count = resolved.recordableHazards().size
        val detailText = resolved.descriptionPageText()
            .ifBlank { fallbackText.trim() }
            .ifBlank { getString(R.string.hazard_record_stream_failed) }
        return "分析出${count}条隐患\n\n$detailText"
    }

    private fun handleAnalysisConfirm() {
        if (advanceAnalysisViewportByPage()) {
            return
        }
        if (activeHazardContent?.shouldReturnToIdleWithoutUpload() == true) {
            returnToIdle(showSuccess = false)
            return
        }
        submitLocalHazard()
    }

    private fun presentStructuredResult(session: StructuredHazardResultSession) {
        resultBitmap?.takeIf { !it.isRecycled }?.recycle()
        resultBitmap = BitmapFactory.decodeByteArray(
            session.imagePayload.jpegBytes,
            0,
            session.imagePayload.jpegBytes.size,
        )
        ivDeepV2ResultImage.setImageBitmap(resultBitmap)
        resultTargets = buildList {
            session.presentation.targets.forEach { target -> add(target.labelId to target.hazards) }
            session.presentation.others?.let { others -> add(null to others.hazards) }
        }
        resultNavigation = DeepV2PresentationStateMachine(session.pageCounts())
        viewDeepV2HazardDetail.clear()
        layoutDeepV2SaveDialog.visibility = View.GONE
        showPage(PageState.STRUCTURED_RESULT)
        viewDeepV2ResultOverlay.post {
            val boxes = session.presentation.targets.mapNotNull { target ->
                DeepV2OverlayGeometry.map(
                    bbox = target.bbox,
                    sourceWidth = session.imagePayload.width,
                    sourceHeight = session.imagePayload.height,
                    destinationWidth = viewDeepV2ResultOverlay.width,
                    destinationHeight = viewDeepV2ResultOverlay.height,
                )?.let { rect ->
                    DeepV2OverlayBox(target.labelId, target.label, target.highestLevel, rect)
                }
            }
            viewDeepV2ResultOverlay.setBoxes(boxes)
            viewDeepV2ResultOverlay.setSelectedLabelId(null, animate = false)
        }
        refreshInputActions()
    }

    private fun renderResultTransition(
        previous: DeepV2NavigationState,
        transition: DeepV2Transition,
    ) {
        resultRenderGeneration += 1L
        val renderGeneration = resultRenderGeneration
        when (transition.effect) {
            DeepV2NavigationEffect.SubmitSave -> {
                layoutDeepV2SaveDialog.visibility = View.GONE
                submitLocalHazard()
                return
            }
            DeepV2NavigationEffect.DiscardResult -> {
                returnToIdle(showSuccess = false)
                return
            }
            DeepV2NavigationEffect.None -> Unit
        }
        when (val state = transition.state) {
            DeepV2NavigationState.Defocused -> {
                layoutDeepV2SaveDialog.visibility = View.GONE
                viewDeepV2ResultOverlay.setSelectedLabelId(null, animate = true)
                viewDeepV2HazardDetail.clear()
            }
            is DeepV2NavigationState.Focused -> {
                layoutDeepV2SaveDialog.visibility = View.GONE
                val target = resultTargets.getOrNull(state.targetIndex) ?: return
                val previousLabel = (previous as? DeepV2NavigationState.Focused)
                    ?.let { resultTargets.getOrNull(it.targetIndex)?.first }
                val nextLabel = target.first
                val transitionType = DeepV2ResultInteractionPolicy.focusTransition(previousLabel, nextLabel)
                viewDeepV2HazardDetail.clear()
                val showDetail = Runnable {
                    if (renderGeneration != resultRenderGeneration || resultNavigation?.state != state) return@Runnable
                    val hazard = target.second.getOrNull(state.pageIndex) ?: return@Runnable
                    viewDeepV2HazardDetail.render(
                        HazardDetailDisplayModel.from(hazard),
                        state.pageIndex,
                        target.second.size.coerceAtLeast(1),
                    )
                }
                when (transitionType) {
                    DeepV2FocusTransition.FOCUS_THEN_SHOW_DETAIL,
                    DeepV2FocusTransition.SWITCH_BOX_THEN_SHOW_DETAIL,
                    -> {
                        viewDeepV2ResultOverlay.setSelectedLabelId(nextLabel, animate = true)
                        uiHandler.postDelayed(showDetail, DEEP_V2_BOX_ANIMATION_MS)
                    }
                    DeepV2FocusTransition.DEFOCUS_THEN_SHOW_DETAIL -> {
                        viewDeepV2ResultOverlay.setSelectedLabelId(null, animate = true)
                        uiHandler.postDelayed(showDetail, DEEP_V2_BOX_ANIMATION_MS)
                    }
                    DeepV2FocusTransition.SHOW_DETAIL_IMMEDIATELY -> showDetail.run()
                }
            }
            is DeepV2NavigationState.SaveDialog -> {
                viewDeepV2HazardDetail.clear()
                layoutDeepV2SaveDialog.visibility = View.VISIBLE
                tvDeepV2SaveConfirm.setBackgroundResource(
                    if (state.selected == DeepV2SaveChoice.CONFIRM) R.drawable.glass_card_outline_selected else R.drawable.glass_card_outline,
                )
                tvDeepV2SaveCancel.setBackgroundResource(
                    if (state.selected == DeepV2SaveChoice.CANCEL) R.drawable.glass_card_outline_selected else R.drawable.glass_card_outline,
                )
            }
            DeepV2NavigationState.Submitting -> Unit
        }
        refreshInputActions()
    }

    private fun submitLocalHazard() {
        if (saveSubmitting) return
        val hazardContent = activeHazardContent ?: return
        val enterprisePayload = InspectionWorkflowSession.enterpriseQrPayload
        val jpegBytes = hazardContent.jpegBytes.takeIf { it.isNotEmpty() }
        val uploadItems = LocalHazardUploadItemBuilder.build(hazardContent)
        val failureMessage = when {
            !InspectionFeatureFlags.isEnterpriseInspectionFlowEnabled() -> "企业巡检链路未启用"
            enterprisePayload == null -> "缺少企业巡检信息"
            enterprisePayload.apiBaseUrl.isBlank() -> "缺少上传地址"
            enterprisePayload.authCode.isBlank() -> "缺少授权信息"
            enterprisePayload.objectId.isBlank() -> "缺少对象信息"
            jpegBytes == null -> "隐患图片缺失"
            uploadItems.isEmpty() -> "隐患信息缺失"
            else -> null
        }
        if (failureMessage != null) {
            returnToIdle(showSuccess = false)
            tvIdleHint.text = failureMessage
            return
        }

        val recordKey = buildRecordKey(hazardContent)
        InspectionWorkflowSession.recordSavedHazardAttempt(
            recordKey = recordKey,
            jpegBytes = jpegBytes,
            hazardItems = uploadItems.map { item ->
                InspectionWorkflowSession.SavedHazardItem(
                    hidNum = item.hidNum,
                    hidLevel = item.hidLevel,
                    description = item.descrip,
                    advice = item.advice,
                )
            },
            saveOutcome = InspectionWorkflowSession.SaveOutcome.PENDING,
        )
        saveSubmitting = true
        tvAnalysisHint.visibility = View.GONE
        refreshInputActions()
        localHazardUploadHandle?.cancel()
        localHazardUploadHandle = localHazardPushService.pushLocalHazard(
            baseUrl = enterprisePayload!!.apiBaseUrl,
            authCode = enterprisePayload.authCode,
            objectId = enterprisePayload.objectId,
            userId = enterprisePayload.userId,
            customParam = enterprisePayload.extraField,
            jpegBytes = jpegBytes!!,
            hidDanger = uploadItems,
            callback = object : LocalHazardPushService.Callback {
                override fun onSuccess() {
                    if (isFinishing || isDestroyed) return
                    InspectionWorkflowSession.updateSavedHazardAttemptOutcome(
                        recordKey = recordKey,
                        saveOutcome = InspectionWorkflowSession.SaveOutcome.SUCCESS,
                    )
                    if (pageState == PageState.IDLE) showSuccessToast()
                }

                override fun onFailure(message: String) {
                    if (isFinishing || isDestroyed) return
                    InspectionWorkflowSession.updateSavedHazardAttemptOutcome(
                        recordKey = recordKey,
                        saveOutcome = InspectionWorkflowSession.SaveOutcome.FAILED,
                    )
                    if (pageState == PageState.IDLE) {
                        tvIdleHint.text = message.ifBlank { getString(R.string.ai_inspection_local_save_failed) }
                    }
                }
            },
        )
        check(HazardRecordPresentationPolicy.afterSaveAccepted() == HazardRecordPresentation.IDLE)
        localHazardUploadHandle = null
        returnToIdle(showSuccess = false)
    }

    private fun buildRecordKey(hazardContent: ResolvedHazardContent): String {
        val hazardKey = hazardContent.recordableHazards()
            .joinToString(separator = "||") { hazard ->
                listOf(
                    hazard.displayTitle,
                    hazard.description,
                    hazard.uploadAdvice,
                    hazard.hidNum,
                    hazard.hidLevel,
                    hazard.lawBasis,
                ).joinToString(separator = "|")
            }
        return listOf(
            "RECORD",
            hazardContent.displayTitle,
            hazardKey,
            hazardContent.jpegBytes.contentHashCode().toString(),
        ).joinToString(separator = "|")
    }

    private fun advanceAnalysisViewportByPage(): Boolean {
        val contentView = scrollAnalysis.getChildAt(0) ?: return false
        val maxScrollY = (contentView.height - scrollAnalysis.height).coerceAtLeast(0)
        if (maxScrollY <= scrollAnalysis.scrollY) {
            return false
        }
        val pageHeight = scrollAnalysis.height.takeIf { it > 0 } ?: 1
        val targetScrollY = (scrollAnalysis.scrollY + pageHeight).coerceAtMost(maxScrollY)
        scrollAnalysis.post {
            scrollAnalysis.scrollTo(0, targetScrollY)
        }
        return true
    }

    private fun updateAnalysisText(text: String) {
        tvAnalysisContent.text = text
        scrollAnalysis.post {
            scrollAnalysis.scrollTo(0, scrollAnalysis.scrollY)
        }
    }

    private fun setThumbnail(jpegBytes: ByteArray) {
        val bitmap = decodeSampledBitmap(jpegBytes, THUMBNAIL_TARGET_PX, THUMBNAIL_TARGET_PX) ?: return
        currentThumbnail?.takeIf { !it.isRecycled }?.recycle()
        currentThumbnail = bitmap
        ivThumbnail.setImageBitmap(bitmap)
    }

    private fun decodeSampledBitmap(
        jpegBytes: ByteArray,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = calculateInSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                targetWidth,
                targetHeight,
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

    private fun returnToIdle(showSuccess: Boolean = false) {
        cancelActiveWork()
        activeRequestId += 1
        activeHazardContent = null
        lastStreamText = ""
        captureInProgress = false
        streamingInProgress = false
        saveSubmitting = false
        resultSession = null
        resultTargets = emptyList()
        resultNavigation = null
        viewDeepV2ResultOverlay.clear()
        viewDeepV2HazardDetail.clear()
        layoutDeepV2SaveDialog.visibility = View.GONE
        ivDeepV2ResultImage.setImageDrawable(null)
        resultBitmap?.takeIf { !it.isRecycled }?.recycle()
        resultBitmap = null
        tvIdleHint.setText(R.string.hazard_record_photo_prompt)
        showPage(PageState.IDLE)
        if (showSuccess) {
            showSuccessToast()
        }
    }

    private fun cancelActiveWork() {
        Log.i(
            TAG,
            "cancelActiveWork activeRequestId=$activeRequestId hasAnalysisHandle=${activeAnalysisHandle != null} hasUploadHandle=${localHazardUploadHandle != null} frameOpen=${RokidFrameSource.isFrameStreamOpen()} frameWarm=${RokidFrameSource.isFrameStreamWarm()}",
        )
        activeAnalysisHandle?.cancel()
        activeAnalysisHandle = null
        deepV2Coordinator.cancel()
        localHazardUploadHandle?.cancel()
        localHazardUploadHandle = null
        uiHandler.removeCallbacks(hideSuccessToastRunnable)
    }

    private fun showSuccessToast() {
        tvSuccessToast.visibility = View.VISIBLE
        tvSuccessToast.alpha = 1f
        uiHandler.removeCallbacks(hideSuccessToastRunnable)
        uiHandler.postDelayed(hideSuccessToastRunnable, SUCCESS_TOAST_VISIBLE_MS)
    }

    private fun showPage(state: PageState) {
        pageState = state
        layoutIdle.visibility = if (state == PageState.IDLE) View.VISIBLE else View.GONE
        layoutCountdown.visibility = if (state == PageState.COUNTDOWN) View.VISIBLE else View.GONE
        layoutAnalysis.visibility = if (state == PageState.ANALYSIS) View.VISIBLE else View.GONE
        layoutDeepV2Result.visibility = if (state == PageState.STRUCTURED_RESULT) View.VISIBLE else View.GONE
        if (state == PageState.ANALYSIS) {
            scrollAnalysis.post {
                scrollAnalysis.scrollTo(0, 0)
            }
        }
        refreshInputActions()
    }

    private fun startRealtimeAnalysis() {
        val targetActivity = if (InspectionSession.isInitialized) {
            AiInspectionActivity::class.java
        } else {
            InspectionLoadingActivity::class.java
        }
        Log.i(
            TAG,
            "startRealtimeAnalysis target=${targetActivity.simpleName} frameReady=$frameStreamReady frameOpen=${RokidFrameSource.isFrameStreamOpen()} frameWarm=${RokidFrameSource.isFrameStreamWarm()} captureInProgress=$captureInProgress streamingInProgress=$streamingInProgress saveSubmitting=$saveSubmitting",
        )
        InspectionCameraCoordinator.releaseForNavigation(CameraOwner.HAZARD_RECORD, reason = "hazard_record_realtime_analysis")
        startActivity(Intent(this, targetActivity))
        finish()
    }

    private fun navigateToDeviceGuide() {
        Log.i(
            TAG,
            "navigateToDeviceGuide frameReady=$frameStreamReady frameOpen=${RokidFrameSource.isFrameStreamOpen()} frameWarm=${RokidFrameSource.isFrameStreamWarm()}",
        )
        InspectionCameraCoordinator.releaseForNavigation(CameraOwner.HAZARD_RECORD, reason = "hazard_record_device_guide")
        navigatingToDeviceGuide = true
        startActivity(Intent(this, DeviceGuideActivity::class.java))
        finish()
    }

    companion object {
        private const val TAG = "HazardRecordActivity"
        private const val REQUEST_MEDIA_PERMISSION = 301
        private const val COUNTDOWN_SECONDS = 3
        private const val COUNTDOWN_TICK_MS = 1000L
        private const val STALE_FRAME_THRESHOLD_MS = 1200L
        private const val SELECT_WINDOW_MS = 240L
        private const val SELECT_MAX_FRAMES = 3
        private const val SELECT_POLL_INTERVAL_MS = 80L
        private const val JPEG_QUALITY = 97
        private const val THUMBNAIL_TARGET_PX = 160
        private const val SUCCESS_TOAST_VISIBLE_MS = 1800L
        private const val DEEP_V2_BOX_ANIMATION_MS = 220L
    }
}
