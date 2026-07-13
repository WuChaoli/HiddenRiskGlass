package com.rokid.glass.hiddenrisk

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import com.rokid.glass.workflow.InspectionWorkflowSession
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 本地触发检测服务。
 * 将 NCNN 小模型包装成与 /ai/auto 等价的触发 provider，页面侧继续复用 DetectCallback 契约。
 */
internal class LocalTriggerDetectionService(
    assetManager: AssetManager? = null,
    private val nativeEngine: NativeEngine = InspectionSessionNativeEngine(
        requireNotNull(assetManager) { "LocalTriggerDetectionService requires AssetManager" },
    ),
    private val bitmapDecoder: (ByteArray) -> Any? = { bytes ->
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    },
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
    private val mainPoster: ((Runnable) -> Unit) = { runnable ->
        Handler(Looper.getMainLooper()).post(runnable)
    },
) {
    interface NativeEngine {
        fun ensureLoaded(): Boolean
        fun submitBitmap(bitmap: Any): Boolean
        fun latestStats(): NativeInferenceStats?
    }

    fun detect(
        request: OnlineHazardDetectionService.DetectionRequest,
        callback: AiArSseService.DetectCallback,
    ): AiArSseService.RequestHandle {
        val handle = AiArSseService.RequestHandle(taskId = "local-${request.requestId}")
        val placeCode = InspectionWorkflowSession.enterpriseInfo?.placeCode?.trim().orEmpty()
        if (placeCode.isBlank()) {
            postSuccess(handle, callback, hasHazard = false, fullText = "", labels = emptyList())
            return handle
        }
        worker.execute {
            val bitmap = bitmapDecoder(request.jpegBytes)
            if (bitmap == null) {
                postFailure(handle, callback, "本地触发图片解码失败")
                return@execute
            }
            val loaded = runCatching { nativeEngine.ensureLoaded() }.getOrDefault(false)
            if (!loaded) {
                postFailure(handle, callback, "本地触发模型加载失败")
                recycleBitmap(bitmap)
                return@execute
            }
            val success = runCatching { nativeEngine.submitBitmap(bitmap) }.getOrDefault(false)
            val stats = runCatching { nativeEngine.latestStats() }.getOrNull()
            recycleBitmap(bitmap)
            if (!success) {
                postFailure(
                    handle,
                    callback,
                    stats?.errorMessage?.takeIf { it.isNotBlank() } ?: "本地触发推理失败",
                )
                return@execute
            }
            val labels = stats
                ?.detections
                ?.mapNotNull { detection -> detection.label?.trim()?.takeIf(String::isNotBlank) }
                ?.distinct()
                .orEmpty()
            val hasHazard = labels.isNotEmpty()
            val fullText = if (hasHazard) {
                labels.joinToString(prefix = "local_trigger:", separator = ",")
            } else {
                ""
            }
            postSuccess(handle, callback, hasHazard, fullText, labels)
        }
        return handle
    }

    fun shutdown() {
        worker.shutdownNow()
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
                if (!handle.isCanceled()) {
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
                if (!handle.isCanceled()) {
                    callback.onFailure(handle, message)
                }
            },
        )
    }

    private fun recycleBitmap(bitmap: Any) {
        (bitmap as? Bitmap)?.takeIf { !it.isRecycled }?.recycle()
    }

    private class InspectionSessionNativeEngine(
        private val assetManager: AssetManager,
    ) : NativeEngine {
        override fun ensureLoaded(): Boolean {
            return InspectionSession.createNcnnInstance() &&
                InspectionSession.loadModel(assetManager)
        }

        override fun submitBitmap(bitmap: Any): Boolean {
            val ncnn = InspectionSession.hiddenRiskNcnn ?: return false
            return ncnn.submitBitmap(bitmap as Bitmap)
        }

        override fun latestStats(): NativeInferenceStats? {
            return InspectionSession.hiddenRiskNcnn?.getLatestInferenceStats()
        }
    }
}
