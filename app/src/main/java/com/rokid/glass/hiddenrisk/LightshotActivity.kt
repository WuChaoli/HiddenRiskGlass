package com.rokid.glass.hiddenrisk

import android.graphics.Bitmap.CompressFormat
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.TextureView
import android.view.View
import android.widget.TextView
import com.rokid.glass.camera.QuickCameraManager
import com.rokid.glass.utils.SpriteToastUtil
import com.rokid.glesse.R
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.sdk.base.data.offlineCmd.bean.VoiceAction
import com.rokid.security.glass3.sdk.base.data.offlineCmd.listener.IVoiceCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 闪拍页面：展示 SDK 共享预览，单击触控板时保存当前视频流帧。
 */
class LightshotActivity : BaseGlassActivity() {

    companion object {
        private const val TAG = "LightshotActivity"
        private const val DEFAULT_ZOOM_RATIO = 2.0f
        private const val SAVE_DIR_NAME = "lightshot"
        private const val OUTPUT_SIZE = 640
        private const val JPEG_QUALITY = 90
        private const val RESULT_TIP_DURATION_MS = 2000L
        private val FOV_LEVELS = floatArrayOf(1.0f, 1.2f, 1.5f, 1.8f, 2.0f, 2.2f, 2.6f, 3.0f)
        private val QUICK_CAPTURE_SIZE = Size(640, 640)
    }

    private lateinit var previewView: TextureView
    private lateinit var tvHint: TextView
    private lateinit var tvFov: TextView
    private lateinit var tvSaveResult: TextView

    @Volatile
    private var isCapturing = false

    private var isCameraReady = false
    private var cameraInitInProgress = false
    private var currentFovIndex = 0
    private val shutterSound by lazy { MediaActionSound() }

    private val voiceHandler = Handler(Looper.getMainLooper())
    private var voiceRegistered = false
    private var headGestureSupported = false

    private val headGestureListener = object : HeadGestureManager.Listener {
        override fun onHeadGesture(event: HeadGestureManager.HeadGestureEvent) {
            val gestureLabel = when (event.type) {
                HeadGestureManager.HeadGestureType.NOD -> "点头"
                HeadGestureManager.HeadGestureType.SHAKE -> "摇头"
            }
            showResultTip("检测到$gestureLabel")
            SpriteToastUtil.showSpriteToast(this@LightshotActivity, "检测到$gestureLabel", 0, 1500, false)
            Log.i(
                TAG,
                "head gesture event type=${event.type} pitch=${"%.1f".format(Locale.US, event.pitchDeg)} yaw=${"%.1f".format(Locale.US, event.yawDeg)}",
            )
        }
    }

    private val voiceCaptureAction = VoiceAction("拍照", "pai zhao", object : IVoiceCallback.Stub() {
        override fun onVoiceTriggered() {
            runOnUiThread { captureAndSave() }
        }
    })
    private val voiceZoomInAction = VoiceAction("放大", "fang da", object : IVoiceCallback.Stub() {
        override fun onVoiceTriggered() {
            runOnUiThread { adjustFov(1) }
        }
    })
    private val voiceZoomOutAction = VoiceAction("缩小", "suo xiao", object : IVoiceCallback.Stub() {
        override fun onVoiceTriggered() {
            runOnUiThread { adjustFov(-1) }
        }
    })
    private val voiceExitAction = VoiceAction("退出", "tui chu", object : IVoiceCallback.Stub() {
        override fun onVoiceTriggered() {
            runOnUiThread { finish() }
        }
    })

    private val allVoiceActions = listOf(voiceCaptureAction, voiceZoomInAction, voiceZoomOutAction, voiceExitAction)

