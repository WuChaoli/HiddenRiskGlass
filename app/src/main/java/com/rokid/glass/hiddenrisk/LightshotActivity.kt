package com.rokid.glass.hiddenrisk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.view.View
import android.view.TextureView
import android.widget.TextView
import com.rokid.glass.camera.QuickCameraManager
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
 * 闪拍页面：展示绿色取景框，单击触控板时采集当前相机帧，
 * 裁剪并缩放为 640×640 JPEG（质量 90），保存到
 * /storage/emulated/0/lightshot/ 目录，以时间戳命名。
 * 用于批量采集模型测试样本图片。
 */
class LightshotActivity : BaseGlassActivity() {

    companion object {
        private const val TAG = "LightshotActivity"
        /** 保存目录名称 */
        private const val SAVE_DIR_NAME = "lightshot"
        /** 输出图片边长（正方形） */
        private const val OUTPUT_SIZE = 640
        /** JPEG 压缩质量 */
        private const val JPEG_QUALITY = 90
        /** 保存结果提示显示时长（毫秒） */
        private const val RESULT_TIP_DURATION_MS = 2000L
        private val FOV_LEVELS = floatArrayOf(1.0f, 1.2f, 1.5f, 1.8f, 2.2f, 2.6f, 3.0f)
    }

    private lateinit var previewTexture: TextureView
    private lateinit var tvHint: TextView
    private lateinit var tvFov: TextView
    private lateinit var tvSaveResult: TextView

    /** 相机是否已初始化就绪 */
    private var isCameraReady = false
    private var isPreviewTextureReady = false
    private var currentFovIndex = 0
    /** 是否正在处理拍摄（防止连续重复触发） */
    @Volatile
    private var isCapturing = false

    /** 系统快门音效播放器 */
    private val shutterSound by lazy { MediaActionSound() }

    // ─────────────────────────────────────��────────
    // 语音指令
    // ──────────────────────────────────────────────

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

    /** 尝试注册语音指令，未就绪时自动重试 */
    private val voiceRegisterRunnable = object : Runnable {
        override fun run() {
            if (registerVoiceCommandsIfReady()) return
            voiceHandler.postDelayed(this, 500L)
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
        }.onFailure { e -> Log.w(TAG, "注销语音指令失败: ${e.message}") }
        voiceRegistered = false
        Log.i(TAG, "语音指令已注销")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lightshot)

        previewTexture = findViewById(R.id.previewTexture)
        tvHint = findViewById(R.id.tvHint)
        tvFov = findViewById(R.id.tvFov)
        tvSaveResult = findViewById(R.id.tvSaveResult)

