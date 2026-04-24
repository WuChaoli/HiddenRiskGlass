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
import com.rokid.glass.camera.RokidFrameSource
import com.rokid.glass.component.GlassStatusBar
import com.rokid.glass.component.RokidCameraPreviewView
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.hiddenrisk.GlassKeyEvent
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

    private val scannerDelegate: Lazy<BarcodeScanner> = lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

    private val scanner: BarcodeScanner
        get() = scannerDelegate.value

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isFrameStreamReady = false
    private var isProcessingFrame = false
    private var completed = false
    private var debugSnapshotMode = false

    @Volatile
    private var latestPreviewFrame: ByteArray? = null

    @Volatile
    private var latestPreviewWidth = 0

    @Volatile
    private var latestPreviewHeight = 0

    private val scanRunnable = object : Runnable {
        override fun run() {
            if (completed || !isFrameStreamReady || isProcessingFrame) {
                mainHandler.postDelayed(this, SCAN_INTERVAL_MS)
                return
            }
            if (!refreshLatestFrameFromSdk()) {
                mainHandler.postDelayed(this, SCAN_INTERVAL_MS)
                return
            }
            val frame = latestPreviewFrame
            val width = latestPreviewWidth
            val height = latestPreviewHeight
            if (frame == null || width <= 0 || height <= 0) {
                mainHandler.postDelayed(this, SCAN_INTERVAL_MS)
                return
            }
            isProcessingFrame = true
            scanner.process(InputImage.fromByteArray(frame, width, height, 0, ImageFormat.NV21))
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
    }

    override fun onResume() {
        super.onResume()
        if (completed) return
        if (debugSnapshotMode) return
        if (skipScanIfEnterpriseQrCached()) return
        if (hasRequiredPermissions()) {
            startCameraPipeline()
            startScanLoop()
        } else {
            requestPermissions()
        }
    }

    override fun onPause() {
        if (debugSnapshotMode) {
            super.onPause()
            return
        }
        stopCameraPipeline()
        super.onPause()
    }

    override fun onDestroy() {
        if (!debugSnapshotMode) {
            mainHandler.removeCallbacksAndMessages(null)
            if (scannerDelegate.isInitialized()) {
                scanner.close()
            }
            stopCameraPipeline()
        }
        super.onDestroy()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        return when (keyEvent) {
            GlassKeyEvent.KEYCODE_CLICK -> {
                if (!completed) {
                    tvStatus.setText(R.string.enterprise_qr_waiting)
                    startScanLoop()
                }
                true
            }
            else -> super.onGlassKeyEvent(keyEvent)
        }
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
            return
        }
        startCameraPipeline()
        startScanLoop()
    }

    private fun startCameraPipeline() {
        if (!hasRequiredPermissions()) {
            return
        }
        cameraPreviewView.startPreview { success ->
            runOnUiThread {
                if (!success) {
                    tvScanHint.text = getString(R.string.enterprise_qr_camera_error)
                }
            }
        }
        RokidFrameSource.startFrameStream { success ->
            runOnUiThread {
                isFrameStreamReady = success
                if (!success) {
                    tvScanHint.text = getString(R.string.enterprise_qr_camera_error)
                    return@runOnUiThread
                }
                tvStatus.setText(R.string.enterprise_qr_waiting)
                startScanLoop()
            }
        }
    }

    private fun stopCameraPipeline() {
        mainHandler.removeCallbacks(scanRunnable)
        cameraPreviewView.stopPreview()
        RokidFrameSource.stopFrameStream()
        isFrameStreamReady = false
        isProcessingFrame = false
        latestPreviewFrame = null
        latestPreviewWidth = 0
        latestPreviewHeight = 0
    }

    private fun refreshLatestFrameFromSdk(): Boolean {
        val frame = RokidFrameSource.copyLatestCroppedFrame() ?: return false
        latestPreviewFrame = frame.data
        latestPreviewWidth = frame.width
        latestPreviewHeight = frame.height
        return true
    }

    private fun startScanLoop() {
        mainHandler.removeCallbacks(scanRunnable)
        mainHandler.post(scanRunnable)
    }

    private fun handleScanResult(barcodes: List<Barcode>) {
        if (completed) {
            return
        }
        val rawValue = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue ?: return
        if (!InspectionWorkflowSession.updateEnterpriseFromQr(rawValue)) {
            tvStatus.setText(R.string.enterprise_qr_invalid)
            return
        }
        completed = true
        tvStatus.visibility = View.GONE

        cameraPreviewView.visibility = View.INVISIBLE
        viewfinder.visibility = View.INVISIBLE
        tvScanHint.visibility = View.GONE
        infoCard.visibility = View.GONE

        scanFrame.background = null
        resultContent.visibility = View.VISIBLE
        bottomHints.visibility = View.VISIBLE

        stopCameraPipeline()
        mainHandler.postDelayed({
            startActivity(Intent(this, EnterpriseInfoActivity::class.java))
            finish()
        }, RESULT_STAY_MS)
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
        startActivity(Intent(this, EnterpriseInfoActivity::class.java))
        finish()
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
            bottomHints.visibility = View.VISIBLE
        } else {
            tvScanHint.visibility = View.VISIBLE
            infoCard.visibility = View.VISIBLE
            resultContent.visibility = View.GONE
            bottomHints.visibility = View.GONE
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 6001
        private const val SCAN_INTERVAL_MS = 800L
        private const val RESULT_STAY_MS = 1000L
    }
}
