package com.rokid.glass.utils

import android.graphics.Bitmap
import android.util.Log

/**
 * Bitmap 工具类
 * 提供图像处理相关功能
 */
object BitmapUtils {

    private const val TAG = "BitmapUtils"
    private const val OUTPUT_SIZE = 640

    /**
     * 从 Bitmap 中心裁剪出 640x640 区域
     * 如果原图小于 640x640，则使用 letterbox 方式缩放
     *
     * @param source 原始 Bitmap
     * @return 裁剪后的 640x640 Bitmap
     */
    fun cropCenterTo640(source: Bitmap?): Bitmap? {
        if (source == null) return null

        val targetSize = OUTPUT_SIZE

        // 如果已经是 640x640，直接返回
        if (source.width == targetSize && source.height == targetSize) {
            return source
        }

        // 如果原图小于目标尺寸，使用 letterbox 缩放
        if (source.width < targetSize || source.height < targetSize) {
            Log.w(TAG, "Source bitmap (${source.width}x${source.height}) smaller than target, using letterbox")
            return letterboxTo640(source)
        }

        // 计算中心裁剪区域
        val x = (source.width - targetSize) / 2
        val y = (source.height - targetSize) / 2

        return try {
            Bitmap.createBitmap(source, x, y, targetSize, targetSize)
        } catch (e: Exception) {
            Log.e(TAG, "Center crop failed: ${e.message}")
            letterboxTo640(source)
        }
    }

    /**
     * Letterbox 缩放至 640x640
     * 保持宽高比，短边填充黑边
     *
     * @param source 原始 Bitmap
     * @return 缩放后的 640x640 Bitmap
     */
    private fun letterboxTo640(source: Bitmap): Bitmap {
        val targetSize = OUTPUT_SIZE

        // 计算缩放比例
        val scale = targetSize.toFloat() / kotlin.math.max(source.width, source.height)
        val newWidth = (source.width * scale).toInt()
        val newHeight = (source.height * scale).toInt()

        // 缩放图片
        val scaled = Bitmap.createScaledBitmap(source, newWidth, newHeight, true)

        // 创建 640x640 画布并居中绘制
        val result = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        canvas.drawColor(android.graphics.Color.BLACK)

        val left = (targetSize - newWidth) / 2f
        val top = (targetSize - newHeight) / 2f
        canvas.drawBitmap(scaled, left, top, null)

        // 回收中间生成的缩略图（如果不是原图）
        if (scaled !== source) {
            scaled.recycle()
        }

        return result
    }
}
