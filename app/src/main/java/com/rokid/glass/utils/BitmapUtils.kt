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
        return cropCenterToSize(source, OUTPUT_SIZE)
    }

    /**
     * 从 Bitmap 中心裁剪出指定尺寸的正方形区域。
     * 如果原图小于目标尺寸，则使用 letterbox 方式缩放。
     */
    fun cropCenterToSize(source: Bitmap?, targetSize: Int): Bitmap? {
        if (source == null) return null
        if (targetSize <= 0) return null

        // 如果已经是 640x640，直接返回
        if (source.width == targetSize && source.height == targetSize) {
            return source
        }

        // 如果原图小于目标尺寸，使用 letterbox 缩放
        if (source.width < targetSize || source.height < targetSize) {
            Log.w(TAG, "Source bitmap (${source.width}x${source.height}) smaller than target=$targetSize, using letterbox")
            return letterboxToSize(source, targetSize)
        }

        // 计算中心裁剪区域
        val x = (source.width - targetSize) / 2
        val y = (source.height - targetSize) / 2

        return try {
            Bitmap.createBitmap(source, x, y, targetSize, targetSize)
        } catch (e: Exception) {
            Log.e(TAG, "Center crop failed: ${e.message}")
            letterboxToSize(source, targetSize)
        }
    }

    /**
     * 将 NV21 帧转换为 Bitmap。
     * 先压缩为 JPEG，再解码为 Bitmap，兼容性更稳定。
     */
    fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int, jpegQuality: Int = 90): Bitmap? {
        return try {
            val jpegBytes = encodeNv21ToJpeg(
                nv21 = nv21,
                width = width,
                height = height,
                jpegQuality = jpegQuality,
            ) ?: return null
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (error: Exception) {
            Log.e(TAG, "NV21 转 Bitmap 失败: ${error.message}", error)
            null
        }
    }

    /**
     * 将整张 NV21 帧编码为 JPEG。
     */
    fun encodeNv21ToJpeg(
        nv21: ByteArray,
        width: Int,
        height: Int,
        jpegQuality: Int = 80,
    ): ByteArray? {
        return encodeNv21RectToJpeg(
            nv21 = nv21,
            width = width,
            height = height,
            cropRect = Rect(0, 0, width, height),
            jpegQuality = jpegQuality,
        )
    }

    /**
     * 将 NV21 指定裁切区域编码为 JPEG。
     */
    fun encodeNv21CropRectToJpeg(
        nv21: ByteArray,
        width: Int,
        height: Int,
        cropRect: Rect,
        jpegQuality: Int = 80,
    ): ByteArray? {
        return encodeNv21RectToJpeg(
            nv21 = nv21,
            width = width,
            height = height,
            cropRect = cropRect,
            jpegQuality = jpegQuality,
        )
    }

    /**
     * 将 NV21 中心裁剪/回退处理为指定尺寸的 NV21。
     * 常规路径直接复制 NV21 的中心区域；异常尺寸时回退到 Bitmap letterbox。
     */
    fun cropCenterNv21(
        nv21: ByteArray,
        width: Int,
        height: Int,
        targetSize: Int = OUTPUT_SIZE,
    ): ByteArray? {
        val cropRect = calculateCenterCropRect(width, height, targetSize)
        if (cropRect != null) {
            return cropNv21Rect(
                nv21 = nv21,
                width = width,
                height = height,
                cropRect = cropRect,
            )
        }

        val source = nv21ToBitmap(nv21, width, height, jpegQuality = 95) ?: return null
        val output = cropCenterToSize(source, targetSize) ?: run {
            if (!source.isRecycled) {
                source.recycle()
            }
            return null
        }

        return try {
            bitmapToNv21(output)
        } catch (error: Exception) {
            Log.e(TAG, "Bitmap 转 NV21 失败: ${error.message}", error)
            null
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
        val output = cropCenterToSize(source, targetSize) ?: run {
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
     * 将正方形 NV21 缩放到目标尺寸。
     * 当前项目只在“基准方图 -> 本地推理 640x640”链路使用，优先保证结果稳定。
     */
    fun resizeSquareNv21(
        nv21: ByteArray,
        width: Int,
        height: Int,
        targetSize: Int,
        jpegQuality: Int = 100,
    ): ByteArray? {
        if (width <= 0 || height <= 0 || targetSize <= 0) {
            return null
        }
        if (width == targetSize && height == targetSize) {
            return nv21.copyOf()
        }

        val source = nv21ToBitmap(nv21, width, height, jpegQuality = jpegQuality) ?: return null
        val scaled = try {
            Bitmap.createScaledBitmap(source, targetSize, targetSize, true)
        } catch (error: Exception) {
            Log.e(TAG, "Square NV21 缩放失败: ${error.message}", error)
            if (!source.isRecycled) {
                source.recycle()
            }
            return null
        }

        return try {
            bitmapToNv21(scaled)
        } catch (error: Exception) {
            Log.e(TAG, "缩放后 Bitmap 转 NV21 失败: ${error.message}", error)
            null
        } finally {
            if (scaled !== source && !scaled.isRecycled) {
                scaled.recycle()
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
        return letterboxToSize(source, OUTPUT_SIZE)
    }

    private fun letterboxToSize(source: Bitmap, targetSize: Int): Bitmap {
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
        val left = ((width - targetSize) / 2) and -2
        val top = ((height - targetSize) / 2) and -2
        return Rect(left, top, left + targetSize, top + targetSize)
    }

    fun cropNv21Rect(
        nv21: ByteArray,
        width: Int,
        height: Int,
        cropRect: Rect,
    ): ByteArray? {
        val cropWidth = cropRect.width()
        val cropHeight = cropRect.height()
        if (cropWidth <= 0 || cropHeight <= 0) {
            return null
        }
        if (cropRect.left < 0 || cropRect.top < 0 || cropRect.right > width || cropRect.bottom > height) {
            return null
        }
        if ((cropRect.left and 1) != 0 || (cropRect.top and 1) != 0 || (cropWidth and 1) != 0 || (cropHeight and 1) != 0) {
            Log.w(TAG, "NV21 裁剪区域未按偶数对齐 crop=$cropRect")
            return null
        }
        val expectedSize = width * height * 3 / 2
        if (nv21.size < expectedSize) {
            Log.e(TAG, "NV21 数据长度不足 actual=${nv21.size} expected=$expectedSize")
            return null
        }

        val output = ByteArray(cropWidth * cropHeight * 3 / 2)
        val srcYStride = width
        val dstYStride = cropWidth
        for (row in 0 until cropHeight) {
            val srcOffset = (cropRect.top + row) * srcYStride + cropRect.left
            val dstOffset = row * dstYStride
            System.arraycopy(nv21, srcOffset, output, dstOffset, cropWidth)
        }

        val srcUvBase = width * height
        val dstUvBase = cropWidth * cropHeight
        for (row in 0 until cropHeight / 2) {
            val srcOffset = srcUvBase + ((cropRect.top / 2) + row) * width + cropRect.left
            val dstOffset = dstUvBase + row * cropWidth
            System.arraycopy(nv21, srcOffset, output, dstOffset, cropWidth)
        }
        return output
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

    private fun bitmapToNv21(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        val output = ByteArray(width * height * 3 / 2)

        var yIndex = 0
        var uvIndex = width * height
        for (j in 0 until height) {
            for (i in 0 until width) {
                val color = argb[j * width + i]
                val r = color shr 16 and 0xFF
                val g = color shr 8 and 0xFF
                val b = color and 0xFF

                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                output[yIndex++] = y.coerceIn(0, 255).toByte()
                if ((j and 1) == 0 && (i and 1) == 0) {
                    output[uvIndex++] = v.coerceIn(0, 255).toByte()
                    output[uvIndex++] = u.coerceIn(0, 255).toByte()
                }
            }
        }
        return output
    }
}
