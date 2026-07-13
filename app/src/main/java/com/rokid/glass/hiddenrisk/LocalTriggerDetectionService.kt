package com.rokid.glass.hiddenrisk

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.rokid.glass.workflow.InspectionWorkflowSession
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地触发检测服务。
 * 将 NCNN 小模型包装成与 /ai/auto 等价的触发 provider，页面侧继续复用 DetectCallback 契约。
 */
internal class LocalTriggerDetectionService(
    private val assetManager: Any? = null,
    private val coordinator: CoordinatorGateway = AndroidCoordinatorGateway,
    private val bitmapDecoder: (ByteArray) -> Any? = { bytes ->
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    },
    private val bitmapRecycler: (Any) -> Unit = ::recycleAndroidBitmap,
    private val mainPoster: ((Runnable) -> Unit) = { runnable ->
        Handler(Looper.getMainLooper()).post(runnable)
    },
) {
    interface CoordinatorGateway {
        fun detect(
            assets: Any,
            bitmap: Any,
            traceLabel: String = "",
            callback: (LocalInferenceCoordinator.DetectionOutcome) -> Unit,
        )
    }

    private val closed = AtomicBoolean(false)

    fun detect(
        request: OnlineHazardDetectionService.DetectionRequest,
        callback: AiArSseService.DetectCallback,
    ): AiArSseService.RequestHandle {
        val requestStartedAtMs = SystemClock.elapsedRealtime()
        val traceLabel = "local-${request.requestId}"
        val handle = AiArSseService.RequestHandle(taskId = "local-${request.requestId}")
        val placeCode = InspectionWorkflowSession.enterpriseInfo?.placeCode?.trim().orEmpty()
        Log.i(
            TAG,
            "timing trace=$traceLabel phase=request_start requestId=${request.requestId} jpegBytes=${request.jpegBytes.size} placeCodePresent=${placeCode.isNotBlank()}",
        )
        if (placeCode.isBlank()) {
            postSuccess(handle, callback, hasHazard = false, fullText = "", labels = emptyList())
            return handle
        }
        val decodeStartedAtMs = SystemClock.elapsedRealtime()
        val bitmap = bitmapDecoder(request.jpegBytes)
        val decodeMs = SystemClock.elapsedRealtime() - decodeStartedAtMs
        if (bitmap == null) {
            Log.w(
                TAG,
                "timing trace=$traceLabel phase=decode_failed decodeMs=$decodeMs elapsedMs=${SystemClock.elapsedRealtime() - requestStartedAtMs}",
            )
            postFailure(handle, callback, "本地触发图片解码失败")
            return handle
        }
        Log.i(
            TAG,
            "timing trace=$traceLabel phase=decoded decodeMs=$decodeMs elapsedMs=${SystemClock.elapsedRealtime() - requestStartedAtMs} bitmap=${summarizeBitmap(bitmap)}",
        )
        val coordinatorSubmittedAtMs = SystemClock.elapsedRealtime()
        coordinator.detect(
            assets = requireNotNull(assetManager) { "LocalTriggerDetectionService requires AssetManager" },
            bitmap = bitmap,
            traceLabel = traceLabel,
        ) coordinatorCallback@{ outcome ->
            try {
                val callbackElapsedMs = SystemClock.elapsedRealtime() - requestStartedAtMs
                val coordinatorElapsedMs = SystemClock.elapsedRealtime() - coordinatorSubmittedAtMs
                val statsInferenceMs = outcome.stats?.inferenceTimeMs ?: -1L
                Log.i(
                    TAG,
                    "timing trace=$traceLabel phase=coordinator_callback success=${outcome.success} totalElapsedMs=$callbackElapsedMs coordinatorElapsedMs=$coordinatorElapsedMs decodeMs=$decodeMs statsInferenceMs=$statsInferenceMs estimatedNonNativeMs=${if (statsInferenceMs >= 0) coordinatorElapsedMs - statsInferenceMs else -1L}",
                )
                if (!outcome.success) {
                    Log.w(
                        TAG,
                        "local trigger native failed requestId=${request.requestId} message=${outcome.errorMessage}",
                    )
                    postFailure(
                        handle,
                        callback,
                        outcome.errorMessage.takeIf { it.isNotBlank() } ?: "本地触发推理失败",
                    )
                    return@coordinatorCallback
                }
                val stats = outcome.stats
                val detections = stats?.detections.orEmpty()
                Log.i(
                    TAG,
                    "local trigger native result requestId=${request.requestId} detectionCount=${stats?.detectionCount ?: -1} prelimitCount=${stats?.preLimitDetectionCount ?: -1} returnedCount=${detections.size} detections=${summarizeDetections(detections)}",
                )
                val labels = detections
                    .mapNotNull { detection -> detection.label?.trim()?.takeIf(String::isNotBlank) }
                    .distinct()
                Log.i(
                    TAG,
                    "local trigger labels requestId=${request.requestId} labelCount=${labels.size} labels=${labels.joinToString()}",
                )
                val hasHazard = labels.isNotEmpty()
                val fullText = if (hasHazard) {
                    labels.joinToString(prefix = "local_trigger:", separator = ",")
                } else {
                    ""
                }
                postSuccess(handle, callback, hasHazard, fullText, labels)
            } finally {
                bitmapRecycler(bitmap)
            }
        }
        return handle
    }

    fun shutdown() {
        closed.set(true)
    }

    private fun postSuccess(
        handle: AiArSseService.RequestHandle,
        callback: AiArSseService.DetectCallback,
        hasHazard: Boolean,
        fullText: String,
        labels: List<String>,
    ) {
        mainPoster(
            Runnable {
                if (!closed.get() && !handle.isCanceled()) {
                    callback.onSuccess(handle, hasHazard, fullText, labels)
                }
            },
        )
    }

    private fun postFailure(
        handle: AiArSseService.RequestHandle,
        callback: AiArSseService.DetectCallback,
        message: String,
    ) {
        mainPoster(
            Runnable {
                if (!closed.get() && !handle.isCanceled()) {
                    callback.onFailure(handle, message)
                }
            },
        )
    }

    private object AndroidCoordinatorGateway : CoordinatorGateway {
        override fun detect(
            assets: Any,
            bitmap: Any,
            traceLabel: String,
            callback: (LocalInferenceCoordinator.DetectionOutcome) -> Unit,
        ) {
            InspectionSession.detectLocal(
                assets = assets as AssetManager,
                bitmap = bitmap as Bitmap,
                traceLabel = traceLabel,
                callback = callback,
            )
        }
    }

    companion object {
        private const val TAG = "LocalTriggerDetect"

        private fun recycleAndroidBitmap(bitmap: Any) {
        (bitmap as? Bitmap)?.takeIf { !it.isRecycled }?.recycle()
        }

        private fun summarizeBitmap(bitmap: Any): String {
            return (bitmap as? Bitmap)?.let { "${it.width}x${it.height}" } ?: bitmap::class.java.simpleName
        }

        private fun summarizeDetections(detections: Array<out DetectionResult>): String {
            if (detections.isEmpty()) {
                return "[]"
            }
            return detections
                .take(8)
                .joinToString(prefix = "[", postfix = "]") { detection ->
                    "id=${detection.labelId},label=${detection.label?.trim().orEmpty()},score=${detection.score},box=${detection.x},${detection.y},${detection.width}x${detection.height}"
                }
        }
    }
}
