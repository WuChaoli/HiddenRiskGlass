package com.rokid.glass.hiddenrisk

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.rokid.glass.camera.RokidFrameSource
import com.rokid.glass.utils.BitmapUtils

/**
 * 复用隐患识别页的 NV21 方图裁切与 JPEG 编码链路。
 */
class InspectionFrameCaptureService(
    private val frameProvider: FrameProvider = RokidSquareFrameProvider,
    private val jpegEncoder: JpegEncoder = Nv21JpegEncoder,
    private val staleFrameThresholdMs: Long,
    private val selectWindowMs: Long,
    private val selectMaxFrames: Int,
    private val selectPollIntervalMs: Long,
    private val jpegQuality: Int,
    private val clockElapsedMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val sleepMs: (Long) -> Unit = { SystemClock.sleep(it) },
    private val logger: (stage: String, extra: String) -> Unit = { stage, extra ->
        Log.i(TAG, "$stage $extra")
    },
    private val warningLogger: (String) -> Unit = { message -> Log.w(TAG, message) },
) {
    fun copyLatestSquareFrameOrNull(lastTimestampExclusive: Long): SquareFramePayload? {
        val sourceFrame = frameProvider.copyLatestSquareFrame() ?: return null
        if (sourceFrame.timestamp <= lastTimestampExclusive) {
            return null
        }
        val ageMs = clockElapsedMs() - sourceFrame.receivedAtElapsedMs
        if (ageMs > staleFrameThresholdMs) {
            warningLogger("drop square frame reason=stale timestamp=${sourceFrame.timestamp} ageMs=$ageMs")
            return null
        }
        return sourceFrame.toPayload()
    }

    fun buildCapturedFramePayload(frame: SquareFramePayload): CapturedFramePayload? {
        val startElapsedMs = clockElapsedMs()
        logger(
            "build_captured_frame_payload:start",
            "frameTs=${frame.timestamp} size=${frame.width}x${frame.height} source=${frame.sourceWidth}x${frame.sourceHeight}",
        )
        val jpegBytes = jpegEncoder.encode(
            nv21 = frame.nv21,
            width = frame.width,
            height = frame.height,
            cropRect = createRect(0, 0, frame.width, frame.height),
            jpegQuality = jpegQuality,
        ) ?: run {
            logger(
                "build_captured_frame_payload:null",
                "frameTs=${frame.timestamp} elapsedMs=${clockElapsedMs() - startElapsedMs}",
            )
            return null
        }
        val payload = CapturedFramePayload(
            jpegBytes = jpegBytes,
            width = frame.width,
            height = frame.height,
            timestamp = frame.timestamp,
            receivedAtElapsedMs = frame.receivedAtElapsedMs,
            payloadBuiltAtElapsedMs = clockElapsedMs(),
            sourceWidth = frame.sourceWidth,
            sourceHeight = frame.sourceHeight,
            cropRect = copyRect(frame.cropRect),
            sharpnessScore = frame.sharpnessScore,
        )
        logger(
            "build_captured_frame_payload:end",
            "frameTs=${frame.timestamp} jpegBytes=${jpegBytes.size} elapsedMs=${clockElapsedMs() - startElapsedMs}",
        )
        return payload
    }

    fun selectBestFramePayload(lastTimestampExclusive: Long): CapturedFramePayload? {
        val selectStartedElapsedMs = clockElapsedMs()
        val deadline = selectStartedElapsedMs + selectWindowMs
        var bestFrame: SquareFramePayload? = null
        var lastTimestamp = lastTimestampExclusive
        var sampledFrames = 0
        logger(
            "select_best_frame_payload:start",
            "lastTs=$lastTimestampExclusive windowMs=$selectWindowMs maxFrames=$selectMaxFrames",
        )
        while (sampledFrames < selectMaxFrames) {
            val frame = copyLatestSquareFrameOrNull(lastTimestamp)
            if (frame == null) {
                if (clockElapsedMs() >= deadline) {
                    break
                }
                sleepMs(selectPollIntervalMs)
                continue
            }
            lastTimestamp = frame.timestamp
            sampledFrames += 1
            val currentBest = bestFrame
            if (currentBest == null ||
                frame.sharpnessScore > currentBest.sharpnessScore ||
                (frame.sharpnessScore == currentBest.sharpnessScore && frame.timestamp > currentBest.timestamp)
            ) {
                bestFrame = frame
            }
            if (sampledFrames >= selectMaxFrames || clockElapsedMs() >= deadline) {
                break
            }
            sleepMs(selectPollIntervalMs)
        }
        val selectedFrame = bestFrame
        if (selectedFrame == null) {
            logger(
                "select_best_frame_payload:null",
                "sampledFrames=$sampledFrames elapsedMs=${clockElapsedMs() - selectStartedElapsedMs}",
            )
            return null
        }
        logger(
            "select_best_frame_payload:selected",
            "frameTs=${selectedFrame.timestamp} sampledFrames=$sampledFrames sharpness=${"%.2f".format(selectedFrame.sharpnessScore)} selectElapsedMs=${clockElapsedMs() - selectStartedElapsedMs}",
        )
        return buildCapturedFramePayload(selectedFrame)
    }

    private fun SourceSquareFrame.toPayload(): SquareFramePayload {
        return SquareFramePayload(
            nv21 = data,
            width = width,
            height = height,
            timestamp = timestamp,
            receivedAtElapsedMs = receivedAtElapsedMs,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            cropRect = copyRect(cropRect),
            sharpnessScore = computeSquareFrameSharpnessScore(data, width, height),
        )
    }

    interface FrameProvider {
        fun copyLatestSquareFrame(): SourceSquareFrame?
    }

    fun interface JpegEncoder {
        fun encode(
            nv21: ByteArray,
            width: Int,
            height: Int,
            cropRect: Rect,
            jpegQuality: Int,
        ): ByteArray?
    }

    data class SourceSquareFrame(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val cropRect: Rect,
        val timestamp: Long,
        val receivedAtElapsedMs: Long,
    )

    data class SquareFramePayload(
        val nv21: ByteArray,
        val width: Int,
        val height: Int,
        val timestamp: Long,
        val receivedAtElapsedMs: Long,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val cropRect: Rect,
        val sharpnessScore: Double,
    )

    data class CapturedFramePayload(
        val jpegBytes: ByteArray,
        val width: Int,
        val height: Int,
        val timestamp: Long,
        val receivedAtElapsedMs: Long,
        val payloadBuiltAtElapsedMs: Long,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val cropRect: Rect,
        val sharpnessScore: Double,
    )

    companion object {
        private const val TAG = "InspectionFrameCapture"

        private fun copyRect(source: Rect): Rect {
            return createRect(source.left, source.top, source.right, source.bottom)
        }

        private fun createRect(left: Int, top: Int, right: Int, bottom: Int): Rect {
            return Rect().apply {
                this.left = left
                this.top = top
                this.right = right
                this.bottom = bottom
            }
        }

        fun computeSquareFrameSharpnessScore(
            nv21: ByteArray,
            width: Int,
            height: Int,
        ): Double {
            if (width <= 2 || height <= 2) {
                return 0.0
            }
            val stride = 4
            var score = 0.0
            var samples = 0
            var y = stride
            while (y < height - stride) {
                var x = stride
                while (x < width - stride) {
                    val center = nv21[y * width + x].toInt() and 0xFF
                    val leftPx = nv21[y * width + (x - stride)].toInt() and 0xFF
                    val rightPx = nv21[y * width + (x + stride)].toInt() and 0xFF
                    val topPx = nv21[(y - stride) * width + x].toInt() and 0xFF
                    val bottomPx = nv21[(y + stride) * width + x].toInt() and 0xFF
                    val laplacian = kotlin.math.abs(leftPx + rightPx + topPx + bottomPx - 4 * center)
                    score += laplacian.toDouble()
                    samples += 1
                    x += stride
                }
                y += stride
            }
            return if (samples == 0) 0.0 else score / samples
        }
    }
}

