package com.rokid.glass

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.rokid.glass.camera.RokidCameraRecoveryController
import com.rokid.glass.camera.RokidFrameSource
import com.rokid.glass.config.InspectionConfigRepository
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.component.GlassStatusBarUpdater
import com.rokid.glass.component.RokidCameraPreviewView
import com.rokid.glass.hiddenrisk.AiInspectionActivity
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator.CameraOwner
import com.rokid.glass.input.GlassesWearStateMachine
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glesse.R

class EnterpriseQrScanActivity : BaseGlassActivity() {

    override val wearSleepEnabled: Boolean
        get() = true

    private lateinit var cameraPreviewView: RokidCameraPreviewView
    private lateinit var tvStatus: TextView
    private lateinit var resultOverlay: FrameLayout
    private lateinit var tvResultMessage: TextView

    private lateinit var scanFrame: View
    private lateinit var viewfinder: View
    private lateinit var tvScanHint: TextView
    private lateinit var resultContent: LinearLayout
    private lateinit var infoCard: LinearLayout
    private lateinit var bottomHints: LinearLayout
    private lateinit var statusBar: GlassStatusBar
    private val statusBarUpdater by lazy { GlassStatusBarUpdater(this) }
    private val inputSession by lazy { UnifiedInputSession(this, TAG) }

    private val scannerDelegate: Lazy<BarcodeScanner> = lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

    private val scanner: BarcodeScanner
        get() = scannerDelegate.value

    private val objectMessageService by lazy { EnterpriseObjectMessageService() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isFrameStreamReady = false
    private var isProcessingFrame = false
    private var completed = false
    private var debugSnapshotMode = false
    private var destroyed = false
    private var cameraSessionGeneration = 0L
    private var cameraRequestToken: Long = -1L
    private var objectMessageRequest: EnterpriseObjectMessageService.RequestHandle? = null
    private var isActivityResumed = false
    private var wearSnapshot: GlassesWearStateMachine.Snapshot? = null
    private var wearRecoveryInProgress = false

    private val cameraRecoveryController by lazy {
        RokidCameraRecoveryController(
            mode = RokidCameraRecoveryController.RecoveryMode.PREVIEW_HEALTH,
            previewView = cameraPreviewView,
            callback = object : RokidCameraRecoveryController.Callback {
                override fun onRecoveryStarted(
                    issue: RokidCameraRecoveryController.RecoveryIssue,
                    attempt: Int,
                    maxAttempts: Int,
                ) {
                    Log.w(TAG, "camera recovery start issue=$issue attempt=$attempt/$maxAttempts")
                    isFrameStreamReady = false
                    isProcessingFrame = false
                    mainHandler.removeCallbacks(scanRunnable)
                    cameraPreviewView.visibility = View.INVISIBLE
                }

                override fun onRecoverySucceeded() {
                    Log.i(TAG, "camera recovery success")
                    isFrameStreamReady = true
                    isProcessingFrame = false
                    showScanState(tvStatus.text ?: getString(R.string.enterprise_qr_waiting))
                    if (shouldEnableCameraRecovery()) {
                        startScanLoop()
                    }
                }

                override fun onRecoveryAbandoned(issue: RokidCameraRecoveryController.RecoveryIssue) {
                    Log.e(TAG, "camera recovery abandoned issue=$issue")
                    isFrameStreamReady = false
                    isProcessingFrame = false
                    mainHandler.removeCallbacks(scanRunnable)
                    showScanState(getString(R.string.enterprise_qr_camera_recovery_failed))
                }
            },
            restartHandler = { issue, onReady ->
                cameraSessionGeneration = InspectionCameraCoordinator.restart(
                    owner = CameraOwner.ENTERPRISE_QR_SCAN,
                    reason = issue.name,
                    needPreview = true,
                    previewView = cameraPreviewView,
                    onReady = onReady,
                )
            },
        )
    }

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (completed || isWearStateInteractionBlocked()) {
                return
            }
            if (!isFrameStreamReady || isProcessingFrame) {
                mainHandler.postDelayed(this, SCAN_INTERVAL_MS)
                return
            }
            val frame = RokidFrameSource.copyLatestValidatedFrame(SCAN_FRAME_TARGET_SIZE)
            if (frame == null) {
                mainHandler.postDelayed(this, SCAN_INTERVAL_MS)
                return
            }
            if (frame.width <= 0 || frame.height <= 0) {
                mainHandler.postDelayed(this, SCAN_INTERVAL_MS)
                return
            }
            isProcessingFrame = true
            scanner.process(
                InputImage.fromByteArray(frame.data, frame.width, frame.height, 0, ImageFormat.NV21),
            )
                .addOnSuccessListener { barcodes -> handleScanResult(barcodes) }
                .addOnFailureListener {
                    tvStatus.setText(R.string.enterprise_qr_invalid)
                }
                .addOnCompleteListener {
                    isProcessingFrame = false
                    if (!completed && !isWearStateInteractionBlocked()) {
                        mainHandler.postDelayed(this, SCAN_INTERVAL_MS)
                    }
                }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!InspectionFeatureFlags.isEnterpriseInspectionFlowEnabled()) {
            navigateDirectlyToInspection()
            return
        }
        setContentView(R.layout.activity_enterprise_qr_scan)

