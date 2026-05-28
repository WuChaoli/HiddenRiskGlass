package com.rokid.glass.hiddenrisk

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rokid.glass.camera.RokidFrameSource
import com.rokid.glass.camera.SharedCameraViewportPolicy
import com.rokid.glass.component.RokidCameraPreviewView
import com.rokid.glass.component.RokidDemoNv21PreviewView
import com.rokid.glass.component.RokidDemoSurfacePreviewView
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator.CameraOwner
import com.rokid.glass.utils.BitmapUtils
import com.rokid.glass.utils.dpToPx
import com.rokid.glesse.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 原始相机输出诊断页。
 * Surface 与 NV21 都只做等比适配，用于隔离业务 ROI 与 SDK 输出问题。
 */
class RawCameraPreviewDebugActivity : BaseGlassActivity(), RokidSdkManager.Listener {

    private data class DisplayFrame(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val timestamp: Long,
    )

    private data class SurfaceCropCandidate(
        val label: String,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    )

    private enum class DisplayMode {
        SDK_DEMO_COMPARE,
        SURFACE_DEMO_RAW,
        SURFACE_RAW,
        SURFACE_VALIDATED_CENTER,
        SURFACE_BOTTOM_SQUARE,
        NV21_RAW,
        NV21_SQUARE_BASELINE,
        SURFACE_SQUARE_DIRECT,
        SURFACE_SQUARE_FITTED,
        SURFACE_SQUARE_TRANSPOSED,
    }

    private lateinit var previewViewport: FrameLayout
    private lateinit var surfacePreview: RokidCameraPreviewView
    private lateinit var demoSurfacePreview: RokidDemoSurfacePreviewView
    private lateinit var demoCompareLayout: View
    private lateinit var compareSurfacePreview: RokidDemoSurfacePreviewView
    private lateinit var compareNv21Preview: RokidDemoNv21PreviewView
    private lateinit var nv21Preview: ImageView
    private lateinit var diagnostics: TextView
    private val uiHandler = Handler(Looper.getMainLooper())
    private val bitmapExecutor = Executors.newSingleThreadExecutor()
    private val nv21ConversionPending = AtomicBoolean(false)

