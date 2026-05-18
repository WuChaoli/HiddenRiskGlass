package com.rokid.glass.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import java.io.ByteArrayOutputStream

/**
 * YUV 图像转换工具类
 * 提供 YUV_420_888 / NV21 到 Bitmap 的公共转换方法
 */
object YuvConversionUtils {

    /**
     * 将 YUV_420_888 Image 转换为 NV21 字节数组
     * 正确处理 plane 的 rowStride 和 pixelStride，兼容不同设备的 plane 布局
     */
    fun yuv420888ToNv21(image: Image): ByteArray? {
        if (image.planes.size < 3) {
            return null
        }
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        copyPlane(
            plane = yPlane,
            width = width,
            height = height,
            out = nv21,
            outOffset = 0,
            outPixelStride = 1,
        )

        copyPlane(
            plane = vPlane,
            width = width / 2,
            height = height / 2,
            out = nv21,
            outOffset = ySize,
            outPixelStride = 2,
        )
        copyPlane(
            plane = uPlane,
            width = width / 2,
            height = height / 2,
            out = nv21,
            outOffset = ySize + 1,
            outPixelStride = 2,
        )

        return nv21
    }

    /**
     * 将 NV21 字节数组转换为 Bitmap
     * 通过 YuvImage 压缩为 JPEG 再解码为 Bitmap（简单可靠的方法）
     *
     * @param nv21 NV21 格式的 YUV 数据
     * @param width 图像宽度
     * @param height 图像高度
     * @param quality JPEG 压缩质量 0-100
     */
    fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int, quality: Int = 90): Bitmap? {
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        if (!yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, out)) {
            return null
        }
        val jpegBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    /**
     * 将 YUV_420_888 Image 一站式转换为 Bitmap
     * 内部依次调用 yuv420888ToNv21 + nv21ToBitmap
     *
     * @param image YUV_420_888 格式的 Image
     * @param quality JPEG 压缩质量 0-100
     */
    fun yuvImageToBitmap(image: Image, quality: Int = 90): Bitmap? {
        val nv21 = yuv420888ToNv21(image) ?: return null
        return nv21ToBitmap(nv21, image.width, image.height, quality)
    }

    /**
     * 将 plane 数据按指定步长拷贝到输出数组
     * 处理不同设备的 rowStride 和 pixelStride 差异
     */
    private fun copyPlane(
        plane: Image.Plane,
        width: Int,
        height: Int,
        out: ByteArray,
        outOffset: Int,
        outPixelStride: Int,
    ) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowData = ByteArray(rowStride)
        var outputIndex = outOffset

        for (row in 0 until height) {
            val rowLength = if (pixelStride == 1 && outPixelStride == 1) {
                width
            } else {
                (width - 1) * pixelStride + 1
            }
            buffer.get(rowData, 0, rowLength)
            var inputIndex = 0
            for (col in 0 until width) {
                out[outputIndex] = rowData[inputIndex]
                outputIndex += outPixelStride
                inputIndex += pixelStride
            }
            if (row < height - 1) {
                buffer.position(buffer.position() + rowStride - rowLength)
            }
        }
    }
}
