package com.rokid.glass

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.rokid.glass.camera.CameraTestManager
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.hiddenrisk.GlassKeyEvent
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glesse.R

class EnterpriseQrScanActivity : BaseGlassActivity() {

    private lateinit var textureView: TextureView
    private lateinit var tvStatus: TextView
    private lateinit var resultOverlay: FrameLayout
    private lateinit var tvResultMessage: TextView
    private lateinit var cameraManager: CameraTestManager

    private val scanner: BarcodeScanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isCameraReady = false
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
            if (completed || !isCameraReady || isProcessingFrame || !textureView.isAvailable) {
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

        cameraManager = CameraTestManager(this)
        debugSnapshotMode = intent.getBooleanExtra("debug_snapshot", false)
        cameraManager.setPreviewFrameCallback { data, width, height ->
            latestPreviewFrame = data
            latestPreviewWidth = width
            latestPreviewHeight = height
        }
        textureView = findViewById(R.id.textureView)
        tvStatus = findViewById(R.id.tvStatus)
        resultOverlay = findViewById(R.id.resultOverlay)
        tvResultMessage = findViewById(R.id.tvResultMessage)
        if (debugSnapshotMode) {
            applyDebugSnapshotState()
            return
        }
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                initCamera(surface, width, height)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                isCameraReady = false
                cameraManager.release()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    override fun onResume() {
        super.onResume()
        if (debugSnapshotMode) return
        if (hasRequiredPermissions()) {
            if (textureView.isAvailable) {
                initCamera(textureView.surfaceTexture, textureView.width, textureView.height)
            }
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
        mainHandler.removeCallbacks(scanRunnable)
        cameraManager.release()
        isCameraReady = false
        isProcessingFrame = false
        latestPreviewFrame = null
        latestPreviewWidth = 0
        latestPreviewHeight = 0
        super.onPause()
    }

    override fun onDestroy() {
        if (debugSnapshotMode) {
            super.onDestroy()
            return
        }
        mainHandler.removeCallbacksAndMessages(null)
        scanner.close()
        cameraManager.release()
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
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (!granted) {
            tvStatus.setText(R.string.enterprise_qr_permission_denied)
            return
        }
        if (textureView.isAvailable) {
            initCamera(textureView.surfaceTexture, textureView.width, textureView.height)
        }
        startScanLoop()
    }

    private fun initCamera(surface: SurfaceTexture?, width: Int, height: Int) {
        if (surface == null || !hasRequiredPermissions() || isCameraReady) {
            return
        }
        cameraManager.initialize(surface, width, height) { success ->
            runOnUiThread {
                isCameraReady = success
                tvStatus.setText(
                    if (success) R.string.enterprise_qr_waiting else R.string.enterprise_qr_camera_error,
                )
                if (success) {
                    startScanLoop()
                }
            }
        }
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
        completed = true
        tvStatus.setText(R.string.enterprise_qr_success)
        InspectionWorkflowSession.updateEnterpriseFromQr(rawValue)
        resultOverlay.visibility = android.view.View.VISIBLE
        tvResultMessage.setText(R.string.enterprise_qr_success)
        mainHandler.postDelayed({
            startActivity(Intent(this, EnterpriseInfoActivity::class.java))
            finish()
        }, RESULT_STAY_MS)
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
        textureView.visibility = android.view.View.INVISIBLE
        val success = intent.getStringExtra("debug_state") == "success"
        tvStatus.text = if (success) {
            getString(R.string.enterprise_qr_success)
        } else {
            getString(R.string.enterprise_qr_waiting)
        }
        resultOverlay.visibility = if (success) android.view.View.VISIBLE else android.view.View.GONE
        tvResultMessage.text = getString(R.string.enterprise_qr_success)
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 6001
        private const val SCAN_INTERVAL_MS = 800L
        private const val RESULT_STAY_MS = 1000L
    }
}