    private var resumed = false
    private var cameraAcquiring = false
    private var cameraReady = false
    private var mode = DisplayMode.SDK_DEMO_COMPARE
    private var compareSurfaceResult: Boolean? = null
    private var compareNv21Result: Boolean? = null
    private var lastNv21Timestamp = 0L
    private var candidateCropSummary = "-"
    private var lastCandidateCropSummary: String? = null
    private var diagnosticsLogged = false
    private var displayedBitmap: Bitmap? = null

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!resumed) return
            refreshDiagnostics()
            updateSquareCandidate()
            if (mode == DisplayMode.NV21_RAW || mode == DisplayMode.NV21_SQUARE_BASELINE) {
                renderLatestNv21()
            }
            uiHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = resolveInitialMode()
        setContentView(R.layout.activity_raw_camera_preview_debug)
        previewViewport = findViewById(R.id.layoutRawPreviewViewport)
        surfacePreview = findViewById(R.id.viewRawSurfacePreview)
        demoSurfacePreview = findViewById(R.id.viewDemoSurfacePreview)
        demoCompareLayout = findViewById(R.id.layoutSdkDemoCompare)
        compareSurfacePreview = findViewById(R.id.viewCompareSurfacePreview)
        compareNv21Preview = findViewById(R.id.viewCompareNv21Preview)
        nv21Preview = findViewById(R.id.imageRawNv21Preview)
        diagnostics = findViewById(R.id.textRawCameraDiagnostics)
        surfacePreview.setPreviewRenderMode(RokidCameraPreviewView.PreviewRenderMode.RAW_ASPECT_FIT)
        demoSurfacePreview.setCenterSquareCropEnabled(false)
        compareSurfacePreview.setCenterSquareCropEnabled(true)
        RokidSdkManager.initialize(application as Application)
        RokidSdkManager.addListener(this)
        RokidSdkManager.ensureInitialized()
    }

    private fun resolveInitialMode(): DisplayMode {
        return when (intent?.getStringExtra(EXTRA_MODE)) {
            MODE_SDK_DEMO_COMPARE -> DisplayMode.SDK_DEMO_COMPARE
            MODE_SURFACE_DEMO_RAW -> DisplayMode.SURFACE_DEMO_RAW
            MODE_SURFACE_RAW -> DisplayMode.SURFACE_RAW
            MODE_SURFACE_VALIDATED_CENTER -> DisplayMode.SURFACE_VALIDATED_CENTER
            MODE_SURFACE_BOTTOM_SQUARE -> DisplayMode.SURFACE_BOTTOM_SQUARE
            else -> DisplayMode.SDK_DEMO_COMPARE
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        applyDisplayMode()
        startCameraWhenReady()
        uiHandler.post(refreshRunnable)
    }

    override fun onPause() {
        resumed = false
        uiHandler.removeCallbacks(refreshRunnable)
        cameraAcquiring = false
        cameraReady = false
        demoSurfacePreview.stopDemoPreview()
        compareSurfacePreview.stopDemoPreview()
        compareNv21Preview.stopDemoPreview()
        InspectionCameraCoordinator.pause(CameraOwner.RAW_CAMERA_DEBUG, reason = "raw_debug_on_pause")
        super.onPause()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        RokidSdkManager.removeListener(this)
        demoSurfacePreview.stopDemoPreview()
        compareSurfacePreview.stopDemoPreview()
        compareNv21Preview.stopDemoPreview()
        InspectionCameraCoordinator.pause(CameraOwner.RAW_CAMERA_DEBUG, reason = "raw_debug_on_destroy")
        bitmapExecutor.shutdownNow()
        displayedBitmap?.recycle()
        displayedBitmap = null
        super.onDestroy()
    }

    override fun onSdkStateChanged(state: RokidSdkManager.SdkState) {
        if (state == RokidSdkManager.SdkState.READY) {
            startCameraWhenReady()
        } else if (state == RokidSdkManager.SdkState.FAILED) {
            diagnostics.setText(R.string.raw_camera_debug_failed)
        }
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        if (keyEvent == GlassKeyEvent.KEYCODE_CLICK) {
            mode = when (mode) {
                DisplayMode.SDK_DEMO_COMPARE -> DisplayMode.SURFACE_DEMO_RAW
                DisplayMode.SURFACE_DEMO_RAW -> DisplayMode.SURFACE_RAW
                DisplayMode.SURFACE_RAW -> DisplayMode.SURFACE_VALIDATED_CENTER
                DisplayMode.SURFACE_VALIDATED_CENTER -> DisplayMode.SURFACE_BOTTOM_SQUARE
                DisplayMode.SURFACE_BOTTOM_SQUARE -> DisplayMode.NV21_RAW
                DisplayMode.NV21_RAW -> DisplayMode.NV21_SQUARE_BASELINE
                DisplayMode.NV21_SQUARE_BASELINE -> DisplayMode.SURFACE_SQUARE_DIRECT
                DisplayMode.SURFACE_SQUARE_DIRECT -> DisplayMode.SURFACE_SQUARE_FITTED
                DisplayMode.SURFACE_SQUARE_FITTED -> DisplayMode.SURFACE_SQUARE_TRANSPOSED
                DisplayMode.SURFACE_SQUARE_TRANSPOSED -> DisplayMode.SDK_DEMO_COMPARE
            }
            applyDisplayMode()
            refreshDiagnostics()
            return true
        }
        return super.onGlassKeyEvent(keyEvent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCameraWhenReady()
        } else if (requestCode == REQUEST_CAMERA_PERMISSION) {
            diagnostics.setText(R.string.ai_inspection_loading_missing_camera_permission)
        }
    }

    private fun startCameraWhenReady() {
        if (!resumed || cameraReady || cameraAcquiring) return
        if (mode == DisplayMode.SDK_DEMO_COMPARE) {
            startDemoCompareWhenReady()
            return
        }
        if (mode == DisplayMode.SURFACE_DEMO_RAW) {
            startDemoSurfaceWhenReady()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
            return
        }
        if (RokidSdkManager.state != RokidSdkManager.SdkState.READY) return
        cameraAcquiring = true
        diagnostics.setText(R.string.raw_camera_debug_starting)
        InspectionCameraCoordinator.acquire(
            owner = CameraOwner.RAW_CAMERA_DEBUG,
            needPreview = true,
            previewView = surfacePreview,
        ) { success ->
            cameraAcquiring = false
            cameraReady = success
            if (!success) {
                diagnostics.setText(R.string.raw_camera_debug_failed)
            }
            Log.i(TAG, "raw debug camera ready success=$success")
            if (success && !diagnosticsLogged) {
                diagnosticsLogged = true
                Log.i(TAG, "shared camera diagnostics ${RokidFrameSource.diagnosticsSnapshot()}")
            }
        }
    }

    private fun startDemoSurfaceWhenReady() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
            return
        }
        if (RokidSdkManager.state != RokidSdkManager.SdkState.READY) return
        cameraAcquiring = true
        diagnostics.setText(R.string.raw_camera_debug_starting)
        demoSurfacePreview.startDemoPreview { success ->
            uiHandler.post {
                cameraAcquiring = false
                cameraReady = success
                if (!success) {
                    diagnostics.setText(R.string.raw_camera_debug_failed)
                }
                Log.i(TAG, "demo surface camera ready success=$success")
            }
        }
    }

    private fun startDemoCompareWhenReady() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
            return
        }
        if (RokidSdkManager.state != RokidSdkManager.SdkState.READY) return
        cameraAcquiring = true
        compareSurfaceResult = null
        compareNv21Result = null
        diagnostics.setText(R.string.raw_camera_debug_starting)
        compareSurfacePreview.startDemoPreview { success ->
            uiHandler.post {
                compareSurfaceResult = success
                updateDemoCompareReadyState()
            }
        }
        compareNv21Preview.startDemoPreview { success ->
            uiHandler.post {
                compareNv21Result = success
                updateDemoCompareReadyState()
            }
        }
    }

    private fun updateDemoCompareReadyState() {
        if (mode != DisplayMode.SDK_DEMO_COMPARE) return
        if (compareSurfaceResult == false || compareNv21Result == false) {
            cameraAcquiring = false
            cameraReady = false
            diagnostics.setText(R.string.raw_camera_debug_failed)
            Log.e(TAG, "demo compare camera failed surface=$compareSurfaceResult nv21=$compareNv21Result")
            return
        }
        if (compareSurfaceResult == true && compareNv21Result == true) {
            cameraAcquiring = false
            cameraReady = true
            Log.i(TAG, "demo compare camera ready surface=true nv21=true")
            return
        }
        refreshDiagnostics()
    }

    private fun applyDisplayMode() {
        stopInactivePreviewForMode()
        val squareMode = mode == DisplayMode.NV21_SQUARE_BASELINE ||
            mode == DisplayMode.SURFACE_VALIDATED_CENTER ||
            mode == DisplayMode.SURFACE_BOTTOM_SQUARE ||
            mode.isSurfaceSquareCandidate()
        previewViewport.layoutParams = if (mode == DisplayMode.SURFACE_DEMO_RAW) {
            FrameLayout.LayoutParams(DEMO_SURFACE_WIDTH_PX, DEMO_SURFACE_HEIGHT_PX, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        } else {
            val size = if (squareMode) {
                dpToPx(SQUARE_VIEWPORT_SIZE_DP.toFloat()).toInt()
            } else {
                FrameLayout.LayoutParams.MATCH_PARENT
            }
            FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        }
        previewViewport.visibility = if (mode == DisplayMode.SDK_DEMO_COMPARE) View.GONE else View.VISIBLE
        demoCompareLayout.visibility = if (mode == DisplayMode.SDK_DEMO_COMPARE) View.VISIBLE else View.GONE
        demoSurfacePreview.visibility = if (mode == DisplayMode.SURFACE_DEMO_RAW) {
            View.VISIBLE
        } else {
            View.GONE
        }
        surfacePreview.visibility = if (mode == DisplayMode.SURFACE_DEMO_RAW) {
            View.GONE
        } else {
            View.VISIBLE
        }
        nv21Preview.visibility = if (
            mode == DisplayMode.NV21_RAW || mode == DisplayMode.NV21_SQUARE_BASELINE
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        surfacePreview.setPreviewRenderMode(
            when (mode) {
                DisplayMode.SURFACE_VALIDATED_CENTER -> RokidCameraPreviewView.PreviewRenderMode.AUTO_SURFACE_SQUARE
                DisplayMode.SURFACE_BOTTOM_SQUARE -> RokidCameraPreviewView.PreviewRenderMode.SURFACE_BOTTOM_SQUARE
                DisplayMode.SURFACE_SQUARE_DIRECT,
                DisplayMode.SURFACE_SQUARE_FITTED,
                DisplayMode.SURFACE_SQUARE_TRANSPOSED,
                -> RokidCameraPreviewView.PreviewRenderMode.DEBUG_TEXTURE_CROP_FILL
                else -> RokidCameraPreviewView.PreviewRenderMode.RAW_ASPECT_FIT
            },
        )
        if (mode.isSurfaceSquareCandidate()) {
            updateSquareCandidate(forceApply = true)
        }
        lastNv21Timestamp = 0L
        compareSurfaceResult = null
        compareNv21Result = null
        cameraReady = false
        cameraAcquiring = false
        startCameraWhenReady()
    }

    private fun stopInactivePreviewForMode() {
        if (mode == DisplayMode.SDK_DEMO_COMPARE) {
            demoSurfacePreview.stopDemoPreview()
            InspectionCameraCoordinator.pause(CameraOwner.RAW_CAMERA_DEBUG, reason = "raw_debug_switch_to_demo_compare")
        } else if (mode == DisplayMode.SURFACE_DEMO_RAW) {
            compareSurfacePreview.stopDemoPreview()
            compareNv21Preview.stopDemoPreview()
            InspectionCameraCoordinator.pause(CameraOwner.RAW_CAMERA_DEBUG, reason = "raw_debug_switch_to_demo_surface")
        } else {
            demoSurfacePreview.stopDemoPreview()
            compareSurfacePreview.stopDemoPreview()
            compareNv21Preview.stopDemoPreview()
        }
    }

    private fun renderLatestNv21() {
        val frame = if (mode == DisplayMode.NV21_SQUARE_BASELINE) {
            RokidFrameSource.copyLatestValidatedSquareFrame()?.let {
                DisplayFrame(it.data, it.width, it.height, it.timestamp)
            }
        } else {
            RokidFrameSource.copyLatestRawFrame()?.let {
                DisplayFrame(it.data, it.width, it.height, it.timestamp)
            }
        } ?: return
        if (frame.timestamp == lastNv21Timestamp || !nv21ConversionPending.compareAndSet(false, true)) return
        lastNv21Timestamp = frame.timestamp
        bitmapExecutor.execute {
            val bitmap = BitmapUtils.nv21ToBitmap(frame.data, frame.width, frame.height, jpegQuality = 80)
            uiHandler.post {
                nv21ConversionPending.set(false)
                if (
                    bitmap == null ||
                    !resumed ||
                    (mode != DisplayMode.NV21_RAW && mode != DisplayMode.NV21_SQUARE_BASELINE)
                ) {
                    bitmap?.recycle()
                    return@post
                }
                val previous = displayedBitmap
                displayedBitmap = bitmap
                nv21Preview.setImageBitmap(bitmap)
                previous?.recycle()
            }
        }
    }

    private fun updateSquareCandidate(forceApply: Boolean = false) {
        val frameSize = RokidFrameSource.getLatestFrameSize() ?: return
        val crop = SharedCameraViewportPolicy.calculateValidatedNv21SquareCropRect(
            frameSize.width,
            frameSize.height,
        )
        if (crop.width() <= 0 || crop.height() <= 0) return
        val candidate = buildSurfaceCropCandidate(crop, frameSize.width, frameSize.height)
        val left = candidate.left
        val top = candidate.top
        val width = candidate.width
        val height = candidate.height
        candidateCropSummary = "Rect(${crop.left},${crop.top},${crop.right},${crop.bottom}) ${candidate.label} -> [$left,$top,$width,$height]"
        if (candidateCropSummary != lastCandidateCropSummary || forceApply) {
            lastCandidateCropSummary = candidateCropSummary
            Log.i(TAG, "square candidate nv21=${frameSize.width}x${frameSize.height} crop=$candidateCropSummary")
            surfacePreview.setDebugTextureCrop(left, top, width, height)
        }
    }

    private fun buildSurfaceCropCandidate(
        crop: android.graphics.Rect,
        frameWidth: Int,
        frameHeight: Int,
    ): SurfaceCropCandidate {
        val directLeft = crop.left.toFloat() / frameWidth.toFloat()
        val directTop = crop.top.toFloat() / frameHeight.toFloat()
        val directWidth = crop.width().toFloat() / frameWidth.toFloat()
        val directHeight = crop.height().toFloat() / frameHeight.toFloat()
        return when (mode) {
            DisplayMode.SURFACE_SQUARE_FITTED -> SurfaceCropCandidate(
                label = "fitted",
                left = directLeft,
                top = (1f - directWidth) / 2f,
                width = directWidth,
                height = directWidth,
            )

            DisplayMode.SURFACE_SQUARE_TRANSPOSED -> SurfaceCropCandidate(
                label = "transposed",
                left = directTop,
                top = directLeft,
                width = directHeight,
                height = directWidth,
            )

            else -> SurfaceCropCandidate(
                label = "direct",
                left = directLeft,
                top = directTop,
                width = directWidth,
                height = directHeight,
            )
        }
    }

    private fun refreshDiagnostics() {
        val surfaceWidth = RokidFrameSource.getSurfaceCameraWidth()
        val surfaceHeight = RokidFrameSource.getSurfaceCameraHeight()
        val nv21Size = RokidFrameSource.getLatestFrameSize()
        val cameraDiagnostics = RokidFrameSource.diagnosticsSnapshot()
        val matrix = RokidFrameSource.getSurfaceTransformMatrix()
        diagnostics.text = buildString {
            append("Mode: ")
            append(mode.name)
            if (mode == DisplayMode.SDK_DEMO_COMPARE) {
                append("\n")
                append(compareSurfacePreview.diagnosticsText())
                append("\n")
                append(compareNv21Preview.diagnosticsText())
                return@buildString
            }
            if (mode == DisplayMode.SURFACE_DEMO_RAW) {
                append('\n')
                append(demoSurfacePreview.diagnosticsText())
                return@buildString
            }
            append("\nView: ")
            append(surfacePreview.width)
            append('x')
            append(surfacePreview.height)
            append("  Surface: ")
            append(surfaceWidth)
            append('x')
            append(surfaceHeight)
            append("\nNV21: ")
            append(nv21Size?.let { "${it.width}x${it.height}" } ?: "-")
            append("  Zoom ratio: ")
            append(RokidFrameSource.getAppliedPreviewZoomRatio())
            append("\nActive: NV21=")
            append(cameraDiagnostics.nv21Active)
            append(" Surface=")
            append(cameraDiagnostics.surfaceActive)
            append("\nRequested: ")
            append("${cameraDiagnostics.requestedWidth}x${cameraDiagnostics.requestedHeight}@${cameraDiagnostics.requestedFps}")
            append(" EIS=")
            append(cameraDiagnostics.requestedEis)
            append(" zoomLevel=")
            append(cameraDiagnostics.requestedZoomLevel)
            append("\nApplied NV21: fps=")
            append(cameraDiagnostics.nv21AppliedFps ?: "-")
            append(" EIS=")
            append(cameraDiagnostics.nv21Eis ?: "-")
            append(" zoom=")
            append(cameraDiagnostics.nv21ZoomLevel ?: "-")
            append("\nApplied Surface: fps=")
            append(cameraDiagnostics.surfaceAppliedFps ?: "-")
            append(" EIS=")
            append(cameraDiagnostics.surfaceEis ?: "-")
            append(" zoom=")
            append(cameraDiagnostics.surfaceZoomLevel ?: "-")
            append("\nSupported: ")
            append(cameraDiagnostics.supportedPreviewSizes.joinToString().ifBlank { "-" })
            append("\nMatrix: ")
            append(matrixSummary(matrix))
            if (mode == DisplayMode.NV21_SQUARE_BASELINE || mode.isSurfaceSquareCandidate()) {
                append("\nSquare: ")
                append(candidateCropSummary)
            }
        }
    }

    private fun DisplayMode.isSurfaceSquareCandidate(): Boolean {
        return this == DisplayMode.SURFACE_SQUARE_DIRECT ||
            this == DisplayMode.SURFACE_SQUARE_FITTED ||
            this == DisplayMode.SURFACE_SQUARE_TRANSPOSED
    }

    private fun matrixSummary(matrix: FloatArray): String {
        if (matrix.size < 16) return "invalid"
        return "[${matrix[0]}, ${matrix[1]}, ${matrix[4]}, ${matrix[5]}, ${matrix[12]}, ${matrix[13]}]"
    }

    companion object {
        private const val TAG = "RawCameraPreviewDebug"
        private const val EXTRA_MODE = "mode"
        private const val MODE_SDK_DEMO_COMPARE = "sdk_demo_compare"
        private const val MODE_SURFACE_DEMO_RAW = "surface_demo_raw"
        private const val MODE_SURFACE_RAW = "surface_raw"
        private const val MODE_SURFACE_VALIDATED_CENTER = "surface_validated_center"
        private const val MODE_SURFACE_BOTTOM_SQUARE = "surface_bottom_square"
        private const val REFRESH_INTERVAL_MS = 300L
        private const val REQUEST_CAMERA_PERMISSION = 2011
        private const val SQUARE_VIEWPORT_SIZE_DP = 220
        private const val DEMO_SURFACE_WIDTH_PX = 480
        private const val DEMO_SURFACE_HEIGHT_PX = 320
    }
}
