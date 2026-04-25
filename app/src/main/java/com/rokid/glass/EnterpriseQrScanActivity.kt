package com.rokid.glass

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.os.BatteryManager
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
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.component.RokidCameraPreviewView
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glesse.R

class EnterpriseQrScanActivity : BaseGlassActivity() {

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
    private var objectMessageRequest: EnterpriseObjectMessageService.RequestHandle? = null

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
        )
    }

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (completed || !isFrameStreamReady || isProcessingFrame) {
                mainHandler.postDelayed(this, SCAN_INTERVAL_MS)
                return
            }
            val frame = RokidFrameSource.copyLatestScanFrame(SCAN_FRAME_TARGET_SIZE)
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
                    if (!completed) {
                        mainHandler.postDelayed(this, SCAN_INTERVAL_MS)
                    }
                }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_enterprise_qr_scan)

        debugSnapshotMode = intent.getBooleanExtra("debug_snapshot", false)
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
        updateBatteryLevel()

        if (!debugSnapshotMode && skipScanIfEnterpriseQrCached()) {
            return
        }

        if (debugSnapshotMode) {
            applyDebugSnapshotState()
        }
        hideBottomHints()
    }

    override fun onResume() {
        super.onResume()
        inputSession.attach()
        refreshInputActions()
        if (completed) return
        if (debugSnapshotMode) return
        if (skipScanIfEnterpriseQrCached()) return
        if (hasRequiredPermissions()) {
            startCameraPipeline(resetRecoveryAttempts = true)
        } else {
            requestPermissions()
        }
    }

    override fun onPause() {
        inputSession.detach()
        if (debugSnapshotMode) {
            super.onPause()
            return
        }
        objectMessageRequest?.cancel()
        objectMessageRequest = null
        if (completed) {
            completed = false
            showScanState(getString(R.string.enterprise_qr_waiting))
        }
        stopCameraPipeline()
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        inputSession.release()
        if (!debugSnapshotMode) {
            mainHandler.removeCallbacksAndMessages(null)
            objectMessageRequest?.cancel()
            objectMessageRequest = null
            if (scannerDelegate.isInitialized()) {
                scanner.close()
            }
            stopCameraPipeline()
        }
        super.onDestroy()
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
        Log.i(TAG, "startCameraPipeline begin completed=$completed status=$statusMessage")
        showScanState(statusMessage)
        refreshInputActions()
        isFrameStreamReady = false
        isProcessingFrame = false
        cameraRecoveryController.setRecoveryEnabled(shouldEnableCameraRecovery())
        cameraRecoveryController.startOrReuse { success ->
            runOnUiThread {
                isFrameStreamReady = success
                Log.i(TAG, "startCameraPipeline frameStreamReady=$success")
                if (!success) {
                    tvScanHint.text = getString(R.string.enterprise_qr_camera_error)
                    return@runOnUiThread
                }
                tvStatus.text = statusMessage
                startScanLoop()
            }
        }
    }

    private fun stopCameraPipeline() {
        mainHandler.removeCallbacks(scanRunnable)
        cameraRecoveryController.stop()
        isFrameStreamReady = false
        isProcessingFrame = false
    }

    private fun startScanLoop() {
        if (completed) {
            return
        }
        mainHandler.removeCallbacks(scanRunnable)
        mainHandler.post(scanRunnable)
    }

    private fun handleScanResult(barcodes: List<Barcode>) {
        if (completed) {
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
                        "enterprise object fetch success requestId=$objectFetchRequestId objectNameBlank=${data.objectName.isNullOrBlank()} areaNameBlank=${data.areaName.isNullOrBlank()} domainBlank=${data.domain.isNullOrBlank()} tagsBlank=${data.tags.isNullOrBlank()} riskLevelBlank=${data.riskLevel.isNullOrBlank()} hazardCount=${data.hidDanger.orEmpty().size} hazardWithDescriptionCount=${hazardHistory.size}",
                    )
                    InspectionWorkflowSession.updateEnterpriseObjectInfo(
                        companyName = data.objectName,
                        region = data.areaName,
                        category = data.domain,
                        riskTags = data.tags,
                        riskLevel = data.riskLevel,
                        hazardHistory = hazardHistory,
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
        stopCameraPipeline()
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

    private fun shouldEnableCameraRecovery(): Boolean {
        return !debugSnapshotMode && !completed && objectMessageRequest == null
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

    /**
     * 获取当前电池电量并更新电池图标填充
     */
    private fun updateBatteryLevel() {
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryStatus?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level != -1 && scale != -1) {
                val batteryPct = (level * 100 / scale.toFloat()).toInt()
                statusBar.setBatteryPercent(batteryPct)
            }
        }
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
                exitAppDirectly()
            },
        )
    }

    private fun isPrimaryActionEnabled(): Boolean {
        if (debugSnapshotMode) {
            return intent.getStringExtra("debug_state") == "success"
        }
        return !completed && objectMessageRequest == null
    }

    private fun handlePrimaryAction() {
        if (debugSnapshotMode) {
            if (intent.getStringExtra("debug_state") == "success") {
                navigateToEnterpriseInfo()
            }
            return
        }
        if (completed || objectMessageRequest != null) {
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

    private fun exitAppDirectly() {
        objectMessageRequest?.cancel()
        objectMessageRequest = null
        mainHandler.removeCallbacks(scanRunnable)
        stopCameraPipeline()
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
        private const val REQUEST_CODE_PERMISSIONS = 6001
        private const val SCAN_INTERVAL_MS = 800L
        private const val SCAN_FRAME_TARGET_SIZE = 1080
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
