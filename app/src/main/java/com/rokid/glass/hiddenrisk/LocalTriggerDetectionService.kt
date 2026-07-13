package com.rokid.glass.hiddenrisk

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
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
            callback: (LocalInferenceCoordinator.DetectionOutcome) -> Unit,
        )
    }

    private val closed = AtomicBoolean(false)

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
        val bitmap = bitmapDecoder(request.jpegBytes)
        if (bitmap == null) {
            postFailure(handle, callback, "本地触发图片解码失败")
            return handle
        }
        coordinator.detect(
            assets = requireNotNull(assetManager) { "LocalTriggerDetectionService requires AssetManager" },
            bitmap = bitmap,
        ) coordinatorCallback@{ outcome ->
            try {
                if (!outcome.success) {
                    postFailure(
                        handle,
                        callback,
                        outcome.errorMessage.takeIf { it.isNotBlank() } ?: "本地触发推理失败",
                    )
                    return@coordinatorCallback
                }
                val labels = outcome.stats
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
        private val coordinator = LocalInferenceCoordinator(
            executor = LocalInferenceCoordinator.executor(),
            engineFactory = { InspectionSessionNativeEngine() },
        )

        override fun detect(
            assets: Any,
            bitmap: Any,
            callback: (LocalInferenceCoordinator.DetectionOutcome) -> Unit,
        ) {
            coordinator.detect(assets, bitmap, callback)
        }
    }

    private class InspectionSessionNativeEngine : LocalInferenceCoordinator.NativeEngine {
        override fun load(assets: Any): Boolean {
            return InspectionSession.createNcnnInstance() &&
                InspectionSession.loadModel(assets as AssetManager)
        }

        override fun detect(bitmap: Any): Boolean {
            return InspectionSession.hiddenRiskNcnn?.submitBitmap(bitmap as Bitmap) ?: false
        }

        override fun latestStats(): NativeInferenceStats? {
            return InspectionSession.hiddenRiskNcnn?.getLatestInferenceStats()
        }

        override fun release() {
            InspectionSession.hiddenRiskNcnn?.clearFrameState()
        }

        override fun errorMessage(): String? = InspectionSession.errorMessage
    }

    companion object {
        private fun recycleAndroidBitmap(bitmap: Any) {
        (bitmap as? Bitmap)?.takeIf { !it.isRecycled }?.recycle()
        }
    }
}
