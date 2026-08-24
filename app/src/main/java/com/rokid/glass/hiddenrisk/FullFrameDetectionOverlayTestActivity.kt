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
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rokid.glass.camera.CameraStreamProfile
import com.rokid.glass.camera.RokidFrameSource
import com.rokid.glass.component.RokidCameraPreviewView
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator.CameraOwner
import com.rokid.glass.utils.BitmapUtils
import com.rokid.glesse.R
import okhttp3.Call
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class FullFrameDetectionOverlayTestActivity : BaseGlassActivity(), RokidSdkManager.Listener {
    private val uiHandler = Handler(Looper.getMainLooper())
    private val encodingExecutor = Executors.newSingleThreadExecutor()
    private val detectionClient = AlignmentAutoDetectionClient()
    private val requestState = FullFrameDetectionRequestState()
    private val detectionLoop = Runnable { beginDetectionCycle() }

    private lateinit var previewView: RokidCameraPreviewView
    private lateinit var overlayView: FullFrameDetectionOverlayView
    private lateinit var diagnosticsView: TextView

    private var calibrationState = FullFrameOverlayCalibrationState()
    private var resumed = false
    private var cameraReady = false
    private var cameraAcquiring = false
    private var sourceSize: FrameSize? = null
    private var lastResponse: AlignmentDetectionResponse? = null
    private var activeCall: Call? = null
    private var terminalSourceError = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_full_frame_detection_overlay_test)
        previewView = findViewById(R.id.viewFullFramePreview)
        overlayView = findViewById(R.id.viewFullFrameOverlay)
        diagnosticsView = findViewById(R.id.textFullFrameDiagnostics)
        previewView.setPreviewRenderMode(RokidCameraPreviewView.PreviewRenderMode.DEBUG_TEXTURE_CROP_FILL)
        applyCalibration()
        RokidSdkManager.initialize(application as Application)
        RokidSdkManager.addListener(this)
        RokidSdkManager.ensureInitialized()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        terminalSourceError = false
        startCameraWhenReady()
    }

    override fun onPause() {
        resumed = false
        stopDetectionLoop()
        cameraReady = false
        cameraAcquiring = false
        InspectionCameraCoordinator.pauseTemporarily(
            CameraOwner.FULL_FRAME_OVERLAY_TEST,
            reason = "full_frame_overlay_on_pause",
        )
        super.onPause()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        stopDetectionLoop()
        RokidSdkManager.removeListener(this)
        InspectionCameraCoordinator.releaseForNavigation(
            CameraOwner.FULL_FRAME_OVERLAY_TEST,
            reason = "full_frame_overlay_on_destroy",
        )
        encodingExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onSdkStateChanged(state: RokidSdkManager.SdkState) {
        when (state) {
            RokidSdkManager.SdkState.READY -> startCameraWhenReady()
            RokidSdkManager.SdkState.FAILED -> renderStatus("camera_sdk_failed")
            else -> Unit
        }
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        calibrationState = when (keyEvent) {
            GlassKeyEvent.KEYCODE_CLICK -> calibrationState.selectNextControl()
            GlassKeyEvent.KEYCODE_FRONT -> calibrationState.adjust(AdjustmentDirection.DECREASE)
            GlassKeyEvent.KEYCODE_BEHIND -> calibrationState.adjust(AdjustmentDirection.INCREASE)
            GlassKeyEvent.KEYCODE_DOUBLE_CLICK -> calibrationState.togglePreview()
            else -> return super.onGlassKeyEvent(keyEvent)
        }
        applyCalibration()
        remapLastResponse()
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA_PERMISSION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCameraWhenReady()
        } else {
            renderStatus("camera_permission_denied")
        }
    }

    private fun startCameraWhenReady() {
        if (!resumed || cameraReady || cameraAcquiring) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
            return
        }
        if (RokidSdkManager.state != RokidSdkManager.SdkState.READY) {
            renderStatus("waiting_camera_sdk")
            return
        }
        cameraAcquiring = true
        renderStatus("opening requested=3024x4032")
        InspectionCameraCoordinator.acquireForActivity(
            owner = CameraOwner.FULL_FRAME_OVERLAY_TEST,
            needPreview = true,
            previewView = previewView,
            streamProfile = CameraStreamProfile.FULL_FRAME_OVERLAY_TEST,
        ) { success ->
            cameraAcquiring = false
            cameraReady = success
            Log.i(TAG, "camera ready=$success requested=3024x4032")
            if (success) {
                applyCalibration()
                scheduleNextDetection(0L)
            } else {
                renderStatus("camera_open_failed ${RokidFrameSource.diagnosticsSnapshot()}")
            }
        }
    }

    private fun beginDetectionCycle() {
        if (!resumed || !cameraReady || terminalSourceError) return
        val requestId = requestState.begin(SystemClock.elapsedRealtime())
        if (requestId == null) {
            scheduleNextDetection(RETRY_POLL_MS)
            return
        }
        val frame = RokidFrameSource.copyLatestRawFrame()
        if (frame == null) {
            requestState.acceptFailure(requestId)
            renderStatus("waiting_nv21_frame")
            scheduleNextDetection(RETRY_POLL_MS)
            return
        }
        if (frame.width * 4 != frame.height * 3) {
            requestState.acceptFailure(requestId)
            terminalSourceError = true
            val message = "unsupported_source_size=${frame.width}x${frame.height}"
            Log.e(TAG, "$message supported=${RokidFrameSource.diagnosticsSnapshot().supportedPreviewSizes}")
            renderStatus(message)
            return
        }
        sourceSize = FrameSize(frame.width, frame.height)
        Log.i(
            TAG,
            "nv21 actual=${frame.width}x${frame.height} aspect=3:4 requested=3024x4032",
        )
        encodingExecutor.execute { encodeAndSubmit(frame, requestId) }
    }

    private fun encodeAndSubmit(frame: RokidFrameSource.Nv21Frame, requestId: Long) {
        val jpegBytes = runCatching {
            val source = checkNotNull(BitmapUtils.nv21ToBitmap(frame.data, frame.width, frame.height))
            val scaled = Bitmap.createScaledBitmap(source, REQUEST_WIDTH, REQUEST_HEIGHT, true)
            try {
                ByteArrayOutputStream().use { output ->
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output))
                    output.toByteArray()
                }
            } finally {
                if (scaled !== source) scaled.recycle()
                source.recycle()
            }
        }.getOrNull()
        uiHandler.post {
            if (!resumed || jpegBytes == null) {
                requestState.acceptFailure(requestId)
                if (resumed) {
                    renderStatus("frame_encode_failed")
                    scheduleNextDetection(RETRY_POLL_MS)
                }
                return@post
            }
            submitRequest(jpegBytes, requestId, frame.width, frame.height)
        }
    }

    private fun submitRequest(jpegBytes: ByteArray, requestId: Long, width: Int, height: Int) {
        Log.i(
            TAG,
            "auto requestId=$requestId source=${width}x$height request=${REQUEST_WIDTH}x$REQUEST_HEIGHT " +
                "jpegBytes=${jpegBytes.size}",
        )
        renderStatus("source=${width}x$height request=${REQUEST_WIDTH}x$REQUEST_HEIGHT ${jpegBytes.size / 1024}KB")
        activeCall = detectionClient.detect(jpegBytes, object : AlignmentAutoDetectionClient.ResultCallback {
            override fun onSuccess(response: AlignmentDetectionResponse) {
                uiHandler.post {
                    if (!requestState.acceptSuccess(requestId)) return@post
                    activeCall = null
                    lastResponse = response
                    remapLastResponse()
                    scheduleNextDetection(0L)
                }
            }

            override fun onFailure(message: String) {
                uiHandler.post {
                    if (!requestState.acceptFailure(requestId)) return@post
                    activeCall = null
                    Log.w(TAG, "auto failure requestId=$requestId message=$message")
                    renderStatus("auto_failed=$message keep_previous_boxes")
                    scheduleNextDetection(0L)
                }
            }
        })
    }

    private fun remapLastResponse() {
        val response = lastResponse ?: return
        val actualSourceSize = sourceSize ?: return
        val mapped = FullFrameOverlayMapper.map(
            responseDetections = response.detections,
            requestSize = FrameSize(REQUEST_WIDTH, REQUEST_HEIGHT),
            sourceSize = actualSourceSize,
            overlaySize = FrameSize(OVERLAY_WIDTH, OVERLAY_HEIGHT),
            calibration = calibrationState.calibration,
        )
        if (mapped.detections.isEmpty()) overlayView.clearDetections()
        else overlayView.showDetections(mapped.detections)
        Log.i(
            TAG,
            "responseBBoxCount=${response.detections.size} projectionCrop=${mapped.sourceCrop} " +
                "mappedBBoxCount=${mapped.detections.size}",
        )
        renderStatus(
            "source=${actualSourceSize.width}x${actualSourceSize.height} request=960x1280 " +
                "bbox=${response.detections.size}/${mapped.detections.size}",
        )
    }

    private fun applyCalibration() {
        val crop = calibrationState.calibration.normalizedCameraCrop()
        previewView.alpha = calibrationState.previewAlpha
        previewView.setDebugTextureCrop(crop.left, crop.top, crop.width, crop.height)
        renderStatus(
            "1m ${calibrationState.calibration.control} scale=${calibrationState.calibration.scale} " +
                "x=${calibrationState.calibration.offsetX} y=${calibrationState.calibration.offsetY} " +
                "alpha=${calibrationState.previewAlpha}",
        )
    }

    private fun scheduleNextDetection(delayMs: Long) {
        uiHandler.removeCallbacks(detectionLoop)
        if (resumed && cameraReady && !terminalSourceError) {
            uiHandler.postDelayed(detectionLoop, delayMs)
        }
    }

    private fun stopDetectionLoop() {
        uiHandler.removeCallbacks(detectionLoop)
        activeCall?.cancel()
        activeCall = null
        requestState.cancel()
    }

    private fun renderStatus(message: String) {
        if (::diagnosticsView.isInitialized) diagnosticsView.text = message
    }

    companion object {
        private const val TAG = "FullFrameOverlayTest"
        private const val REQUEST_CAMERA_PERMISSION = 6104
        private const val REQUEST_WIDTH = 960
        private const val REQUEST_HEIGHT = 1280
        private const val OVERLAY_WIDTH = 480
        private const val OVERLAY_HEIGHT = 640
        private const val JPEG_QUALITY = 82
        private const val RETRY_POLL_MS = 50L
    }
}