object RokidSquareFrameProvider : InspectionFrameCaptureService.FrameProvider {
    override fun copyLatestSquareFrame(): InspectionFrameCaptureService.SourceSquareFrame? {
        val frame = RokidFrameSource.copyLatestSquareFrame() ?: return null
        return InspectionFrameCaptureService.SourceSquareFrame(
            data = frame.data,
            width = frame.width,
            height = frame.height,
            sourceWidth = frame.sourceWidth,
            sourceHeight = frame.sourceHeight,
            cropRect = Rect().apply {
                left = frame.cropRect.left
                top = frame.cropRect.top
                right = frame.cropRect.right
                bottom = frame.cropRect.bottom
            },
            timestamp = frame.timestamp,
            receivedAtElapsedMs = frame.receivedAtElapsedMs,
        )
    }
}

object Nv21JpegEncoder : InspectionFrameCaptureService.JpegEncoder {
    override fun encode(
        nv21: ByteArray,
        width: Int,
        height: Int,
        cropRect: Rect,
        jpegQuality: Int,
    ): ByteArray? {
        return BitmapUtils.encodeNv21CropRectToJpeg(
            nv21 = nv21,
            width = width,
            height = height,
            cropRect = cropRect,
            jpegQuality = jpegQuality,
        )
    }
}