        // 预加载快门音，避免首次播放延迟
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)

        syncFovWithManager()
        bindPreviewTexture()
        updateFovTip()
    }

    override fun onResume() {
        super.onResume()
        voiceHandler.removeCallbacks(voiceRegisterRunnable)
        voiceHandler.post(voiceRegisterRunnable)
    }

    override fun onPause() {
        voiceHandler.removeCallbacks(voiceRegisterRunnable)
        unregisterVoiceCommands()
        super.onPause()
    }

    /** 初始化相机（可视预览 + JPEG 拍照模式） */
    private fun initCamera() {
        if (!isPreviewTextureReady || !previewTexture.isAvailable) {
            tvHint.text = "预览初始化中..."
            return
        }
        tvHint.text = "相机初始化中..."

        // 防御性清理：强制释放可能残留的相机资源，避免状态不一致
        QuickCameraManager.releaseCamera()
        QuickCameraManager.attachPreviewTexture(
            previewTexture.surfaceTexture ?: return,
            previewTexture.width.coerceAtLeast(1),
            previewTexture.height.coerceAtLeast(1),
        )

        // 延迟初始化，确保系统相机服务完全释放资源
        tvHint.postDelayed({
            QuickCameraManager.initialize(
                quickCapture = false,
                stillCaptureOnly = false,
            ) { success ->
                runOnUiThread {
                    isCameraReady = success
                    if (success) {
                        QuickCameraManager.setPreviewZoomRatio(FOV_LEVELS[currentFovIndex])
                        updatePreviewTransform()
                        tvHint.text = "单击拍摄，左右滑动调视野"
                    } else {
                        tvHint.text = "相机初始化失败，请退出重试"
                    }
                    Log.d(TAG, "preview camera init success=$success")
                }
            }
        }, 300)
    }

    private fun bindPreviewTexture() {
        previewTexture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                isPreviewTextureReady = true
                initCamera()
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                QuickCameraManager.attachPreviewTexture(surface, width, height)
                updatePreviewTransform()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                isPreviewTextureReady = false
                isCameraReady = false
                QuickCameraManager.releaseCamera()
                QuickCameraManager.detachPreviewTexture()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        if (previewTexture.isAvailable) {
            isPreviewTextureReady = true
            initCamera()
        }
    }

    private fun updatePreviewTransform() {
        val streamSize = QuickCameraManager.getPreviewStreamSize() ?: return
        val viewWidth = previewTexture.width
        val viewHeight = previewTexture.height
        if (viewWidth <= 0 || viewHeight <= 0) {
            return
        }

        val matrix = Matrix()
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
        val streamAspect = streamSize.width.toFloat() / streamSize.height.toFloat()
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f

        val scaleX: Float
        val scaleY: Float
        if (streamAspect > viewAspect) {
            scaleX = streamAspect / viewAspect
            scaleY = 1f
        } else {
            scaleX = 1f
            scaleY = viewAspect / streamAspect
        }
        matrix.setScale(scaleX, scaleY, centerX, centerY)
        previewTexture.setTransform(matrix)
        Log.d(
            TAG,
            "updatePreviewTransform view=${viewWidth}x${viewHeight} stream=${streamSize.width}x${streamSize.height} scaleX=$scaleX scaleY=$scaleY",
        )
    }

    // ──────────────────────────────────────────────
    // 按键事件处理
    // ──────────────────────────────────────────────

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

    private fun updateFovTip(zoomRatio: Float = FOV_LEVELS[currentFovIndex]) {
        tvFov.text = "视野 ${"%.1f".format(Locale.US, zoomRatio)}x"
    }

    private fun syncFovWithManager() {
        val zoomRatio = QuickCameraManager.getPreviewZoomRatio()
        currentFovIndex = FOV_LEVELS.indices.minByOrNull { index ->
            kotlin.math.abs(FOV_LEVELS[index] - zoomRatio)
        } ?: 0
    }

    // ──────────────────────────────────────────────
    // 核心拍摄逻辑
    // ──────────────────────────────────────────────

    /**
     * 触发一次拍摄（普通 JPEG 模式）：
     * 1. 使用 QuickCameraManager.takePicture() 获取 JPEG 文件
     * 2. 解码并缩放为 640×640
     * 3. 保存为 JPEG Q90 到 lightshot/ 目录
     * 4. 删除中间临时文件
     * 5. 播放快门音，显示保存结果提示
     */
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

        QuickCameraManager.takePicture { capturedFile ->
            val latencyNs = System.nanoTime() - startTime
            val latencyMs = latencyNs / 1_000_000

            if (capturedFile == null) {
                Log.w(TAG, "JPEG 拍照失败")
                runOnUiThread {
                    isCapturing = false
                    tvHint.text = "单击拍摄，左右滑动调视野"
                    showResultTip("采集失败，请重试")
                }
                return@takePicture
            }

            Log.i(TAG, "JPEG 拍照成功: ${capturedFile.name}, 延迟=${latencyMs}ms")

            // 在 IO 线程完成图像压缩和文件写入
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val result = processCapturedFileAndSave(capturedFile, latencyMs)
                    withContext(Dispatchers.Main) {
                        if (result != null) {
                            // 播放系统快门音
                            shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                            showResultTip("已保存：${result.name} (延迟${latencyMs}ms)")
                            Log.i(TAG, "JPEG 保存成功: ${result.absolutePath}, 延迟=${latencyMs}ms")
                        } else {
                            showResultTip("保存失败，请重试")
                        }
                        tvHint.text = "单击拍摄，左右滑动调视野"
                        isCapturing = false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "JPEG 图像处理异常", e)
                    withContext(Dispatchers.Main) {
                        showResultTip("保存失败：${e.message}")
                        tvHint.text = "单击拍摄，左右滑动调视野"
                        isCapturing = false
                    }
                } finally {
                    runCatching {
                        if (capturedFile.exists()) {
                            capturedFile.delete()
                        }
                    }
                }
            }
        }
    }

    /**
     * 处理 JPEG 文件 (原始分辨率 → 640×640) 并写入目标目录
     * @param capturedFile 拍照生成的临时 JPEG 文件
     * @param latencyMs 拍照延迟（毫秒）
     * @return 保存成功的 File，失败返回 null
     */
    private fun processCapturedFileAndSave(capturedFile: File, latencyMs: Long): File? {
        val source = BitmapFactory.decodeFile(capturedFile.absolutePath) ?: run {
            Log.e(TAG, "processCapturedFileAndSave: decodeFile failed path=${capturedFile.absolutePath}")
            return null
        }
        Log.d(TAG, "processCapturedFileAndSave: 输入 ${source.width}x${source.height}, 延迟=${latencyMs}ms")
        val squareBitmap = cropCenterSquare(source)
        val outputBitmap = Bitmap.createScaledBitmap(squareBitmap, OUTPUT_SIZE, OUTPUT_SIZE, true)

        // 2. 确保输出目录存在
        val saveDir = File(
            Environment.getExternalStorageDirectory(),
            SAVE_DIR_NAME
        )
        if (!saveDir.exists() && !saveDir.mkdirs()) {
            Log.e(TAG, "无法创建目录: ${saveDir.absolutePath}")
            return null
        }

        // 3. 以时间戳生成文件名（包含延迟信息）
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.getDefault()).format(Date())
        val filePrefix = "${timestamp}_JPEG${latencyMs}ms"
        val outputFile = File(saveDir, "$filePrefix.jpg")

        // 4. 写入 JPEG 文件
        FileOutputStream(outputFile).use { out ->
            outputBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.flush()
        }

        // 5. 回收中间 Bitmap
        if (!outputBitmap.isRecycled) outputBitmap.recycle()
        if (squareBitmap !== source && !squareBitmap.isRecycled) squareBitmap.recycle()
        if (!source.isRecycled) source.recycle()

        // 6. 通知媒体扫描
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
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        return Bitmap.createBitmap(source, left, top, side, side)
    }

    // ──────────────────────────────────────────────
    // UI 辅助
    // ──────────────────────────────────────────────

    /** 短暂显示保存结果提示文字，2 秒后自动隐藏 */
    private fun showResultTip(message: String) {
        tvSaveResult.text = message
        tvSaveResult.visibility = View.VISIBLE
        tvSaveResult.removeCallbacks(hideSaveResultRunnable)
        tvSaveResult.postDelayed(hideSaveResultRunnable, RESULT_TIP_DURATION_MS)
    }

    private val hideSaveResultRunnable = Runnable {
        tvSaveResult.visibility = View.GONE
    }

    // ──────────────────────────────────────────────
    // 生命周期
    // ──────────────────────────────────────────────

    override fun onDestroy() {
        voiceHandler.removeCallbacks(voiceRegisterRunnable)
        unregisterVoiceCommands()
        super.onDestroy()
        // 释放快门音资源
        runCatching { shutterSound.release() }
        // 释放相机资源
        QuickCameraManager.releaseCamera()
        QuickCameraManager.detachPreviewTexture()
        Log.d(TAG, "onDestroy: 资源已释放")
    }
}
