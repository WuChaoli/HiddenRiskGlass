package com.rokid.glass.hiddenrisk

import android.graphics.Bitmap
import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import com.rokid.glass.camera.RokidFrameSource
import com.rokid.glass.component.RokidCameraPreviewView
import com.rokid.glass.utils.BitmapUtils
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
    }

    private lateinit var previewView: RokidCameraPreviewView
    private lateinit var tvHint: TextView
    private lateinit var tvFov: TextView
    private lateinit var tvSaveResult: TextView

    @Volatile
    private var isCapturing = false

    private var isCameraReady = false
    private var currentFovIndex = 0
    private val shutterSound by lazy { MediaActionSound() }

    private val voiceHandler = Handler(Looper.getMainLooper())
    private var voiceRegistered = false

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
        tvHint = findViewById(R.id.tvHint)
        tvFov = findViewById(R.id.tvFov)
        tvSaveResult = findViewById(R.id.tvSaveResult)

        shutterSound.load(MediaActionSound.SHUTTER_CLICK)
        syncFovWithManager()
        updateFovTip()
    }

    override fun onResume() {
        super.onResume()
        initCamera()
        voiceHandler.removeCallbacks(voiceRegisterRunnable)
        voiceHandler.post(voiceRegisterRunnable)
    }

    override fun onPause() {
        voiceHandler.removeCallbacks(voiceRegisterRunnable)
        unregisterVoiceCommands()
        isCameraReady = false
        previewView.stopPreview()
        previewView.onPause()
        RokidFrameSource.stopFrameStream()
        super.onPause()
    }

    override fun onDestroy() {
        voiceHandler.removeCallbacks(voiceRegisterRunnable)
        unregisterVoiceCommands()
        runCatching { shutterSound.release() }
        RokidFrameSource.releaseAll()
        Log.d(TAG, "onDestroy: 资源已释放")
        super.onDestroy()
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

    private fun initCamera() {
        if (!GlassSdk.isReady()) {
            tvHint.text = "SDK 未就绪，请稍后重试"
            return
        }

        tvHint.text = "相机初始化中..."
        isCameraReady = false
        currentFovIndex = FOV_LEVELS.indexOfFirst { it == DEFAULT_ZOOM_RATIO }.coerceAtLeast(0)

        RokidFrameSource.setPreviewFramingMode(RokidFrameSource.PreviewFramingMode.CENTER)
        val appliedZoom = RokidFrameSource.setPreviewZoomRatio(DEFAULT_ZOOM_RATIO)

        previewView.onResume()
        previewView.startPreview { previewReady ->
            runOnUiThread {
                if (!previewReady) {
                    tvHint.text = "预览初始化失败，请退出重试"
                    return@runOnUiThread
                }
                RokidFrameSource.startFrameStream { streamReady ->
                    runOnUiThread {
                        isCameraReady = streamReady
                        if (streamReady) {
                            updateFovTip(appliedZoom)
                            tvHint.text = "单击拍摄，左右滑动调视野"
                        } else {
                            tvHint.text = "视频流初始化失败，请退出重试"
                        }
                        Log.d(TAG, "lightshot init previewReady=$previewReady streamReady=$streamReady")
                    }
                }
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
        val appliedZoom = RokidFrameSource.setPreviewZoomRatio(FOV_LEVELS[currentFovIndex])
        updateFovTip(appliedZoom)
        showResultTip("视野 ${"%.1f".format(Locale.US, appliedZoom)}x")
    }

    private fun updateFovTip(zoomRatio: Float = FOV_LEVELS[currentFovIndex]) {
        tvFov.text = "视野 ${"%.1f".format(Locale.US, zoomRatio)}x"
    }

    private fun syncFovWithManager() {
        val zoomRatio = RokidFrameSource.getAppliedPreviewZoomRatio()
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

        val frame = RokidFrameSource.copyLatestFrame()
        if (frame == null) {
            showResultTip("当前无可用视频帧")
            return
        }

        isCapturing = true
        tvHint.text = "采集中..."
        val startTime = System.nanoTime()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = processFrameAndSave(frame)
                val latencyMs = (System.nanoTime() - startTime) / 1_000_000
                withContext(Dispatchers.Main) {
                    if (result != null) {
                        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                        showResultTip("已保存：${result.name} (延迟${latencyMs}ms)")
                        Log.i(TAG, "视频帧保存成功: ${result.absolutePath}, 延迟=${latencyMs}ms")
                    } else {
                        showResultTip("保存失败，请重试")
                    }
                    tvHint.text = "单击拍摄，左右滑动调视野"
                    isCapturing = false
                }
            } catch (error: Exception) {
                Log.e(TAG, "视频帧处理异常", error)
                withContext(Dispatchers.Main) {
                    showResultTip("保存失败：${error.message}")
                    tvHint.text = "单击拍摄，左右滑动调视野"
                    isCapturing = false
                }
            }
        }
    }

    private fun processFrameAndSave(frame: RokidFrameSource.Nv21Frame): File? {
        val source = BitmapUtils.nv21ToBitmap(frame.data, frame.width, frame.height) ?: run {
            Log.e(TAG, "processFrameAndSave: nv21ToBitmap failed width=${frame.width} height=${frame.height}")
            return null
        }

        val squareBitmap = cropCenterSquare(source)
        val outputBitmap = Bitmap.createScaledBitmap(squareBitmap, OUTPUT_SIZE, OUTPUT_SIZE, true)

        val saveDir = File(Environment.getExternalStorageDirectory(), SAVE_DIR_NAME)
        if (!saveDir.exists() && !saveDir.mkdirs()) {
            Log.e(TAG, "无法创建目录: ${saveDir.absolutePath}")
            return null
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.getDefault()).format(Date())
        val outputFile = File(saveDir, "${timestamp}_SDK.jpg")
        FileOutputStream(outputFile).use { out ->
            outputBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.flush()
        }

        if (!outputBitmap.isRecycled) outputBitmap.recycle()
        if (squareBitmap !== source && !squareBitmap.isRecycled) squareBitmap.recycle()
        if (!source.isRecycled) source.recycle()

        val mediaScanIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
        mediaScanIntent.data = Uri.fromFile(outputFile)
        sendBroadcast(mediaScanIntent)

        return outputFile
    }

    private fun cropCenterSquare(source: Bitmap): Bitmap {
        if (source.width == source.height) {
            return source
        }
        val side = minOf(source.width, source.height)
        val targetCenterX = (source.width * RokidFrameSource.getPreviewTargetCenterXRatio()).toInt()
        val targetCenterY = (source.height * RokidFrameSource.getPreviewTargetCenterYRatio()).toInt()
        val left = (targetCenterX - side / 2).coerceIn(0, source.width - side)
        val top = when (RokidFrameSource.getPreviewFramingMode()) {
            RokidFrameSource.PreviewFramingMode.TARGET_CENTER -> {
                (targetCenterY - side / 2).coerceIn(0, source.height - side)
            }
            RokidFrameSource.PreviewFramingMode.BOTTOM -> source.height - side
            RokidFrameSource.PreviewFramingMode.CENTER -> (source.height - side) / 2
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