    private val voiceRegisterRunnable = object : Runnable {
        override fun run() {
            if (registerVoiceCommandsIfReady()) return
            voiceHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lightshot)

        previewView = findViewById(R.id.previewView)
        previewView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                Log.i(TAG, "preview surface available width=$width height=$height")
                initCamera(surface, width, height)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                Log.i(TAG, "preview surface size changed width=$width height=$height")
                applyPreviewTransform()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                Log.i(TAG, "preview surface destroyed")
                QuickCameraManager.detachPreviewTexture()
                isCameraReady = false
                cameraInitInProgress = false
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        tvHint = findViewById(R.id.tvHint)
        tvFov = findViewById(R.id.tvFov)
        tvSaveResult = findViewById(R.id.tvSaveResult)

        shutterSound.load(MediaActionSound.SHUTTER_CLICK)
        syncFovWithManager()
        updateFovTip()
        HeadGestureManager.initialize(this)
        headGestureSupported = HeadGestureManager.isSupported()
        if (!headGestureSupported) {
            Log.w(TAG, "头部动作识别不可用，设备缺少所需传感器")
        }
    }

    override fun onStart() {
        super.onStart()
        HeadGestureManager.addListener(headGestureListener)
    }

    override fun onResume() {
        super.onResume()
        if (previewView.isAvailable) {
            initCamera()
        } else {
            tvHint.text = "预览面初始化中..."
        }
        voiceHandler.removeCallbacks(voiceRegisterRunnable)
        voiceHandler.post(voiceRegisterRunnable)
        if (headGestureSupported) {
            HeadGestureManager.start()
        } else {
            tvHint.text = "设备不支持头部动作识别，仍可正常拍照"
        }
    }

    override fun onPause() {
        voiceHandler.removeCallbacks(voiceRegisterRunnable)
        unregisterVoiceCommands()
        HeadGestureManager.stop()
        isCameraReady = false
        cameraInitInProgress = false
        QuickCameraManager.detachPreviewTexture()
        QuickCameraManager.releaseCamera()
        super.onPause()
    }

    override fun onDestroy() {
        voiceHandler.removeCallbacks(voiceRegisterRunnable)
        unregisterVoiceCommands()
        runCatching { shutterSound.release() }
        QuickCameraManager.detachPreviewTexture()
        QuickCameraManager.releaseCamera()
        Log.d(TAG, "onDestroy: 资源已释放")
        super.onDestroy()
    }

    override fun onStop() {
        HeadGestureManager.removeListener(headGestureListener)
        super.onStop()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        return when (keyEvent) {
            GlassKeyEvent.KEYCODE_CLICK -> {
                captureAndSave()
                true
            }
            GlassKeyEvent.KEYCODE_FRONT -> {
                adjustFov(-1)
                true
            }
            GlassKeyEvent.KEYCODE_BEHIND -> {
                adjustFov(1)
                true
            }
            GlassKeyEvent.KEYCODE_BACK -> {
                finish()
                true
            }
            else -> super.onGlassKeyEvent(keyEvent)
        }
    }

    private fun registerVoiceCommandsIfReady(): Boolean {
        val service = runCatching { GlassSdk.getGlassOfflineCmdService() }.getOrNull() ?: return false
        allVoiceActions.forEach { service.add(it) }
        voiceRegistered = true
        Log.i(TAG, "语音指令已注册: 拍照 | 放大 | 缩小 | 退出")
        return true
    }

    private fun unregisterVoiceCommands() {
        if (!voiceRegistered) return
        runCatching {
            val service = GlassSdk.getGlassOfflineCmdService()
            allVoiceActions.forEach { service?.remove(it) }
        }.onFailure { error ->
            Log.w(TAG, "注销语音指令失败: ${error.message}")
        }
        voiceRegistered = false
    }

