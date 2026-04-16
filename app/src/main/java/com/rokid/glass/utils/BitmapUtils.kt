package com.rokid.glass.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import java.io.ByteArrayOutputStream

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
     * 将 NV21 帧转换为 Bitmap。
     * 先压缩为 JPEG，再解码为 Bitmap，兼容性更稳定。
     */
    fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int, jpegQuality: Int = 90): Bitmap? {
        return try {
            val jpegBytes = encodeNv21RectToJpeg(
                nv21 = nv21,
                width = width,
                height = height,
                cropRect = Rect(0, 0, width, height),
                jpegQuality = jpegQuality,
            ) ?: return null
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (error: Exception) {
            Log.e(TAG, "NV21 转 Bitmap 失败: ${error.message}", error)
            null
        }
    }

    /**
     * 将 NV21 帧中心裁剪为 640x640 JPEG。
     * 对于尺寸不足的输入，回退到 Bitmap letterbox 逻辑，保证输出稳定。
     */
    fun encodeCenterCropNv21ToJpeg(
        nv21: ByteArray,
        width: Int,
        height: Int,
        jpegQuality: Int = 80,
        targetSize: Int = OUTPUT_SIZE,
    ): ByteArray? {
        val cropRect = calculateCenterCropRect(width, height, targetSize)
        if (cropRect != null) {
            return encodeNv21RectToJpeg(
                nv21 = nv21,
                width = width,
                height = height,
                cropRect = cropRect,
                jpegQuality = jpegQuality,
            )
        }

        val source = nv21ToBitmap(nv21, width, height, jpegQuality = jpegQuality) ?: return null
        val output = cropCenterTo640(source) ?: run {
            if (!source.isRecycled) {
                source.recycle()
            }
            return null
        }

        return try {
            val outputStream = ByteArrayOutputStream()
            if (!output.compress(Bitmap.CompressFormat.JPEG, jpegQuality, outputStream)) {
                Log.e(TAG, "Fallback Bitmap 转 JPEG 失败")
                null
            } else {
                outputStream.toByteArray()
            }
        } finally {
            if (output !== source && !output.isRecycled) {
                output.recycle()
            }
            if (!source.isRecycled) {
                source.recycle()
            }
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
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK)

        val left = (targetSize - newWidth) / 2f
        val top = (targetSize - newHeight) / 2f
        canvas.drawBitmap(scaled, left, top, null)

        // 回收中间生成的缩略图（如果不是原图）
        if (scaled !== source) {
            scaled.recycle()
        }

        return result
    }

    private fun calculateCenterCropRect(width: Int, height: Int, targetSize: Int): Rect? {
        if (width < targetSize || height < targetSize) {
            return null
        }
        val left = (width - targetSize) / 2
        val top = (height - targetSize) / 2
        return Rect(left, top, left + targetSize, top + targetSize)
    }

    private fun encodeNv21RectToJpeg(
        nv21: ByteArray,
        width: Int,
        height: Int,
        cropRect: Rect,
        jpegQuality: Int,
    ): ByteArray? {
        return try {
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val outputStream = ByteArrayOutputStream()
            if (!yuvImage.compressToJpeg(cropRect, jpegQuality, outputStream)) {
                Log.e(TAG, "NV21 裁剪转 JPEG 失败 crop=$cropRect")
                null
            } else {
                outputStream.toByteArray()
            }
        } catch (error: Exception) {
            Log.e(TAG, "NV21 裁剪转 JPEG 失败: ${error.message}", error)
            null
        }
    }
}
