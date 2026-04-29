package com.rokid.glass.hiddenrisk

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 隐患识别结果保存服务
 * 负责保存原始图片和推理结果JSON，并自动清理旧文件
 */
class HazardCaptureService(private val context: Context) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /**
     * 隐患检测结果数据类（用于JSON序列化）
     */
    data class HazardDetectionRecord(
        @SerializedName("timestamp") val timestamp: String,
        @SerializedName("backend_name") val backendName: String,
        @SerializedName("inference_time_ms") val inferenceTimeMs: Long,
        @SerializedName("image_width") val imageWidth: Int,
        @SerializedName("image_height") val imageHeight: Int,
        @SerializedName("detection_count") val detectionCount: Int,
        @SerializedName("detections") val detections: List<DetectionRecord>
    )

    /**
     * 单个检测框记录
     */
    data class DetectionRecord(
        @SerializedName("label") val label: String,
        @SerializedName("label_id") val labelId: Int,
        @SerializedName("x") val x: Float,
        @SerializedName("y") val y: Float,
        @SerializedName("width") val width: Float,
        @SerializedName("height") val height: Float,
        @SerializedName("score") val score: Float
    )

    /**
     * 保存隐患识别结果（图片 + JSON）
     * @param jpegBytes 已编码的 JPEG 图片
     * @param stats 推理统计信息
     */
    fun saveHazardCapture(jpegBytes: ByteArray?, stats: NativeInferenceStats?) {
        if (jpegBytes == null || jpegBytes.isEmpty()) {
            Log.w(TAG, "无法保存：jpegBytes 为空")
            return
        }
        if (stats == null) {
            Log.w(TAG, "无法保存：stats 为空")
            return
        }

        executor.execute {
            try {
                val captureDir = getCaptureDirectory()
                if (!captureDir.exists()) {
                    captureDir.mkdirs()
                }

                val timestamp = System.currentTimeMillis()
                val timeString = DATE_FORMAT.format(Date(timestamp))
                val filePrefix = "hazard_${timestamp}_"

                // 保存图片
                val imageFile = File(captureDir, "${filePrefix}.jpg")
                saveJpegBytesToFile(jpegBytes, imageFile)

                // 保存JSON
                val jsonFile = File(captureDir, "${filePrefix}.json")
                saveDetectionResultToFile(stats, timeString, jsonFile)

                Log.i(TAG, "隐患识别结果已保存: ${imageFile.name}")

                // 清理旧文件
                cleanupOldFiles(captureDir)

            } catch (e: Exception) {
                Log.e(TAG, "保存隐患识别结果失败", e)
            }
        }
    }

    /**
     * 兼容仍以 Bitmap 作为输入的旧调用方。
     */
    fun saveHazardCapture(bitmap: Bitmap?, stats: NativeInferenceStats?) {
        if (bitmap == null || bitmap.isRecycled) {
            Log.w(TAG, "无法保存：bitmap 为空或已回收")
            return
        }

        val jpegBytes = runCatching {
            java.io.ByteArrayOutputStream().use { out ->
                if (bitmap.compress(Bitmap.CompressFormat.JPEG, LEGACY_BITMAP_JPEG_QUALITY, out)) {
                    out.toByteArray()
                } else {
                    null
                }
            }
        }.getOrNull()

        saveHazardCapture(jpegBytes, stats)
    }

    /**
     * 获取保存目录
     */
    private fun getCaptureDirectory(): File {
        return File(context.getExternalFilesDir(null), CAPTURE_DIR_NAME)
    }

    /**
     * 保存 JPEG 到文件
     */
    private fun saveJpegBytesToFile(jpegBytes: ByteArray, file: File) {
        FileOutputStream(file).use { out ->
            out.write(jpegBytes)
            out.flush()
        }
    }

    /**
     * 保存检测结果到JSON文件
     */
    private fun saveDetectionResultToFile(
        stats: NativeInferenceStats,
        timeString: String,
        file: File
    ) {
        val detectionRecords = stats.detections?.map { detection ->
            DetectionRecord(
                label = detection.label,
                labelId = detection.labelId,
                x = detection.x,
                y = detection.y,
                width = detection.width,
                height = detection.height,
                score = detection.score
            )
        } ?: emptyList()

        val record = HazardDetectionRecord(
            timestamp = timeString,
            backendName = stats.backendName ?: "Unknown",
            inferenceTimeMs = stats.inferenceTimeMs,
            imageWidth = stats.imageWidth,
            imageHeight = stats.imageHeight,
            detectionCount = stats.detectionCount,
            detections = detectionRecords
        )

        val json = gson.toJson(record)
        file.writeText(json)
    }

    /**
     * 清理旧文件，只保留最近的 MAX_CAPTURE_COUNT 张图片及其对应的JSON
     */
    private fun cleanupOldFiles(directory: File) {
        try {
            // 获取所有图片文件，按修改时间排序
            val imageFiles = directory.listFiles { file ->
                file.isFile && file.name.endsWith(".jpg")
            }?.sortedBy { it.lastModified() } ?: return

            if (imageFiles.size <= MAX_CAPTURE_COUNT) {
                return
            }

            // 删除超出限制的旧文件
            val filesToDelete = imageFiles.take(imageFiles.size - MAX_CAPTURE_COUNT)
            var deletedCount = 0

            for (imageFile in filesToDelete) {
                // 删除图片文件
                if (imageFile.delete()) {
                    deletedCount++
                }

                // 删除对应的JSON文件
                val jsonFileName = imageFile.name.replace(".jpg", ".json")
                val jsonFile = File(directory, jsonFileName)
                if (jsonFile.exists()) {
                    jsonFile.delete()
                }
            }

            Log.i(TAG, "已清理旧文件: $deletedCount 个")

        } catch (e: Exception) {
            Log.e(TAG, "清理旧文件失败", e)
        }
    }

    /**
     * 导出最近的隐患图片到公共目录（DCIM/HazardCaptures/）
     * @param count 要导出的图片数量（最近的几张）
     * @return 导出的文件列表
     */
    fun exportRecentCapturesToPublicDir(count: Int = 10): List<File> {
        val captureDir = getCaptureDirectory()
        if (!captureDir.exists()) {
            return emptyList()
        }

        // 获取最近的图片文件
        val imageFiles = captureDir.listFiles { file ->
            file.isFile && file.name.endsWith(".jpg")
        }?.sortedByDescending { it.lastModified() }?.take(count) ?: return emptyList()

        val exportedFiles = mutableListOf<File>()

        // 创建公共目录
        val publicDir = File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DCIM), "HazardCaptures")
        if (!publicDir.exists()) {
            publicDir.mkdirs()
        }

        for (imageFile in imageFiles) {
            try {
                val destFile = File(publicDir, imageFile.name)
                imageFile.copyTo(destFile, overwrite = true)
                exportedFiles.add(destFile)

                // 同时复制对应的JSON文件
                val jsonFileName = imageFile.name.replace(".jpg", ".json")
                val jsonFile = File(captureDir, jsonFileName)
                if (jsonFile.exists()) {
                    val destJsonFile = File(publicDir, jsonFileName)
                    jsonFile.copyTo(destJsonFile, overwrite = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "导出图片失败: ${imageFile.name}", e)
            }
        }

        Log.i(TAG, "已导出 ${exportedFiles.size} 张图片到: ${publicDir.absolutePath}")
        return exportedFiles
    }

    /**
     * 获取所有已保存的图片文件列表（按时间倒序）
     */
    fun getAllCaptureFiles(): List<File> {
        val captureDir = getCaptureDirectory()
        if (!captureDir.exists()) {
            return emptyList()
        }
        return captureDir.listFiles { file ->
            file.isFile && file.name.endsWith(".jpg")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /**
     * 获取保存目录路径（用于日志打印）
     */
    fun getCaptureDirPath(): String {
        return getCaptureDirectory().absolutePath
    }

    /**
     * 释放资源
     */
    fun shutdown() {
        executor.shutdown()
    }

    companion object {
        private const val TAG = "HazardCaptureService"
        private const val CAPTURE_DIR_NAME = "HazardCaptures"
        private const val LEGACY_BITMAP_JPEG_QUALITY = 95
        private const val MAX_CAPTURE_COUNT = 1000
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }
}