    private fun initCamera(surface: SurfaceTexture? = previewView.surfaceTexture, width: Int = previewView.width, height: Int = previewView.height) {
        if (!GlassSdk.isReady()) {
            tvHint.text = "SDK 未就绪，请稍后重试"
            return
        }
        if (surface == null || width <= 0 || height <= 0) {
            tvHint.text = "预览面未就绪，请稍后"
            return
        }
        if (cameraInitInProgress) {
            Log.d(TAG, "initCamera skipped: initialization already in progress")
            return
        }

        tvHint.text = "相机初始化中..."
        isCameraReady = false
        cameraInitInProgress = true
        val preferredZoom = QuickCameraManager.getPreviewZoomRatio()
        currentFovIndex = FOV_LEVELS.indices.minByOrNull { index ->
            kotlin.math.abs(FOV_LEVELS[index] - preferredZoom)
        } ?: FOV_LEVELS.indexOfFirst { it == DEFAULT_ZOOM_RATIO }.coerceAtLeast(0)
        val appliedZoom = QuickCameraManager.setPreviewZoomRatio(FOV_LEVELS[currentFovIndex])
        QuickCameraManager.attachPreviewTexture(surface, width, height)
        QuickCameraManager.initialize(
            size = QUICK_CAPTURE_SIZE,
            quickCapture = true,
        ) { streamReady ->
            runOnUiThread {
                cameraInitInProgress = false
                isCameraReady = streamReady
                if (streamReady) {
                    applyPreviewTransform()
                    updateFovTip(appliedZoom)
                    tvHint.text = "单击拍摄，左右滑动调视野"
                } else {
                    tvHint.text = "视频流初始化失败，请退出重试"
                }
                Log.d(TAG, "lightshot gpu preview init width=$width height=$height streamReady=$streamReady warm=${QuickCameraManager.isGpuCaptureWarm()}")
            }
        }
    }

    private fun adjustFov(step: Int) {
        val newIndex = (currentFovIndex + step).coerceIn(0, FOV_LEVELS.lastIndex)
        if (newIndex == currentFovIndex) {
            showResultTip(if (step > 0) "已到最小视野" else "已到最大视野")
            return
        }
        currentFovIndex = newIndex
        val appliedZoom = QuickCameraManager.setPreviewZoomRatio(FOV_LEVELS[currentFovIndex])
        updateFovTip(appliedZoom)
        showResultTip("视野 ${"%.1f".format(Locale.US, appliedZoom)}x")
    }

    private fun applyPreviewTransform() {
        val viewWidth = previewView.width
        val viewHeight = previewView.height
        if (viewWidth <= 0 || viewHeight <= 0) {
            return
        }

        val streamSize = QuickCameraManager.getPreviewStreamSize()
        if (streamSize == null || streamSize.width <= 0 || streamSize.height <= 0) {
            previewView.setTransform(Matrix())
            Log.i(TAG, "preview transform skipped: stream size unavailable view=${viewWidth}x${viewHeight}")
            return
        }

        val bufferWidth = streamSize.width.toFloat()
        val bufferHeight = streamSize.height.toFloat()
        val scale = max(viewWidth / bufferWidth, viewHeight / bufferHeight)
        val scaledWidth = bufferWidth * scale
        val scaledHeight = bufferHeight * scale
        val dx = (viewWidth - scaledWidth) / 2f
        val dy = (viewHeight - scaledHeight) / 2f
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        previewView.setTransform(matrix)
        Log.i(
            TAG,
            "preview transform applied view=${viewWidth}x${viewHeight} stream=${streamSize.width}x${streamSize.height} scale=${"%.3f".format(Locale.US, scale)} dx=${"%.1f".format(Locale.US, dx)} dy=${"%.1f".format(Locale.US, dy)}",
        )
    }

    private fun updateFovTip(zoomRatio: Float = FOV_LEVELS[currentFovIndex]) {
        tvFov.text = "视野 ${"%.1f".format(Locale.US, zoomRatio)}x"
    }

    private fun syncFovWithManager() {
        val zoomRatio = QuickCameraManager.getAppliedPreviewZoomRatio()
        currentFovIndex = FOV_LEVELS.indices.minByOrNull { index ->
            kotlin.math.abs(FOV_LEVELS[index] - zoomRatio)
        } ?: 0
    }

