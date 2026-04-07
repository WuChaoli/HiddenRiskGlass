package com.rokid.glass.hiddenrisk

import android.graphics.Bitmap
import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.TextView
import com.rokid.glass.camera.QuickCameraManager
import com.rokid.glesse.R
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
    }

    private lateinit var tvHint: TextView
    private lateinit var tvSaveResult: TextView

    /** 相机是否已初始化就绪 */
    private var isCameraReady = false
    /** 是否正在处理拍摄（防止连续重复触发） */
    @Volatile
    private var isCapturing = false

    /** 系统快门音效播放器 */
    private val shutterSound by lazy { MediaActionSound() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lightshot)

        tvHint = findViewById(R.id.tvHint)
        tvSaveResult = findViewById(R.id.tvSaveResult)

        // 预加载快门音，避免首次播放延迟
        shutterSound.load(MediaActionSound.SHUTTER_CLICK)

        initCamera()
    }

    /** 初始化相机（quickCapture 模式，复用 QuickCameraManager 的常驻预览帧能力） */
    private fun initCamera() {
        tvHint.text = "相机初始化中..."
        QuickCameraManager.initialize(quickCapture = true) { success ->
            runOnUiThread {
                isCameraReady = success
                tvHint.text = if (success) "单击右触控板拍摄" else "相机初始化失败，请退出重试"
                Log.d(TAG, "camera init success=$success")
            }
        }
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
            GlassKeyEvent.KEYCODE_BACK -> {
                finish()
                true
            }
            else -> super.onGlassKeyEvent(keyEvent)
        }
    }

    // ──────────────────────────────────────────────
    // 核心拍摄逻辑
    // ──────────────────────────────────────────────

    /**
     * 触发一次拍摄：
     * 1. 调用 QuickCameraManager.takeGpuFrame() 获取当前预览帧
     * 2. 取 previewBitmap（已旋转校正）
     * 3. 居中裁剪为正方形 → 缩放为 640×640
     * 4. 保存为 JPEG Q90 到 lightshot/ 目录
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

        QuickCameraManager.takeGpuFrame { gpuFrame ->
            val previewBitmap = gpuFrame?.previewBitmap
            if (previewBitmap == null) {
                Log.w(TAG, "takeGpuFrame: previewBitmap 为空，尝试直接使用 HardwareBuffer 失败，放弃本次采集")
                runOnUiThread {
                    isCapturing = false
                    tvHint.text = "单击右触控板拍摄"
                    showResultTip("采集失败，请重试")
                }
                gpuFrame?.hardwareBuffer?.close()
                return@takeGpuFrame
            }

            // 在 IO 线程完成图像处理和文件写入
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val result = processBitmapAndSave(previewBitmap)
                    withContext(Dispatchers.Main) {
                        if (result != null) {
                            // 播放系统快门音
                            shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                            showResultTip("已保存：${result.name}")
                            Log.i(TAG, "保存成功: ${result.absolutePath}")
                        } else {
                            showResultTip("保存失败，请重试")
                        }
                        tvHint.text = "单击右触控板拍摄"
                        isCapturing = false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "图像处理异常", e)
                    withContext(Dispatchers.Main) {
                        showResultTip("保存失败：${e.message}")
                        tvHint.text = "单击右触控板拍摄"
                        isCapturing = false
                    }
                } finally {
                    // 释放 HardwareBuffer 引用
                    gpuFrame.hardwareBuffer.close()
                }
            }
        }
    }

    /**
     * 将 previewBitmap 处理为 640×640 并写入文件。
     * @return 保存成功的 File，失败返回 null
     */
    private fun processBitmapAndSave(source: Bitmap): File? {
        // 1. 居中裁剪为正方形（取短边）
        val squareBitmap = cropToSquare(source)

        // 2. 缩放为 640×640（若本身已是 640 则 createScaledBitmap 直接返回原对象）
        val scaledBitmap = if (squareBitmap.width == OUTPUT_SIZE && squareBitmap.height == OUTPUT_SIZE) {
            squareBitmap
        } else {
            Bitmap.createScaledBitmap(squareBitmap, OUTPUT_SIZE, OUTPUT_SIZE, true)
        }

        // 3. 确保输出目录存在
        val saveDir = File(
            Environment.getExternalStorageDirectory(),
            SAVE_DIR_NAME
        )
        if (!saveDir.exists() && !saveDir.mkdirs()) {
            Log.e(TAG, "无法创建目录: ${saveDir.absolutePath}")
            return null
        }

        // 4. 以时间戳生成文件名（精确到毫秒，避免连续拍摄重名）
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.getDefault()).format(Date())
        val outputFile = File(saveDir, "$timestamp.jpg")

        // 5. 写入 JPEG 文件
        FileOutputStream(outputFile).use { out ->
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.flush()
        }

        // 6. 回收中间 Bitmap（避免内存泄漏）
        if (squareBitmap !== source && !squareBitmap.isRecycled) squareBitmap.recycle()
        if (scaledBitmap !== squareBitmap && !scaledBitmap.isRecycled) scaledBitmap.recycle()

        // 7. 通知媒体扫描（让文件管理器、PC 端能看到）
        val mediaScanIntent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
        mediaScanIntent.data = Uri.fromFile(outputFile)
        sendBroadcast(mediaScanIntent)

        return outputFile
    }

    /**
     * 将 Bitmap 从中心裁剪为正方形（取短边长度）。
     */
    private fun cropToSquare(source: Bitmap): Bitmap {
        val side = minOf(source.width, source.height)
        val x = (source.width - side) / 2
        val y = (source.height - side) / 2
        return if (x == 0 && y == 0 && source.width == side) {
            // 已经是正方形，不需要裁剪
            source
        } else {
            Bitmap.createBitmap(source, x, y, side, side)
        }
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
        super.onDestroy()
        // 释放快门音资源
        runCatching { shutterSound.release() }
        // 释放相机资源
        QuickCameraManager.releaseCamera()
        Log.d(TAG, "onDestroy: 资源已释放")
    }
}