        debugSnapshotMode = intent.getBooleanExtra("debug_snapshot", false)
        val forceScan = intent.getBooleanExtra(EXTRA_FORCE_SCAN, false)
        cameraPreviewView = findViewById(R.id.cameraPreviewView)
        tvStatus = findViewById(R.id.tvStatus)
        resultOverlay = findViewById(R.id.resultOverlay)
        tvResultMessage = findViewById(R.id.tvResultMessage)

        scanFrame = findViewById(R.id.scanFrame)
        viewfinder = findViewById(R.id.viewfinder)
        tvScanHint = findViewById(R.id.tvScanHint)
        resultContent = findViewById(R.id.resultContent)
        infoCard = findViewById(R.id.infoCard)
        bottomHints = findViewById(R.id.bottomHints)
        statusBar = findViewById(R.id.statusBar)
        statusBarUpdater.refreshNow(statusBar)

        if (!debugSnapshotMode && !forceScan && skipScanIfEnterpriseQrCached()) {
            return
        }

        if (debugSnapshotMode) {
            applyDebugSnapshotState()
        }
        hideBottomHints()
    }

    private fun navigateDirectlyToInspection() {
        startActivity(Intent(this, AiInspectionActivity::class.java))
        finish()
    }

    override fun onResume() {
        isActivityResumed = true
        super.onResume()
        inputSession.attach()
        refreshInputActions()
        statusBarUpdater.start(statusBar)
        if (completed) return
        if (debugSnapshotMode) return
        if (!intent.getBooleanExtra(EXTRA_FORCE_SCAN, false) && skipScanIfEnterpriseQrCached()) return
        cameraRecoveryController.start()
        if (hasRequiredPermissions()) {
            startCameraPipeline(resetRecoveryAttempts = true)
        } else {
            requestPermissions()
        }
    }

    override fun onPause() {
        isActivityResumed = false
        wearRecoveryInProgress = false
        statusBarUpdater.stop()
        inputSession.detach()
        if (debugSnapshotMode) {
            super.onPause()
            return
        }
        if (completed) {
            completed = false
            showScanState(getString(R.string.enterprise_qr_waiting))
        }
        pauseCameraPipeline(reason = "on_pause")
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        statusBarUpdater.stop()
        inputSession.release()
        if (!debugSnapshotMode) {
            mainHandler.removeCallbacksAndMessages(null)
            if (scannerDelegate.isInitialized()) {
                scanner.close()
            }
            // 销毁时完整释放相机资源，终止进行中的重试
            mainHandler.removeCallbacks(scanRunnable)
            objectMessageRequest?.cancel()
            objectMessageRequest = null
            cameraRecoveryController.stop()
            isFrameStreamReady = false
            isProcessingFrame = false
            cameraSessionGeneration = 0L
            InspectionCameraCoordinator.releaseForNavigation(CameraOwner.ENTERPRISE_QR_SCAN, reason = "on_destroy")
        }
        super.onDestroy()
    }

    override fun shouldEnableWearSleepNow(): Boolean {
        return super.shouldEnableWearSleepNow() && shouldMonitorWearState()
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

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        return inputSession.dispatchTouch(keyEvent) || super.onGlassKeyEvent(keyEvent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_PERMISSIONS) return
        if (completed) return
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (!granted) {
            tvStatus.setText(R.string.enterprise_qr_permission_denied)
            refreshInputActions()
            return
        }
        startCameraPipeline(resetRecoveryAttempts = true)
    }

    private fun startCameraPipeline(resetRecoveryAttempts: Boolean = false) {
        startCameraPipeline(
            statusMessage = getString(R.string.enterprise_qr_waiting),
            resetRecoveryAttempts = resetRecoveryAttempts,
        )
    }

    private fun startCameraPipeline(
        statusMessage: CharSequence,
        resetRecoveryAttempts: Boolean = false,
    ) {
        if (!hasRequiredPermissions()) {
            return
        }
        if (resetRecoveryAttempts) {
            cameraRecoveryController.resetRecoveryAttempts()
        }
        cameraRecoveryController.start()
        showScanState(statusMessage)
        refreshInputActions()
        isFrameStreamReady = false
        isProcessingFrame = false
        cameraRecoveryController.setRecoveryEnabled(shouldEnableCameraRecovery())
        Log.i(
            TAG,
            "startCameraPipeline completed=$completed status=$statusMessage recovery=${shouldEnableCameraRecovery()}",
        )
        cameraRequestToken = InspectionCameraCoordinator.acquireForActivity(
            owner = CameraOwner.ENTERPRISE_QR_SCAN,
            needPreview = true,
            previewView = cameraPreviewView,
            enableRecovery = shouldEnableCameraRecovery(),
        ) { success ->
            // acquireForActivity 内部已通过 requestToken 处理过期回调，success 直接可用
            isFrameStreamReady = success
            Log.i(
                TAG,
                "camera acquire result success=$success requestToken=$cameraRequestToken owner=${InspectionCameraCoordinator.getOwner()} state=${InspectionCameraCoordinator.getState()}",
            )
            if (!success) {
                tvScanHint.text = getString(R.string.enterprise_qr_camera_error)
                return@acquireForActivity
            }
            tvStatus.text = statusMessage
            startScanLoop()
            if (wearRecoveryInProgress) {
                reportWearRecoveryReady()
            }
        }
        Log.i(TAG, "startCameraPipeline requestToken=$cameraRequestToken owner=${InspectionCameraCoordinator.getOwner()}")
    }

    private fun stopCameraPipeline(reason: String = "unspecified") {
        Log.i(
            TAG,
            "stopCameraPipeline reason=$reason owner=${CameraOwner.ENTERPRISE_QR_SCAN} generation=$cameraSessionGeneration completed=$completed frameReady=$isFrameStreamReady processing=$isProcessingFrame",
        )
        mainHandler.removeCallbacks(scanRunnable)
        objectMessageRequest?.cancel()
        objectMessageRequest = null
        cameraRecoveryController.stop()
        isFrameStreamReady = false
        isProcessingFrame = false
        cameraSessionGeneration = 0L
        InspectionCameraCoordinator.releaseForNavigation(CameraOwner.ENTERPRISE_QR_SCAN, reason = reason)
    }

    private fun pauseCameraPipeline(reason: String = "unspecified") {
        Log.i(
            TAG,
            "pauseCameraPipeline reason=$reason owner=${CameraOwner.ENTERPRISE_QR_SCAN} generation=$cameraSessionGeneration completed=$completed frameReady=$isFrameStreamReady processing=$isProcessingFrame",
        )
        mainHandler.removeCallbacks(scanRunnable)
        objectMessageRequest?.cancel()
        objectMessageRequest = null
        cameraRecoveryController.stop()
        isFrameStreamReady = false
        isProcessingFrame = false
        cameraSessionGeneration = 0L
        InspectionCameraCoordinator.pauseTemporarily(CameraOwner.ENTERPRISE_QR_SCAN, reason = reason)
    }

    private fun startScanLoop() {
        if (completed) {
            return
        }
        Log.i(
            TAG,
            "scanLoop start completed=$completed frameReady=$isFrameStreamReady generation=$cameraSessionGeneration",
        )
        mainHandler.removeCallbacks(scanRunnable)
        mainHandler.post(scanRunnable)
    }

    private fun handleScanResult(barcodes: List<Barcode>) {
        if (completed || isWearStateInteractionBlocked()) {
            return
        }
        val rawValue = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue ?: return
        Log.i(TAG, "enterprise qr raw=${sanitizeQrForLog(rawValue)}")
        if (!InspectionWorkflowSession.updateEnterpriseFromQr(rawValue)) {
            Log.w(TAG, "enterprise qr parse rejected raw=${sanitizeQrForLog(rawValue)}")
            tvStatus.setText(R.string.enterprise_qr_invalid)
            return
        }
        completed = true
        updateWearSleepEligibility(false)
        enterObjectFetchLoadingState()
        refreshInputActions()
        mainHandler.removeCallbacks(scanRunnable)
        isProcessingFrame = false
        cameraRecoveryController.setRecoveryEnabled(false)
        val payload = InspectionWorkflowSession.enterpriseQrPayload
        if (payload == null) {
            Log.e(TAG, "enterprise object fetch aborted: payload missing after qr parse")
            restoreScanStateForRetry(getString(R.string.enterprise_qr_object_fetch_failed))
            return
        }
        Log.i(
            TAG,
            "enterprise object fetch start baseUrl=${payload.apiBaseUrl} objectId=${maskTokenForLog(payload.objectId)} authCode=${maskTokenForLog(payload.authCode)}",
        )
        var objectFetchRequestId = ""
        objectMessageRequest?.cancel()
        objectMessageRequest = objectMessageService.fetchObjectMessage(
            baseUrl = payload.apiBaseUrl,
            authCode = payload.authCode,
            objectId = payload.objectId,
            callback = object : EnterpriseObjectMessageService.ObjectMessageCallback {
                override fun onSuccess(data: EnterpriseObjectMessageService.ObjectMessageData) {
                    if (destroyed || isFinishing) return
                    objectMessageRequest = null
                    val hazardHistory = data.hidDanger.orEmpty()
                        .mapNotNull { it.descrip?.trim()?.takeIf(String::isNotEmpty) }
Log.i(
                         TAG,
                         "enterprise object fetch success requestId=$objectFetchRequestId objectNameBlank=${data.objectName.isNullOrBlank()} areaNameBlank=${data.areaName.isNullOrBlank()} domainBlank=${data.domain.isNullOrBlank()} tagsBlank=${data.tags.isNullOrBlank()} riskLevelBlank=${data.riskLevel.isNullOrBlank()} placeCode=${data.placeCode} lastInspectionDate=${data.lastInspectionDate} hazardCount=${data.hidDanger.orEmpty().size} hazardWithDescriptionCount=${hazardHistory.size}",
                     )
InspectionWorkflowSession.updateEnterpriseObjectInfo(
                         companyName = data.objectName,
                         region = data.areaName,
                         category = data.domain,
                         riskTags = data.tags,
                         riskLevel = data.riskLevel,
                         hazardHistory = hazardHistory,
                         placeCode = data.placeCode,
                         lastInspectionDate = data.lastInspectionDate,
                     )
                    Log.i(TAG, "enterprise object fetch navigate requestId=$objectFetchRequestId target=EnterpriseInfoActivity")
                    navigateToEnterpriseInfo()
                }

                override fun onFailure(message: String) {
                    if (destroyed || isFinishing) return
                    objectMessageRequest = null
                    Log.w(
                        TAG,
                        "enterprise object fetch failure requestId=$objectFetchRequestId message=$message completed=$completed frameReady=$isFrameStreamReady",
                    )
                    restoreScanStateForRetry(message)
                }
            },
        )
        objectFetchRequestId = objectMessageRequest?.requestId.orEmpty()
        Log.i(TAG, "enterprise object fetch queued requestId=$objectFetchRequestId")
    }

    private fun skipScanIfEnterpriseQrCached(): Boolean {
        if (InspectionWorkflowSession.enterpriseQrPayload == null || InspectionWorkflowSession.enterpriseInfo == null) {
            return false
        }
        completed = true
        navigateToEnterpriseInfo()
        return true
    }

    private fun navigateToEnterpriseInfo() {
        stopCameraPipeline(reason = "navigate_to_enterprise_info")
        startActivity(Intent(this, EnterpriseInfoActivity::class.java))
        finish()
    }

    private fun enterObjectFetchLoadingState() {
        tvStatus.visibility = View.VISIBLE
        tvStatus.setText(R.string.enterprise_qr_fetching_object)
        cameraPreviewView.visibility = View.INVISIBLE
        viewfinder.visibility = View.INVISIBLE
        tvScanHint.visibility = View.GONE
        infoCard.visibility = View.GONE
        scanFrame.background = null
        resultContent.visibility = View.VISIBLE
        hideBottomHints()
    }

    private fun restoreScanStateForRetry(message: String) {
        completed = false
        updateWearSleepEligibility(shouldMonitorWearState())
        showScanState(message)
        refreshInputActions()
        if (hasRequiredPermissions()) {
            startCameraPipeline(
                statusMessage = message,
                resetRecoveryAttempts = true,
            )
        }
    }

    private fun showScanState(statusMessage: CharSequence) {
        cameraPreviewView.visibility = View.VISIBLE
        viewfinder.visibility = View.VISIBLE
        tvScanHint.visibility = View.VISIBLE
        infoCard.visibility = View.VISIBLE
        scanFrame.setBackgroundResource(R.drawable.glass_scan_frame)
        resultContent.visibility = View.GONE
        hideBottomHints()
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = statusMessage
    }

    private fun showWearSleep() {
        if (!shouldMonitorWearState()) {
            updateWearSleepEligibility(false)
            return
        }
        wearRecoveryInProgress = false
        pauseCameraPipeline(reason = "enterprise_qr_wear_sleep")
    }

    private fun beginWearRecovery() {
        if (!isActivityResumed || !shouldMonitorWearState()) {
            updateWearSleepEligibility(shouldMonitorWearState())
            return
        }
        wearRecoveryInProgress = true
        if (hasRequiredPermissions()) {
            startCameraPipeline(
                statusMessage = getString(R.string.enterprise_qr_waiting),
                resetRecoveryAttempts = true,
            )
        } else {
            requestPermissions()
        }
    }

    private fun finishWearRecovery() {
        wearRecoveryInProgress = false
        if (shouldMonitorWearState()) {
            showScanState(getString(R.string.enterprise_qr_waiting))
        }
    }

    private fun shouldMonitorWearState(): Boolean {
        return !debugSnapshotMode && !completed && objectMessageRequest == null && !destroyed
    }

    private fun shouldEnableCameraRecovery(): Boolean {
        return InspectionConfigRepository.get().enterpriseScan.enableCameraRecovery &&
            !debugSnapshotMode &&
            !completed &&
            objectMessageRequest == null &&
            (!isWearStateInteractionBlocked() || wearRecoveryInProgress)
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

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions(), REQUEST_CODE_PERMISSIONS)
    }

    private fun applyDebugSnapshotState() {
        cameraPreviewView.visibility = View.INVISIBLE
        viewfinder.visibility = View.INVISIBLE
        val success = intent.getStringExtra("debug_state") == "success"
        if (success) {
            scanFrame.background = null
            tvScanHint.visibility = View.GONE
            infoCard.visibility = View.GONE
            resultContent.visibility = View.VISIBLE
            hideBottomHints()
        } else {
            tvScanHint.visibility = View.VISIBLE
            infoCard.visibility = View.VISIBLE
            resultContent.visibility = View.GONE
            hideBottomHints()
        }
        refreshInputActions()
    }

    private fun refreshInputActions() {
        inputSession.updateActions(buildInputActions())
    }

    private fun buildInputActions(): List<UnifiedInputSession.InputActionSpec> {
        return listOf(
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Confirm,
                label = getString(R.string.ai_inspection_input_label_confirm),
                triggers = buildConfirmTriggers(),
                enabled = { isPrimaryActionEnabled() },
            ) {
                handlePrimaryAction()
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Cancel,
                label = getString(R.string.ai_inspection_input_label_return),
                triggers = buildReturnTriggers(),
            ) {
                returnToPreviousScreen()
            },
        )
    }

    private fun isPrimaryActionEnabled(): Boolean {
        if (debugSnapshotMode) {
            return intent.getStringExtra("debug_state") == "success"
        }
        return !completed && objectMessageRequest == null && !isWearStateInteractionBlocked()
    }

    private fun handlePrimaryAction() {
        if (debugSnapshotMode) {
            if (intent.getStringExtra("debug_state") == "success") {
                navigateToEnterpriseInfo()
            }
            return
        }
        if (completed || objectMessageRequest != null || isWearStateInteractionBlocked()) {
            return
        }
        startCameraPipeline(
            statusMessage = getString(R.string.enterprise_qr_waiting),
            resetRecoveryAttempts = true,
        )
    }

    private fun buildConfirmTriggers(): List<UnifiedInputSession.InputTrigger> {
        return listOf(
            UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK),
            voiceTrigger(R.string.ai_inspection_voice_confirm, "que ren"),
            voiceTrigger(R.string.ai_inspection_voice_confirm_alias, "que ding"),
            voiceTrigger(R.string.ai_inspection_voice_continue_alias, "ji xu"),
        )
    }

    private fun buildReturnTriggers(): List<UnifiedInputSession.InputTrigger> {
        return listOf(
            UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK),
            UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK),
            voiceTrigger(R.string.ai_inspection_voice_return, "fan hui"),
            voiceTrigger(R.string.ai_inspection_voice_cancel_alias, "qu xiao"),
        )
    }

    private fun voiceTrigger(
        textRes: Int,
        pinyin: String,
    ): UnifiedInputSession.InputTrigger {
        return UnifiedInputSession.InputTrigger.Voice(getString(textRes), pinyin)
    }

    private fun hideBottomHints() {
        bottomHints.visibility = View.GONE
    }

    private fun returnToPreviousScreen() {
        mainHandler.removeCallbacks(scanRunnable)
        stopCameraPipeline(reason = "return_to_previous_screen")
        finish()
    }

    private fun exitAppDirectly() {
        mainHandler.removeCallbacks(scanRunnable)
        stopCameraPipeline(reason = "exit_app_directly")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAffinity()
            finishAndRemoveTask()
        } else {
            finishAffinity()
            finish()
        }
    }


    companion object {
        private const val TAG = "EnterpriseQrScan"
        const val EXTRA_FORCE_SCAN = "force_scan"
        private const val REQUEST_CODE_PERMISSIONS = 6001
        private val SCAN_INTERVAL_MS: Long
            get() = InspectionConfigRepository.get().enterpriseScan.scanIntervalMs

        private val SCAN_FRAME_TARGET_SIZE: Int
            get() = InspectionConfigRepository.get().enterpriseScan.scanFrameTargetSize

        private const val QR_LOG_VISIBLE_PREFIX_LENGTH = 120

        private fun sanitizeQrForLog(rawValue: String): String {
            val normalized = rawValue.replace('\n', ' ').replace('\r', ' ')
            return if (normalized.length <= QR_LOG_VISIBLE_PREFIX_LENGTH) {
                normalized
            } else {
                normalized.take(QR_LOG_VISIBLE_PREFIX_LENGTH) + "..."
            }
        }

        private fun maskTokenForLog(value: String?): String {
            val normalized = value?.trim().orEmpty()
            if (normalized.isBlank()) {
                return "(blank)"
            }
            return if (normalized.length <= 6) {
                "***$normalized"
            } else {
                "***${normalized.takeLast(6)}"
            }
        }
    }
}