    private fun captureAndSave() {
        if (!isCameraReady) {
            showResultTip("相机未就绪，请稍候")
            return
        }
        if (isCapturing) {
            Log.d(TAG, "captureAndSave: 上一次采集尚未完成，忽略本次触发")
            return
        }

        isCapturing = true
        tvHint.text = "采集中..."
        val startTime = System.nanoTime()
        QuickCameraManager.takeGpuFrame { frame ->
            val gpuFrame = frame ?: run {
                runOnUiThread {
                    showResultTip("当前无可用 GPU 帧")
                    tvHint.text = "单击拍摄，左右滑动调视野"
                    isCapturing = false
                }
                return@takeGpuFrame
            }
            runOnUiThread {
            }
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val result = processFrameAndSave(gpuFrame)
                    val latencyMs = (System.nanoTime() - startTime) / 1_000_000
                    withContext(Dispatchers.Main) {
                        if (result != null) {
                            shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                            showResultTip("已保存：${result.name} (延迟${latencyMs}ms)")
                            Log.i(TAG, "GPU 帧保存成功: ${result.absolutePath}, 延迟=${latencyMs}ms")
                        } else {
                            showResultTip("保存失败，请重试")
                        }
                        tvHint.text = "单击拍摄，左右滑动调视野"
                        isCapturing = false
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "GPU 帧处理异常", error)
                    withContext(Dispatchers.Main) {
                        showResultTip("保存失败：${error.message}")
                        tvHint.text = "单击拍摄，左右滑动调视野"
                        isCapturing = false
                    }
                }
            }
        }
    }

    private fun processFrameAndSave(frame: QuickCameraManager.GpuFrame): File? {
        val source = frame.previewBitmap ?: run {
            frame.hardwareBuffer.close()
            Log.e(TAG, "processFrameAndSave: previewBitmap unavailable width=${frame.width} height=${frame.height}")
            return null
        }
        try {
            val squareBitmap = cropCenterSquare(source)
            val outputBitmap = Bitmap.createScaledBitmap(squareBitmap, OUTPUT_SIZE, OUTPUT_SIZE, true)

            val saveDir = File(Environment.getExternalStorageDirectory(), SAVE_DIR_NAME)
            if (!saveDir.exists() && !saveDir.mkdirs()) {
                Log.e(TAG, "无法创建目录: ${saveDir.absolutePath}")
                return null
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.getDefault()).format(Date())
            val outputFile = File(saveDir, "${timestamp}_GPU.jpg")
            FileOutputStream(outputFile).use { out ->
                outputBitmap.compress(CompressFormat.JPEG, JPEG_QUALITY, out)
                out.flush()
            }

            if (!outputBitmap.isRecycled) outputBitmap.recycle()
            if (squareBitmap !== source && !squareBitmap.isRecycled) squareBitmap.recycle()

            val mediaScanIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = Uri.fromFile(outputFile)
            sendBroadcast(mediaScanIntent)

            return outputFile
        } finally {
            frame.hardwareBuffer.close()
            if (!source.isRecycled) source.recycle()
        }
    }

    private fun cropCenterSquare(source: Bitmap): Bitmap {
        if (source.width == source.height) {
            return source
        }
        val side = minOf(source.width, source.height)
        val targetCenterX = (source.width * QuickCameraManager.getPreviewTargetCenterXRatio()).toInt()
        val targetCenterY = (source.height * QuickCameraManager.getPreviewTargetCenterYRatio()).toInt()
        val left = (targetCenterX - side / 2).coerceIn(0, source.width - side)
        val top = when (QuickCameraManager.getPreviewFramingMode()) {
            QuickCameraManager.PreviewFramingMode.TARGET_CENTER -> {
                (targetCenterY - side / 2).coerceIn(0, source.height - side)
            }
            QuickCameraManager.PreviewFramingMode.BOTTOM -> source.height - side
            QuickCameraManager.PreviewFramingMode.CENTER -> (source.height - side) / 2
        }
        return Bitmap.createBitmap(source, left, top, side, side)
    }

    private fun showResultTip(message: String) {
        tvSaveResult.text = message
        tvSaveResult.visibility = View.VISIBLE
        tvSaveResult.removeCallbacks(hideSaveResultRunnable)
        tvSaveResult.postDelayed(hideSaveResultRunnable, RESULT_TIP_DURATION_MS)
    }

    private val hideSaveResultRunnable = Runnable {
        tvSaveResult.visibility = View.GONE
    }
}
