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
import com.rokid.glass.InspectionFeatureFlags
import com.rokid.glass.camera.QuickCameraManager
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glass.workflow.InspectionWorkflowSession
import com.rokid.glesse.R
import com.rokid.security.glass3.open.sdk.GlassSdk
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
        const val EXTRA_MODE = "extra_mode"
        const val MODE_LIGHTSHOT = "lightshot"
        const val MODE_HAZARD_RECORD = "hazard_record"
        const val EXTRA_DEBUG_STATE = "extra_debug_state"
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
    private lateinit var tvTitle: TextView
    private lateinit var tvFov: TextView
    private lateinit var tvSaveResult: TextView
    private lateinit var tvCountdownLabel: TextView
    private lateinit var tvCountdownValue: TextView
    private lateinit var tvSyncPrompt: TextView

    @Volatile
    private var isCapturing = false

    private var isCameraReady = false
    private var cameraInitInProgress = false
    private var currentFovIndex = 0
    private val shutterSound by lazy { MediaActionSound() }
    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mode: String = MODE_LIGHTSHOT
    private var countdownActive = false
    private var syncPromptVisible = false
    private var hazardRecordUploadInProgress = false
    private var countdownRemaining = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lightshot)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_LIGHTSHOT

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
        tvTitle = findViewById(R.id.tvTitle)
        tvHint = findViewById(R.id.tvHint)
        tvFov = findViewById(R.id.tvFov)
        tvSaveResult = findViewById(R.id.tvSaveResult)
        tvCountdownLabel = findViewById(R.id.tvCountdownLabel)
        tvCountdownValue = findViewById(R.id.tvCountdownValue)
        tvSyncPrompt = findViewById(R.id.tvSyncPrompt)

        shutterSound.load(MediaActionSound.SHUTTER_CLICK)
        syncFovWithManager()
        updateFovTip()
        applyModeUi()
        inputSession.updateActions(buildInputActions())
    }

    override fun onResume() {
        super.onResume()
        if (previewView.isAvailable) {
            initCamera()
        } else {
            tvHint.text = "预览面初始化中..."
        }
        inputSession.attach()
        inputSession.updateActions(buildInputActions())
    }

    override fun onPause() {
        inputSession.detach()
        mainHandler.removeCallbacksAndMessages(null)
        countdownActive = false
        isCameraReady = false
        cameraInitInProgress = false
        QuickCameraManager.detachPreviewTexture()
        QuickCameraManager.releaseCamera()
        super.onPause()
    }

    override fun onDestroy() {
        inputSession.release()
        runCatching { shutterSound.release() }
        QuickCameraManager.detachPreviewTexture()
        QuickCameraManager.releaseCamera()
        Log.d(TAG, "onDestroy: 资源已释放")
        super.onDestroy()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        return inputSession.dispatchTouch(keyEvent) || super.onGlassKeyEvent(keyEvent)
    }

    private fun buildInputActions(): List<UnifiedInputSession.InputActionSpec> {
        val actions = mutableListOf(
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("lightshot_capture"),
                label = if (isHazardRecordMode()) "开始录入" else "拍照",
                triggers = buildList {
                    add(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK))
                    add(UnifiedInputSession.InputTrigger.Voice("拍照", "pai zhao"))
                    if (isHazardRecordMode()) {
                        add(UnifiedInputSession.InputTrigger.Voice("录入", "lu ru"))
                    }
                },
                enabled = { !countdownActive && !syncPromptVisible && !hazardRecordUploadInProgress },
            ) {
                if (isHazardRecordMode()) {
                    startHazardCountdown()
                } else {
                    captureAndSave()
                }
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("lightshot_zoom_out"),
                label = "放大视野",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.FRONT),
                    UnifiedInputSession.InputTrigger.Voice("缩小", "suo xiao"),
                ),
                enabled = { !isHazardRecordMode() && !syncPromptVisible && !countdownActive && !hazardRecordUploadInProgress },
            ) {
                adjustFov(-1)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("lightshot_zoom_in"),
                label = "缩小视野",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BEHIND),
                    UnifiedInputSession.InputTrigger.Voice("放大", "fang da"),
                ),
                enabled = { !isHazardRecordMode() && !syncPromptVisible && !countdownActive && !hazardRecordUploadInProgress },
            ) {
                adjustFov(1)
            },
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Exit,
                label = "退出",
                triggers = listOf(
                    UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK),
                    UnifiedInputSession.InputTrigger.Voice("退出", "tui chu"),
                ),
                enabled = { !syncPromptVisible || !isHazardRecordMode() },
            ) {
                finish()
            },
        )
        if (isHazardRecordMode()) {
            actions += UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Confirm,
                label = "确认",
                triggers = UnifiedInputSession.buildConfirmTriggers(enableHeadGesture = false),
                enabled = { syncPromptVisible },
            ) {
                resetHazardRecordUi()
            }
            actions += UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId.Cancel,
                label = "取消",
                triggers = UnifiedInputSession.buildCancelTriggers(enableHeadGesture = false),
                enabled = { syncPromptVisible },
            ) {
                finish()
            }
        }
        return actions
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
        QuickCameraManager.attachPreviewTexture(surface, width, height)
        QuickCameraManager.initialize(
            size = QUICK_CAPTURE_SIZE,
            quickCapture = true,
        ) { streamReady ->
            runOnUiThread {
                cameraInitInProgress = false
                isCameraReady = streamReady
                if (streamReady) {
                    // 等 GPU 预览 session ready 后再更新 zoom，避免将未配置的预览 Surface 塞入 repeating request。
                    val appliedZoom = QuickCameraManager.setPreviewZoomRatio(FOV_LEVELS[currentFovIndex])
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
                        handleCaptureFinished(result, latencyMs)
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
        if (isHazardRecordMode()) {
            tvSaveResult.text = message
            tvSaveResult.visibility = View.VISIBLE
            return
        }
        tvSaveResult.text = message
        tvSaveResult.visibility = View.VISIBLE
        tvSaveResult.removeCallbacks(hideSaveResultRunnable)
        tvSaveResult.postDelayed(hideSaveResultRunnable, RESULT_TIP_DURATION_MS)
    }

    private val hideSaveResultRunnable = Runnable {
        tvSaveResult.visibility = View.GONE
    }

    private fun handleCaptureFinished(result: File?, latencyMs: Long) {
        if (result != null) {
            shutterSound.play(MediaActionSound.SHUTTER_CLICK)
            Log.i(TAG, "GPU 帧保存成功: ${result.absolutePath}, 延迟=${latencyMs}ms")
            if (isHazardRecordMode()) {
                startHazardRecordUpload(result)
            } else {
                showResultTip("已保存：${result.name} (延迟${latencyMs}ms)")
                tvHint.setText(R.string.lightshot_hint)
            }
        } else {
            showResultTip("保存失败，请重试")
            tvHint.text = if (isHazardRecordMode()) {
                getString(R.string.hazard_record_idle_hint)
            } else {
                getString(R.string.lightshot_hint)
            }
        }
        refreshActions()
    }

    private fun startHazardRecordUpload(result: File) {
        if (!InspectionFeatureFlags.isEnterpriseInspectionFlowEnabled()) {
            hazardRecordUploadInProgress = false
            syncPromptVisible = true
            tvSaveResult.text = "隐患照片已保存，未执行上传"
            tvSaveResult.visibility = View.VISIBLE
            tvSyncPrompt.visibility = View.VISIBLE
            tvHint.text = "当前为本地模式，可继续录入"
            refreshActions()
            return
        }
        hazardRecordUploadInProgress = true
        syncPromptVisible = false
        tvSaveResult.text = "隐患照片同步中..."
        tvSaveResult.visibility = View.VISIBLE
        tvSyncPrompt.visibility = View.GONE
        tvHint.text = "正在上传隐患照片，请稍候"
        refreshActions()
        HazardRecordUploadService.uploadHazardRecord(
            imageFile = result,
            snCode = RokidSdkManager.getSerialNumber(),
            callback = object : HazardRecordUploadService.Callback {
                override fun onSuccess(result: HazardRecordUploadService.UploadResult) {
                    if (isFinishing || isDestroyed) return
                    hazardRecordUploadInProgress = false
                    syncPromptVisible = true
                    InspectionWorkflowSession.recordHazardRecordUpload(result.sessionId)
                    tvSaveResult.text = getString(R.string.hazard_record_sync_success)
                    tvSaveResult.visibility = View.VISIBLE
                    tvSyncPrompt.visibility = View.VISIBLE
                    tvHint.setText(R.string.hazard_record_continue_hint)
                    refreshActions()
                }

                override fun onError(message: String) {
                    if (isFinishing || isDestroyed) return
                    hazardRecordUploadInProgress = false
                    syncPromptVisible = false
                    tvSaveResult.text = message
                    tvSaveResult.visibility = View.VISIBLE
                    tvSyncPrompt.visibility = View.GONE
                    tvHint.setText(R.string.hazard_record_idle_hint)
                    refreshActions()
                }
            },
        )
    }

    private fun startHazardCountdown() {
        if (countdownActive || syncPromptVisible || hazardRecordUploadInProgress) return
        countdownActive = true
        countdownRemaining = 3
        tvCountdownLabel.visibility = View.VISIBLE
        tvCountdownValue.visibility = View.VISIBLE
        tvSaveResult.visibility = View.GONE
        tvSyncPrompt.visibility = View.GONE
        tvHint.setText(R.string.hazard_record_countdown_subtitle)
        tickHazardCountdown()
        refreshActions()
    }

    private fun tickHazardCountdown() {
        if (!countdownActive) return
        tvCountdownValue.text = countdownRemaining.toString()
        if (countdownRemaining <= 0) {
            countdownActive = false
            tvCountdownLabel.visibility = View.GONE
            tvCountdownValue.visibility = View.GONE
            captureAndSave()
            return
        }
        countdownRemaining -= 1
        mainHandler.postDelayed({ tickHazardCountdown() }, 1000L)
    }

    private fun resetHazardRecordUi() {
        hazardRecordUploadInProgress = false
        syncPromptVisible = false
        tvSaveResult.visibility = View.GONE
        tvSyncPrompt.visibility = View.GONE
        tvHint.setText(R.string.hazard_record_idle_hint)
        refreshActions()
    }

    private fun applyModeUi() {
        if (isHazardRecordMode()) {
            tvTitle.setText(R.string.hazard_record_title)
            tvHint.setText(R.string.hazard_record_idle_hint)
            tvFov.visibility = View.GONE
            intent.getStringExtra(EXTRA_DEBUG_STATE)?.takeIf { it == "success" }?.let {
                syncPromptVisible = true
                tvSaveResult.setText(R.string.hazard_record_sync_success)
                tvSaveResult.visibility = View.VISIBLE
                tvSyncPrompt.visibility = View.VISIBLE
                tvHint.setText(R.string.hazard_record_continue_hint)
            }
        } else {
            tvTitle.setText(R.string.lightshot_title)
            tvHint.setText(R.string.lightshot_hint)
            tvFov.visibility = View.VISIBLE
        }
    }

    private fun refreshActions() {
        inputSession.updateActions(buildInputActions())
    }

    private fun isHazardRecordMode(): Boolean = mode == MODE_HAZARD_RECORD
}
