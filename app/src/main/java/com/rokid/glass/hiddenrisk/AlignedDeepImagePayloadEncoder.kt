package com.rokid.glass.hiddenrisk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

internal object AlignedDeepImagePayloadEncoder {
    fun encode(fullFrameJpegBytes: ByteArray, jpegQuality: Int): DeepV2ImagePayload? = runCatching {
        val source = checkNotNull(BitmapFactory.decodeByteArray(fullFrameJpegBytes, 0, fullFrameJpegBytes.size))
        val crop = AlignedDeepImageCropPlanner.plan(
            sourceSize = FrameSize(source.width, source.height),
            calibration = FullFrameOverlayCalibrationState().calibration,
        )
        val aligned = Bitmap.createBitmap(source, crop.left, crop.top, crop.width, crop.height)
        try {
            ByteArrayOutputStream().use { output ->
                check(aligned.compress(Bitmap.CompressFormat.JPEG, jpegQuality, output))
                DeepV2ImagePayload(output.toByteArray(), aligned.width, aligned.height)
            }
        } finally {
            if (aligned !== source) aligned.recycle()
            source.recycle()
        }
    }.getOrNull()
}
