package com.rokid.glass.hiddenrisk

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaActionSound
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
import com.rokid.glass.InspectionEndReportReturnDestination
import com.rokid.glass.InspectionEndReportActivity
import com.rokid.glass.InspectionFeatureFlags
import com.rokid.glass.camera.RokidFrameSource
import com.rokid.glass.component.FunctionMenuView
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator.CameraOwner
import com.rokid.glass.input.UnifiedInputSession
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

    private val uiHandler = Handler(Looper.getMainLooper())
    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private val aiArSseService by lazy { AiArSseService() }
    private val localHazardPushService by lazy { LocalHazardPushService() }
    private val imageExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val shutterSound by lazy { MediaActionSound() }
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

    private var pageState = PageState.IDLE
    private var frameStreamReady = false
    private var frameStreamInitializing = false
    private var cameraSessionGeneration = 0L
    private var mediaPermissionRequested = false
    private var countdownRemaining = 0
    private var captureInProgress = false
    private var streamingInProgress = false
    private var saveSubmitting = false
    private var activeRequestId = 0L
    private var activeAnalysisHandle: AiArSseService.RequestHandle? = null
    private var localHazardUploadHandle: RetryRequestHandle? = null
    private var activeHazardContent: ResolvedHazardContent? = null
    private var lastStreamText = ""
    private var currentThumbnail: Bitmap? = null
    private var batteryReceiver: BroadcastReceiver? = null

    private val hideSuccessToastRunnable = Runnable {
        tvSuccessToast.visibility = View.GONE
    }

    private val statusUpdateRunnable = object : Runnable {
        override fun run() {
            updateStatusBars()
            uiHandler.postDelayed(this, STATUS_UPDATE_DELAY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hazard_record)
        initViews()
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)
        RokidSdkManager.initialize(application)
        RokidSdkManager.addListener(this)
        RokidSdkManager.ensureInitialized()
        ensureFrameStreamReady()
        showPage(PageState.IDLE)
    }

    override fun onResume() {
        super.onResume()
        Log.i(
            TAG,
            "onResume pageState=$pageState frameReady=$frameStreamReady frameInitializing=$frameStreamInitializing frameOpen=${RokidFrameSource.isFrameStreamOpen()} frameWarm=${RokidFrameSource.isFrameStreamWarm()} captureInProgress=$captureInProgress streamingInProgress=$streamingInProgress saveSubmitting=$saveSubmitting",
        )
        ensureFrameStreamReady()
        inputSession.attach()
        refreshInputActions()
        startStatusBarUpdates()
    }

    override fun onPause() {
        Log.i(
            TAG,
            "onPause pageState=$pageState frameReady=$frameStreamReady frameInitializing=$frameStreamInitializing frameOpen=${RokidFrameSource.isFrameStreamOpen()} frameWarm=${RokidFrameSource.isFrameStreamWarm()} captureInProgress=$captureInProgress streamingInProgress=$streamingInProgress saveSubmitting=$saveSubmitting",
        )
        frameStreamInitializing = false
        frameStreamReady = false
        cameraSessionGeneration = 0L
        InspectionCameraCoordinator.release(CameraOwner.HAZARD_RECORD, reason = "hazard_record_on_pause")
        stopStatusBarUpdates()
        inputSession.detach()
        super.onPause()
    }

    override fun onDestroy() {
        Log.i(
            TAG,
            "onDestroy pageState=$pageState frameReady=$frameStreamReady frameInitializing=$frameStreamInitializing frameOpen=${RokidFrameSource.isFrameStreamOpen()} frameWarm=${RokidFrameSource.isFrameStreamWarm()} captureInProgress=$captureInProgress streamingInProgress=$streamingInProgress saveSubmitting=$saveSubmitting",
        )
        cancelActiveWork()
        InspectionCameraCoordinator.release(CameraOwner.HAZARD_RECORD, reason = "hazard_record_on_destroy")
        uiHandler.removeCallbacksAndMessages(null)
        inputSession.release()
        RokidSdkManager.removeListener(this)
        imageExecutor.shutdownNow()
        runCatching { shutterSound.release() }
        currentThumbnail?.takeIf { !it.isRecycled }?.recycle()
        currentThumbnail = null
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
        val menuContent = getString(R.string.hazard_record_function_menu_content)
        functionMenuIdle.setMenu(content = menuContent)
        functionMenuCountdown.setMenu(content = menuContent)
        updateStatusBars()
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
                startActivity(Intent(this, DeviceGuideActivity::class.java))
                finish()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("hazard_record_finish_task"),
                label = "结束任务",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Voice("结束任务", "jie shu ren wu"),
                    UnifiedInputSession.InputTrigger.Voice("结束", "jie shu"),
                ),
                enabled = { pageState == PageState.IDLE },
            ) {
                startActivity(
                    InspectionEndReportActivity.createIntent(
                        this,
                        InspectionEndReportReturnDestination.HAZARD_RECORD_HOME,
                    ),
                )
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Confirm,
                label = "确认",
                triggers = buildConfirmTriggers(),
                enabled = { pageState == PageState.ANALYSIS && !streamingInProgress && !saveSubmitting },
            ) {
                handleAnalysisConfirm()
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
        var requestGeneration = 0L
        requestGeneration = InspectionCameraCoordinator.acquire(
            owner = CameraOwner.HAZARD_RECORD,
            needPreview = false,
        ) { success ->
            if (requestGeneration != InspectionCameraCoordinator.getGeneration()) {
                Log.i(
                    TAG,
                    "ignore stale hazard acquire callback requestGeneration=$requestGeneration currentGeneration=${InspectionCameraCoordinator.getGeneration()} success=$success",
                )
                return@acquire
            }
            uiHandler.post {
                frameStreamInitializing = false
                frameStreamReady = success
                cameraSessionGeneration = requestGeneration
                Log.i(
                    TAG,
                    "ensureFrameStreamReady end success=$success generation=$requestGeneration pageState=$pageState frameReady=$frameStreamReady frameOpen=${RokidFrameSource.isFrameStreamOpen()} frameWarm=${RokidFrameSource.isFrameStreamWarm()}",
                )
                if (!success && pageState == PageState.IDLE) {
                    tvIdleHint.setText(R.string.hazard_record_frame_stream_failed)
                } else if (success && pageState == PageState.IDLE) {
                    tvIdleHint.setText(R.string.hazard_record_photo_prompt)
                }
                refreshInputActions()
            }
        }
        cameraSessionGeneration = requestGeneration
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
                uiHandler.post {
                    captureInProgress = false
                    if (payload == null || payload.jpegBytes.isEmpty()) {
                        showPage(PageState.IDLE)
                        tvIdleHint.setText(R.string.hazard_record_capture_failed)
                        refreshInputActions()
                        return@post
                    }
                    beginAnalysis(payload)
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

    private fun beginAnalysis(payload: InspectionFrameCaptureService.CapturedFramePayload) {
        activeRequestId += 1
        val requestId = activeRequestId
        val jpegBytes = payload.jpegBytes.copyOf()
        activeHazardContent = ResolvedHazardContent(
            source = HazardSource.ONLINE,
            description = "",
            advice = "",
            hidLevel = "",
            hidNum = "",
            lawBasis = "",
            displayTitle = getString(R.string.hazard_record_title),
            jpegBytes = jpegBytes,
        )
        InspectionWorkflowSession.recordCapture(jpegBytes)
        setThumbnail(jpegBytes)
        lastStreamText = ""
        tvAnalysisContent.setText(R.string.hazard_record_analyzing)
        tvAnalysisHint.visibility = View.GONE
        showPage(PageState.ANALYSIS)
        sendImageToAiAr(requestId, jpegBytes)
    }

    private fun sendImageToAiAr(requestId: Long, jpegBytes: ByteArray) {
        val base64Image = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        streamingInProgress = true
        refreshInputActions()
        activeAnalysisHandle?.cancel()
        activeAnalysisHandle = aiArSseService.fetchHazardDetails(
            base64Image = base64Image,
            onChunk = { partialText ->
                uiHandler.post {
                    if (!shouldDeliverAnalysis(requestId)) return@post
                    lastStreamText = partialText
                    updateAnalysisText(partialText.ifBlank { getString(R.string.hazard_record_analyzing) })
                }
            },
            callback = object : AiArSseService.DetailCallback {
                override fun onOpened(handle: AiArSseService.RequestHandle) = Unit

                override fun onSuccess(handle: AiArSseService.RequestHandle, fullText: String) {
                    uiHandler.post {
                        if (activeAnalysisHandle != handle || requestId != activeRequestId) {
                            return@post
                        }
                        activeAnalysisHandle = null
                        handleAnalysisSuccess(fullText, jpegBytes)
                    }
                }

                override fun onFailure(handle: AiArSseService.RequestHandle, message: String) {
                    uiHandler.post {
                        if (activeAnalysisHandle == handle) {
                            activeAnalysisHandle = null
                        }
                        handleAnalysisFailure(message)
                    }
                }
            },
        )
    }

    private fun shouldDeliverAnalysis(requestId: Long): Boolean {
        return pageState == PageState.ANALYSIS &&
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
        val count = resolved.hazardCount().takeIf { it > 0 } ?: 1
        val detailText = resolved.descriptionPageText()
            .ifBlank { fallbackText.trim() }
            .ifBlank { getString(R.string.hazard_record_stream_failed) }
        return "分析出${count}条隐患\n\n$detailText"
    }

    private fun handleAnalysisConfirm() {
        if (advanceAnalysisViewportByPage()) {
            return
        }
        submitLocalHazard()
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
            updateAnalysisText(failureMessage)
            tvAnalysisHint.visibility = View.VISIBLE
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
                    localHazardUploadHandle = null
                    saveSubmitting = false
                    InspectionWorkflowSession.updateSavedHazardAttemptOutcome(
                        recordKey = recordKey,
                        saveOutcome = InspectionWorkflowSession.SaveOutcome.SUCCESS,
                    )
                    returnToIdle(showSuccess = true)
                }

                override fun onFailure(message: String) {
                    if (isFinishing || isDestroyed) return
                    localHazardUploadHandle = null
                    saveSubmitting = false
                    InspectionWorkflowSession.updateSavedHazardAttemptOutcome(
                        recordKey = recordKey,
                        saveOutcome = InspectionWorkflowSession.SaveOutcome.FAILED,
                    )
                    updateAnalysisText(message.ifBlank { getString(R.string.ai_inspection_local_save_failed) })
                    tvAnalysisHint.visibility = View.VISIBLE
                    refreshInputActions()
                }
            },
        )
    }

    private fun buildRecordKey(hazardContent: ResolvedHazardContent): String {
        val hazardKey = hazardContent.resolvedHazards()
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
        startActivity(Intent(this, targetActivity))
        finish()
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
        statusBarIdle.updateTime()
        statusBarCountdown.updateTime()
        statusBarAnalysis.updateTime()
        updateBatteryLevel()
    }

    private fun updateBatteryLevel(intent: Intent? = null) {
        val batteryStatus = intent ?: registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryStatus?.let { batteryIntent ->
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level != -1 && scale != -1) {
                val batteryPct = (level * 100 / scale.toFloat()).toInt()
                statusBarIdle.setBatteryPercent(batteryPct)
                statusBarCountdown.setBatteryPercent(batteryPct)
                statusBarAnalysis.setBatteryPercent(batteryPct)
            }
        }
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
        private const val STATUS_UPDATE_DELAY_MS = 1000L
    }
}
