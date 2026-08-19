package com.rokid.glass.hiddenrisk

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.PixelCopy
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
import okhttp3.Call
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 原始相机输出诊断页。
 * Surface 与 NV21 都只做等比适配，用于隔离业务 ROI 与 SDK 输出问题。
 */
open class RawCameraPreviewDebugActivity : BaseGlassActivity(), RokidSdkManager.Listener {

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
        ALIGNMENT_CALIBRATION,
        DISTANCE_ALIGNMENT,
        INVERSE_DISTANCE_ALIGNMENT,
    }

    private lateinit var previewViewport: FrameLayout
    private lateinit var surfacePreview: RokidCameraPreviewView
    private lateinit var demoSurfacePreview: RokidDemoSurfacePreviewView
    private lateinit var demoCompareLayout: View
    private lateinit var compareSurfacePreview: RokidDemoSurfacePreviewView
    private lateinit var compareNv21Preview: RokidDemoNv21PreviewView
    private lateinit var nv21Preview: ImageView
    private lateinit var diagnostics: TextView
    private lateinit var hint: TextView
    private lateinit var alignmentDetectionOverlay: AlignmentDetectionOverlayView
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
    private var dominantEye = DominantEye.RIGHT
    private var calibrationState = AlignmentCalibrationState()
    private var distanceAlignmentState = DistanceAlignmentState()
    private var inverseDistanceAlignmentState = InverseDistanceAlignmentState()
    private var detectionOverlayAlignmentState = DetectionOverlayAlignmentState()
    private val alignmentDetectionClient = AlignmentAutoDetectionClient()
    private val inferenceImageSize = AlignmentInferenceImageSize.fromWidth(DEFAULT_INFERENCE_IMAGE_WIDTH)
    private var activeDetectionCall: Call? = null
    private var activeDetectionRequestId = 0L
    private var nextDetectionRequestId = 0L
    private var detectionRequestInFlight = false
    private var lastDetectionStartedMs = 0L
    private var detectionStatus = "等待相机"

    private val detectionLoopRunnable = Runnable { beginAlignmentDetectionCycle() }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!resumed) return
            refreshDiagnostics()
            if (mode.isSurfaceSquareCandidate()) {
                updateSquareCandidate()
            }
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
        hint = findViewById(R.id.textRawCameraHint)
        alignmentDetectionOverlay = findViewById(R.id.viewAlignmentDetectionOverlay)
        dominantEye = parseDominantEye(intent?.getStringExtra(EXTRA_DOMINANT_EYE))
        if (mode == DisplayMode.ALIGNMENT_CALIBRATION) {
            calibrationState = detectionOverlayAlignmentState.calibrationState()
        } else if (mode == DisplayMode.DISTANCE_ALIGNMENT) {
            distanceAlignmentState = loadDistanceAlignmentState(dominantEye)
            calibrationState = distanceAlignmentState.calibrationState()
        } else if (mode == DisplayMode.INVERSE_DISTANCE_ALIGNMENT) {
            inverseDistanceAlignmentState = loadInverseDistanceAlignmentState()
            calibrationState = inverseDistanceAlignmentState.calibrationState()
        } else {
            calibrationState = resolveCalibrationState(dominantEye)
        }
        surfacePreview.setPreviewRenderMode(RokidCameraPreviewView.PreviewRenderMode.RAW_ASPECT_FIT)
        demoSurfacePreview.setCenterSquareCropEnabled(false)
        if (mode.isAlignmentPreviewMode()) {
            demoSurfacePreview.setPreviewConfig(
                width = ALIGNMENT_CAMERA_WIDTH,
                height = ALIGNMENT_CAMERA_HEIGHT,
                targetFps = ALIGNMENT_CAMERA_FPS,
                zoomLevel = ALIGNMENT_CAMERA_ZOOM_LEVEL,
            )
        }
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
            MODE_ALIGNMENT_CALIBRATION -> DisplayMode.ALIGNMENT_CALIBRATION
            MODE_DISTANCE_ALIGNMENT -> DisplayMode.DISTANCE_ALIGNMENT
            MODE_INVERSE_DISTANCE_ALIGNMENT -> DisplayMode.INVERSE_DISTANCE_ALIGNMENT
            else -> DisplayMode.SDK_DEMO_COMPARE
        }
    }

    private fun resolveCalibrationState(eye: DominantEye): AlignmentCalibrationState {
        val defaults = AlignmentCalibrationPreset.forEye(eye)
        return defaults.copy(
            scale = numberExtra(EXTRA_SCALE)?.coerceAtLeast(AlignmentCalibrationState.MIN_SCALE) ?: defaults.scale,
            offsetX = numberExtra(EXTRA_OFFSET_X) ?: defaults.offsetX,
            offsetY = numberExtra(EXTRA_OFFSET_Y) ?: defaults.offsetY,
            alpha = (numberExtra(EXTRA_ALPHA) ?: defaults.alpha).coerceIn(0f, 1f),
            translationStep = numberExtra(EXTRA_TRANSLATION_STEP)?.takeIf { it > 0f }
                ?: defaults.translationStep,
            scaleStep = numberExtra(EXTRA_SCALE_STEP)?.takeIf { it > 0f } ?: defaults.scaleStep,
        )
    }

    private fun loadDistanceAlignmentState(eye: DominantEye): DistanceAlignmentState {
        val preferences = getSharedPreferences(DISTANCE_ALIGNMENT_PREFERENCES, MODE_PRIVATE)
        val defaults = AlignmentCalibrationPreset.forEye(eye)
        val offsets = DistanceAlignmentState.DEFAULT_DISTANCES.mapIndexed { index, _ ->
            DistanceAlignmentOffset(
                offsetX = preferences.getFloat("${eye.name}_$index-x", defaults.offsetX),
                offsetY = preferences.getFloat("${eye.name}_$index-y", defaults.offsetY),
            )
        }
        return DistanceAlignmentState(
            selectedDistanceIndex = preferences.getInt("${eye.name}-distance-index", 0)
                .coerceIn(DistanceAlignmentState.DEFAULT_DISTANCES.indices),
            offsets = offsets,
        )
    }

    private fun saveDistanceAlignmentState(state: DistanceAlignmentState) {
        getSharedPreferences(DISTANCE_ALIGNMENT_PREFERENCES, MODE_PRIVATE).edit().apply {
            putInt("${dominantEye.name}-distance-index", state.selectedDistanceIndex)
            state.offsets.forEachIndexed { index, offset ->
                putFloat("${dominantEye.name}_$index-x", offset.offsetX)
                putFloat("${dominantEye.name}_$index-y", offset.offsetY)
            }
        }.apply()
    }

    private fun loadInverseDistanceAlignmentState(): InverseDistanceAlignmentState {
        val preferences = getSharedPreferences(INVERSE_DISTANCE_ALIGNMENT_PREFERENCES, MODE_PRIVATE)
        val distance = preferences.getFloat("distance", 1f)
            .coerceAtLeast(InverseDistanceAlignmentState.MIN_DISTANCE_METERS)
        val recordDistances = preferences.all.keys.mapNotNull { key ->
            RECORD_KEY_REGEX.matchEntire(key)?.groupValues?.get(1)?.toIntOrNull()
        }.toSet()
        val records = recordDistances.associateWith { recordDistance ->
            InverseDistanceFitRecord(
                distanceMeters = recordDistance,
                b = preferences.getFloat("record_${recordDistance}_b", InverseDistanceAlignmentState.DEFAULT_B),
                k = preferences.getFloat("record_${recordDistance}_k", InverseDistanceAlignmentState.DEFAULT_K),
            )
        }.toMutableMap()
        if (records.isEmpty() && (preferences.contains("b") || preferences.contains("k"))) {
            records[distance.toInt()] = InverseDistanceFitRecord(
                distanceMeters = distance.toInt(),
                b = preferences.getFloat("b", InverseDistanceAlignmentState.DEFAULT_B),
                k = preferences.getFloat("k", InverseDistanceAlignmentState.DEFAULT_K),
            )
        }
        val current = records[distance.toInt()]
        return InverseDistanceAlignmentState(
            distanceMeters = distance,
            b = current?.b ?: InverseDistanceAlignmentState.DEFAULT_B,
            k = current?.k ?: InverseDistanceAlignmentState.DEFAULT_K,
            records = records,
        )
    }

    private fun saveInverseDistanceAlignmentState(state: InverseDistanceAlignmentState) {
        val recordedState = state.withCurrentRecord()
        getSharedPreferences(INVERSE_DISTANCE_ALIGNMENT_PREFERENCES, MODE_PRIVATE).edit().apply {
            putFloat("distance", recordedState.distanceMeters)
            putFloat("b", recordedState.b)
            putFloat("k", recordedState.k)
            recordedState.records.values.forEach { record ->
                putFloat("record_${record.distanceMeters}_b", record.b)
                putFloat("record_${record.distanceMeters}_k", record.k)
            }
        }.apply()
        runCatching {
            val outputDir = File(getExternalFilesDir(null), INVERSE_DISTANCE_EXPORT_DIRECTORY).apply { mkdirs() }
            File(outputDir, INVERSE_DISTANCE_EXPORT_FILE).writeText(recordedState.toCsv())
        }.onFailure { error ->
            Log.e(TAG, "Failed to export inverse distance records", error)
        }
    }

    @Suppress("DEPRECATION")
    private fun numberExtra(name: String): Float? = (intent?.extras?.get(name) as? Number)?.toFloat()

    override fun onResume() {
        super.onResume()
        resumed = true
        applyDisplayMode()
        startCameraWhenReady()
        uiHandler.post(refreshRunnable)
    }

    override fun onPause() {
        if (mode.isAlignmentPreviewMode()) {
            logAlignmentResult("pause")
        }
        resumed = false
        uiHandler.removeCallbacks(refreshRunnable)
        cancelAlignmentDetectionLoop()
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
        cancelAlignmentDetectionLoop()
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
        if (mode == DisplayMode.INVERSE_DISTANCE_ALIGNMENT) {
            inverseDistanceAlignmentState = when (keyEvent) {
                GlassKeyEvent.KEYCODE_CLICK -> inverseDistanceAlignmentState.selectNextControl()
                GlassKeyEvent.KEYCODE_FRONT -> inverseDistanceAlignmentState.adjust(AdjustmentDirection.DECREASE)
                GlassKeyEvent.KEYCODE_BEHIND -> inverseDistanceAlignmentState.adjust(AdjustmentDirection.INCREASE)
                else -> return super.onGlassKeyEvent(keyEvent)
            }.withCurrentRecord()
            calibrationState = inverseDistanceAlignmentState.calibrationState()
            saveInverseDistanceAlignmentState(inverseDistanceAlignmentState)
            applyAlignmentCalibration(logResult = true)
            return true
        }
        if (mode == DisplayMode.DISTANCE_ALIGNMENT) {
            distanceAlignmentState = when (distanceAlignmentActionForKey(keyEvent)) {
                DistanceAlignmentInputAction.SELECT_CONTROL -> distanceAlignmentState.selectNextControl()
                DistanceAlignmentInputAction.NEXT_DISTANCE -> distanceAlignmentState.selectNextDistance()
                DistanceAlignmentInputAction.DECREASE -> distanceAlignmentState.adjust(AdjustmentDirection.DECREASE)
                DistanceAlignmentInputAction.INCREASE -> distanceAlignmentState.adjust(AdjustmentDirection.INCREASE)
                DistanceAlignmentInputAction.NONE -> return super.onGlassKeyEvent(keyEvent)
            }
            calibrationState = distanceAlignmentState.calibrationState()
            saveDistanceAlignmentState(distanceAlignmentState)
            applyAlignmentCalibration(logResult = true)
            return true
        }
        if (mode == DisplayMode.ALIGNMENT_CALIBRATION) {
            val updated = when (keyEvent) {
                GlassKeyEvent.KEYCODE_FRONT -> detectionOverlayAlignmentState.adjustDistance(
                    AdjustmentDirection.DECREASE,
                )
                GlassKeyEvent.KEYCODE_BEHIND -> detectionOverlayAlignmentState.adjustDistance(
                    AdjustmentDirection.INCREASE,
                )
                GlassKeyEvent.KEYCODE_CLICK -> detectionOverlayAlignmentState
                else -> null
            }
            if (updated != null) {
                detectionOverlayAlignmentState = updated
                calibrationState = detectionOverlayAlignmentState.calibrationState()
                applyAlignmentCalibration(logResult = true)
                return true
            }
            return super.onGlassKeyEvent(keyEvent)
        }
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
                DisplayMode.ALIGNMENT_CALIBRATION -> DisplayMode.ALIGNMENT_CALIBRATION
                DisplayMode.DISTANCE_ALIGNMENT -> DisplayMode.DISTANCE_ALIGNMENT
                DisplayMode.INVERSE_DISTANCE_ALIGNMENT -> DisplayMode.INVERSE_DISTANCE_ALIGNMENT
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
        if (mode == DisplayMode.SURFACE_DEMO_RAW || mode.isAlignmentPreviewMode()) {
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
                } else if (mode.isAlignmentPreviewMode()) {
                    applyAlignmentCalibration(logResult = false)
                    if (mode == DisplayMode.ALIGNMENT_CALIBRATION) scheduleNextAlignmentDetection()
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
        demoSurfacePreview.visibility = if (
            mode == DisplayMode.SURFACE_DEMO_RAW || mode.isAlignmentPreviewMode()
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        surfacePreview.visibility = if (
            mode == DisplayMode.SURFACE_DEMO_RAW || mode.isAlignmentPreviewMode()
        ) {
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
        alignmentDetectionOverlay.visibility = if (mode == DisplayMode.ALIGNMENT_CALIBRATION) View.VISIBLE else View.GONE
        hint.setText(
            if (mode.isAlignmentPreviewMode()) {
                if (mode == DisplayMode.DISTANCE_ALIGNMENT) {
                    R.string.distance_alignment_hint
                } else if (mode == DisplayMode.INVERSE_DISTANCE_ALIGNMENT) {
                    R.string.inverse_distance_alignment_hint
                } else {
                    R.string.alignment_calibration_hint
                }
            } else {
                R.string.raw_camera_debug_hint
            },
        )
        surfacePreview.alpha = 1f
        demoSurfacePreview.alpha = if (mode.isAlignmentPreviewMode()) calibrationState.alpha else 1f
        surfacePreview.setPreviewRenderMode(
            when (mode) {
                DisplayMode.SURFACE_VALIDATED_CENTER -> RokidCameraPreviewView.PreviewRenderMode.AUTO_SURFACE_SQUARE
                DisplayMode.SURFACE_BOTTOM_SQUARE -> RokidCameraPreviewView.PreviewRenderMode.SURFACE_BOTTOM_SQUARE
                DisplayMode.SURFACE_SQUARE_DIRECT,
                DisplayMode.SURFACE_SQUARE_FITTED,
                DisplayMode.SURFACE_SQUARE_TRANSPOSED,
                DisplayMode.ALIGNMENT_CALIBRATION,
                DisplayMode.DISTANCE_ALIGNMENT,
                DisplayMode.INVERSE_DISTANCE_ALIGNMENT,
                -> RokidCameraPreviewView.PreviewRenderMode.DEBUG_TEXTURE_CROP_FILL
                else -> RokidCameraPreviewView.PreviewRenderMode.RAW_ASPECT_FIT
            },
        )
        if (mode.isSurfaceSquareCandidate()) {
            updateSquareCandidate(forceApply = true)
        } else if (mode.isAlignmentPreviewMode()) {
            applyAlignmentCalibration(logResult = false)
        }
        lastNv21Timestamp = 0L
        compareSurfaceResult = null
        compareNv21Result = null
        cameraReady = false
        cameraAcquiring = false
        startCameraWhenReady()
    }

    private fun applyAlignmentCalibration(logResult: Boolean) {
        val (surfaceWidth, surfaceHeight) = alignmentSurfaceSize()
        val crop = calibrationState.normalizedSurfaceCrop(surfaceWidth, surfaceHeight)
        demoSurfacePreview.alpha = calibrationState.alpha
        demoSurfacePreview.setCustomTextureCrop(crop.left, crop.top, crop.width, crop.height)
        refreshDiagnostics()
        if (logResult) {
            logAlignmentResult("adjust")
        }
    }

    private fun beginAlignmentDetectionCycle() {
        if (!resumed || mode != DisplayMode.ALIGNMENT_CALIBRATION || !cameraReady || detectionRequestInFlight) return
        val cadenceDelay = AlignmentDetectionCadence.nextDelayMs(
            nowMs = SystemClock.elapsedRealtime(),
            lastStartedMs = lastDetectionStartedMs,
            requestInFlight = detectionRequestInFlight,
        ) ?: return
        if (cadenceDelay > 0L) {
            uiHandler.postDelayed(detectionLoopRunnable, cadenceDelay)
            return
        }
        val sourceWidth = demoSurfacePreview.width
        val sourceHeight = demoSurfacePreview.height
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            detectionStatus = "等待预览尺寸"
            uiHandler.postDelayed(detectionLoopRunnable, CAPTURE_RETRY_DELAY_MS)
            return
        }
        detectionRequestInFlight = true
        detectionStatus = "正在截取画面"
        val captured = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
        PixelCopy.request(
            demoSurfacePreview,
            captured,
            { result ->
                if (result != PixelCopy.SUCCESS || !isAlignmentDetectionActive()) {
                    captured.recycle()
                    finishCaptureFailure("截帧失败 code=$result")
                    return@request
                }
                encodeAndSubmitAlignmentFrame(captured)
            },
            uiHandler,
        )
    }

    private fun encodeAndSubmitAlignmentFrame(captured: Bitmap) {
        bitmapExecutor.execute {
            val jpegBytes = runCatching {
                val cropped = centerCropThreeByFour(captured)
                val scaled = Bitmap.createScaledBitmap(
                    cropped,
                    inferenceImageSize.width,
                    inferenceImageSize.height,
                    true,
                )
                ByteArrayOutputStream().use { output ->
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, INFERENCE_JPEG_QUALITY, output))
                    output.toByteArray()
                }.also {
                    if (scaled !== cropped) scaled.recycle()
                    if (cropped !== captured) cropped.recycle()
                }
            }.getOrNull()
            captured.recycle()
            uiHandler.post {
                if (jpegBytes == null || !isAlignmentDetectionActive()) {
                    finishCaptureFailure("图像编码失败")
                } else {
                    submitAlignmentFrame(jpegBytes)
                }
            }
        }
    }

    private fun centerCropThreeByFour(source: Bitmap): Bitmap {
        val targetRatio = 3f / 4f
        val sourceRatio = source.width.toFloat() / source.height.toFloat()
        return if (sourceRatio > targetRatio) {
            val cropWidth = (source.height * targetRatio).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(source, (source.width - cropWidth) / 2, 0, cropWidth, source.height)
        } else if (sourceRatio < targetRatio) {
            val cropHeight = (source.width / targetRatio).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(source, 0, (source.height - cropHeight) / 2, source.width, cropHeight)
        } else {
            source
        }
    }

    private fun submitAlignmentFrame(jpegBytes: ByteArray) {
        val requestId = ++nextDetectionRequestId
        activeDetectionRequestId = requestId
        lastDetectionStartedMs = SystemClock.elapsedRealtime()
        detectionStatus = "识别中 ${jpegBytes.size / 1024}KB"
        val timeoutRunnable = Runnable {
            if (activeDetectionRequestId != requestId || !detectionRequestInFlight) return@Runnable
            activeDetectionCall?.cancel()
            completeAlignmentDetectionRequest(requestId, "超时，保留上一帧框")
        }
        activeDetectionCall = alignmentDetectionClient.detect(
            jpegBytes = jpegBytes,
            callback = object : AlignmentAutoDetectionClient.ResultCallback {
                override fun onSuccess(response: AlignmentDetectionResponse) {
                    uiHandler.post {
                        if (activeDetectionRequestId != requestId || !detectionRequestInFlight) return@post
                        uiHandler.removeCallbacks(timeoutRunnable)
                        alignmentDetectionOverlay.showDetections(
                            imageWidth = inferenceImageSize.width,
                            imageHeight = inferenceImageSize.height,
                            detections = response.detections,
                        )
                        Log.i(
                            TAG,
                            "AlignmentDetection success requestId=$requestId image=${inferenceImageSize.width}x${inferenceImageSize.height} detections=${response.detections}",
                        )
                        completeAlignmentDetectionRequest(requestId, "已更新 ${response.detections.size} 个框")
                    }
                }

                override fun onFailure(message: String) {
                    uiHandler.post {
                        if (activeDetectionRequestId != requestId || !detectionRequestInFlight) return@post
                        uiHandler.removeCallbacks(timeoutRunnable)
                        Log.w(TAG, "AlignmentDetection failure requestId=$requestId message=$message")
                        completeAlignmentDetectionRequest(requestId, "失败，保留上一帧框")
                    }
                }
            },
        )
        uiHandler.postDelayed(timeoutRunnable, AlignmentDetectionCadence.REQUEST_TIMEOUT_MS)
    }

    private fun completeAlignmentDetectionRequest(requestId: Long, status: String) {
        if (activeDetectionRequestId != requestId) return
        activeDetectionCall = null
        activeDetectionRequestId = 0L
        detectionRequestInFlight = false
        detectionStatus = status
        scheduleNextAlignmentDetection()
    }

    private fun finishCaptureFailure(status: String) {
        if (!detectionRequestInFlight) return
        detectionRequestInFlight = false
        detectionStatus = status
        uiHandler.postDelayed(detectionLoopRunnable, CAPTURE_RETRY_DELAY_MS)
    }

    private fun scheduleNextAlignmentDetection() {
        uiHandler.removeCallbacks(detectionLoopRunnable)
        if (!isAlignmentDetectionActive()) return
        val delay = AlignmentDetectionCadence.nextDelayMs(
            nowMs = SystemClock.elapsedRealtime(),
            lastStartedMs = lastDetectionStartedMs,
            requestInFlight = detectionRequestInFlight,
        ) ?: return
        uiHandler.postDelayed(detectionLoopRunnable, delay)
    }

    private fun cancelAlignmentDetectionLoop() {
        uiHandler.removeCallbacks(detectionLoopRunnable)
        activeDetectionCall?.cancel()
        activeDetectionCall = null
        activeDetectionRequestId = 0L
        detectionRequestInFlight = false
    }

    private fun isAlignmentDetectionActive(): Boolean {
        return resumed && mode == DisplayMode.ALIGNMENT_CALIBRATION && cameraReady
    }

    private fun logAlignmentResult(reason: String) {
        val (surfaceWidth, surfaceHeight) = alignmentSurfaceSize()
        val reportedSize = demoSurfacePreview.cameraSize()
        val crop = calibrationState.normalizedSurfaceCrop(surfaceWidth, surfaceHeight)
        Log.i(
            TAG,
            "AlignmentCalibration result reason=$reason control=${calibrationState.control} " +
                "eye=$dominantEye " +
                if (mode == DisplayMode.DISTANCE_ALIGNMENT) {
                    "distance=${distanceAlignmentState.distanceMeters}m "
                } else {
                    ""
                } +
                "scale=${calibrationState.scale} offsetX=${calibrationState.offsetX} " +
                "offsetY=${calibrationState.offsetY} alpha=${calibrationState.alpha} " +
                "texture=${surfaceWidth}x$surfaceHeight reported=${reportedSize?.first}x${reportedSize?.second} " +
                "zoomLevel=$ALIGNMENT_CAMERA_ZOOM_LEVEL " +
                "crop=[${crop.left},${crop.top},${crop.width},${crop.height}]",
        )
    }

    private fun alignmentSurfaceSize(): Pair<Int, Int> {
        val reported = demoSurfacePreview.cameraSize()
        return selectAlignmentTextureSize(
            requestedWidth = ALIGNMENT_CAMERA_WIDTH,
            requestedHeight = ALIGNMENT_CAMERA_HEIGHT,
            reportedWidth = reported?.first ?: 0,
            reportedHeight = reported?.second ?: 0,
        )
    }

    private fun stopInactivePreviewForMode() {
        if (mode == DisplayMode.SDK_DEMO_COMPARE) {
            demoSurfacePreview.stopDemoPreview()
            InspectionCameraCoordinator.pause(CameraOwner.RAW_CAMERA_DEBUG, reason = "raw_debug_switch_to_demo_compare")
        } else if (mode == DisplayMode.SURFACE_DEMO_RAW || mode.isAlignmentPreviewMode()) {
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
            if (mode.isAlignmentPreviewMode()) {
                val (alignmentWidth, alignmentHeight) = alignmentSurfaceSize()
                val crop = calibrationState.normalizedSurfaceCrop(alignmentWidth, alignmentHeight)
                append("  Control: ")
                append(
                    if (mode == DisplayMode.INVERSE_DISTANCE_ALIGNMENT) {
                        inverseDistanceAlignmentState.control.name
                    } else {
                        calibrationState.control.name
                    },
                )
                append("  Eye: ")
                append(dominantEye.name)
                if (mode == DisplayMode.ALIGNMENT_CALIBRATION) {
                    append("  Distance: ")
                    append("%.1fm".format(detectionOverlayAlignmentState.distanceMeters))
                    append("\nFormula: X = B - K / distance")
                    append("  B: ")
                    append("%.2f".format(InverseDistanceAlignmentState.DEFAULT_B))
                    append("  K: ")
                    append("%.2f".format(InverseDistanceAlignmentState.DEFAULT_K))
                } else if (mode == DisplayMode.DISTANCE_ALIGNMENT) {
                    append("  Distance: ")
                    append("%.1fm".format(distanceAlignmentState.distanceMeters))
                } else if (mode == DisplayMode.INVERSE_DISTANCE_ALIGNMENT) {
                    append("  Distance: ")
                    append("%.1fm".format(inverseDistanceAlignmentState.distanceMeters))
                    append("\nFormula: X = B - K / distance")
                    append("  B: ")
                    append("%.2f".format(inverseDistanceAlignmentState.b))
                    append("  K: ")
                    append("%.2f".format(inverseDistanceAlignmentState.k))
                    append("  Records: ")
                    append(inverseDistanceAlignmentState.records.size)
                }
                append("\nScale: ")
                append("%.6f".format(calibrationState.scale))
                append("  X: ")
                append("%.1f".format(calibrationState.offsetX))
                append("  Y: ")
                append("%.1f".format(calibrationState.offsetY))
                append("\nStep: ")
                append("%.1fpx".format(calibrationState.translationStep))
                append("  Alpha: ")
                append("%.2f".format(calibrationState.alpha))
                append("\nView: ")
                append(demoSurfacePreview.width)
                append('x')
                append(demoSurfacePreview.height)
                append("  Surface: ")
                append(alignmentWidth)
                append('x')
                append(alignmentHeight)
                append("  Zoom: ")
                append(ALIGNMENT_CAMERA_ZOOM_LEVEL)
                append("\nCrop: [")
                append("%.5f, %.5f, %.5f, %.5f".format(crop.left, crop.top, crop.width, crop.height))
                append(']')
                if (mode == DisplayMode.ALIGNMENT_CALIBRATION) {
                    append("\nAI: ")
                    append(detectionStatus)
                    append("  Image: ")
                    append(inferenceImageSize.width)
                    append('x')
                    append(inferenceImageSize.height)
                }
                return@buildString
            }
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

    private fun DisplayMode.isAlignmentPreviewMode(): Boolean {
        return this == DisplayMode.ALIGNMENT_CALIBRATION ||
            this == DisplayMode.DISTANCE_ALIGNMENT ||
            this == DisplayMode.INVERSE_DISTANCE_ALIGNMENT
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
        private const val MODE_ALIGNMENT_CALIBRATION = "alignment_calibration"
        private const val MODE_DISTANCE_ALIGNMENT = "distance_alignment"
        private const val MODE_INVERSE_DISTANCE_ALIGNMENT = "inverse_distance_alignment"
        private const val DISTANCE_ALIGNMENT_PREFERENCES = "distance_alignment_offsets"
        private const val INVERSE_DISTANCE_ALIGNMENT_PREFERENCES = "inverse_distance_alignment"
        private const val INVERSE_DISTANCE_EXPORT_DIRECTORY = "alignment"
        private const val INVERSE_DISTANCE_EXPORT_FILE = "inverse-distance-fit.csv"
        private val RECORD_KEY_REGEX = Regex("record_(\\d+)_[bk]")
        private const val EXTRA_SCALE = "scale"
        private const val EXTRA_OFFSET_X = "offsetX"
        private const val EXTRA_OFFSET_Y = "offsetY"
        private const val EXTRA_ALPHA = "alpha"
        private const val EXTRA_TRANSLATION_STEP = "translationStep"
        private const val EXTRA_SCALE_STEP = "scaleStep"
        private const val EXTRA_DOMINANT_EYE = "dominantEye"
        private const val ALIGNMENT_CAMERA_WIDTH = 3024
        private const val ALIGNMENT_CAMERA_HEIGHT = 4032
        private const val ALIGNMENT_CAMERA_FPS = 15
        private const val ALIGNMENT_CAMERA_ZOOM_LEVEL = 1
        private const val DEFAULT_INFERENCE_IMAGE_WIDTH = 960
        private const val INFERENCE_JPEG_QUALITY = 82
        private const val CAPTURE_RETRY_DELAY_MS = 200L
        private const val REFRESH_INTERVAL_MS = 300L
        private const val REQUEST_CAMERA_PERMISSION = 2011
        private const val SQUARE_VIEWPORT_SIZE_DP = 220
        private const val DEMO_SURFACE_WIDTH_PX = 480
        private const val DEMO_SURFACE_HEIGHT_PX = 320
    }
}
