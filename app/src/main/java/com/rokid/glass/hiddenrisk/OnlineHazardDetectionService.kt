package com.rokid.glass.hiddenrisk

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.rokid.glass.config.InspectionConfigRepository
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 在线隐患识别调度服务。
 * ctype=1 检测阶段仅允许单飞，并对单次请求施加超时控制；
 * ctype=0 详情阶段按需单次拉取。
 */
internal class OnlineHazardDetectionService(
    private val callback: Callback,
    private val requestGateway: RequestGateway = SseRequestGateway(AiArSseService()),
    private val scheduler: MainThreadScheduler = AndroidMainThreadScheduler(),
    private val elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() },
    private val base64Encoder: (ByteArray) -> String = { Base64.encodeToString(it, Base64.NO_WRAP) },
    private val encodeExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val detectTimeoutMs: Long = InspectionConfigRepository.get().network.aiArApi.detectTimeoutMs,
) {
    data class DetectionRequest(
        val epoch: Long,
        val requestId: Long,
        val jpegBytes: ByteArray,
    )

    data class DetailRequest(
        val epoch: Long,
        val requestId: Long,
        val jpegBytes: ByteArray,
    )

    interface Callback {
        fun onDetectionResult(request: DetectionRequest, hasHazard: Boolean, rawText: String)
        fun onDetectionFailure(request: DetectionRequest, message: String)
        fun onDetectionDropped(request: DetectionRequest, reason: String)
        fun onDetailChunk(request: DetailRequest, accumulatedText: String)
        fun onDetailSuccess(request: DetailRequest, fullText: String)
        fun onDetailFailure(request: DetailRequest, message: String)
    }

    internal interface RequestGateway {
        fun detectHasHazard(
            request: DetectionRequest,
            base64Image: String,
            callback: AiArSseService.DetectCallback,
        ): AiArSseService.RequestHandle

        fun fetchHazardDetails(
            request: DetailRequest,
            base64Image: String,
            onChunk: (String) -> Unit,
            callback: AiArSseService.DetailCallback,
        ): AiArSseService.RequestHandle
    }

    internal interface MainThreadScheduler {
        fun post(runnable: Runnable)
        fun postDelayed(runnable: Runnable, delayMs: Long)
        fun removeCallbacks(runnable: Runnable)
    }

    private var activeDetectionRequest: DetectionRequest? = null
    private var activeDetectionHandle: AiArSseService.RequestHandle? = null
    private var activeDetectionStartedElapsedMs = 0L
    private var activeDetailRequest: DetailRequest? = null
    private var activeDetailHandle: AiArSseService.RequestHandle? = null

    private val detectionTimeoutRunnable = Runnable {
        val request = activeDetectionRequest ?: return@Runnable
        if (elapsedRealtimeProvider() - activeDetectionStartedElapsedMs < detectTimeoutMs) {
            return@Runnable
        }
        runCatching { Log.w(TAG, "detect timeout requestId=${request.requestId}") }
        activeDetectionHandle?.cancel()
        clearActiveDetection()
        callback.onDetectionDropped(request, REASON_TIMEOUT)
    }

    fun submitDetection(request: DetectionRequest) {
        scheduler.post {
            if (activeDetectionRequest != null) {
                callback.onDetectionDropped(request, REASON_BUSY)
                return@post
            }
            startDetection(request)
        }
    }

    fun cancelActiveDetection() {
        scheduler.post {
            activeDetectionHandle?.cancel()
            clearActiveDetection()
        }
    }

    fun fetchHazardDetails(request: DetailRequest) {
        scheduler.post {
            activeDetailHandle?.cancel()
            activeDetailHandle = null
            activeDetailRequest = request
            encodeExecutor.execute {
                val base64Image = base64Encoder(request.jpegBytes)
                scheduler.post detailPost@{
                    if (activeDetailRequest != request) {
                        return@detailPost
                    }
                    activeDetailHandle = requestGateway.fetchHazardDetails(
                        request = request,
                        base64Image = base64Image,
                        onChunk = { accumulatedText ->
                            if (activeDetailRequest == request) {
                                callback.onDetailChunk(request, accumulatedText)
                            }
                        },
                        callback = object : AiArSseService.DetailCallback {
                            override fun onOpened(handle: AiArSseService.RequestHandle) {
                                Log.i(TAG, "detail opened taskId=${handle.taskId} requestId=${request.requestId}")
                            }

                            override fun onSuccess(
                                handle: AiArSseService.RequestHandle,
                                fullText: String,
                            ) {
                                if (activeDetailRequest != request) {
                                    return
                                }
                                activeDetailRequest = null
                                activeDetailHandle = null
                                callback.onDetailSuccess(request, fullText)
                            }

                            override fun onFailure(
                                handle: AiArSseService.RequestHandle,
                                message: String,
                            ) {
                                if (activeDetailRequest != request) {
                                    return
                                }
                                activeDetailRequest = null
                                activeDetailHandle = null
                                callback.onDetailFailure(request, message)
                            }
                        },
                    )
                }
            }
        }
    }

    fun cancelAll() {
        scheduler.post {
            scheduler.removeCallbacks(detectionTimeoutRunnable)
            activeDetectionHandle?.cancel()
            clearActiveDetection()
            activeDetailHandle?.cancel()
            activeDetailHandle = null
            activeDetailRequest = null
        }
    }

    fun shutdown() {
        cancelAll()
        encodeExecutor.shutdownNow()
    }

    private fun startDetection(request: DetectionRequest) {
        activeDetectionRequest = request
        activeDetectionStartedElapsedMs = elapsedRealtimeProvider()
        scheduleDetectionTimeout()
        encodeExecutor.execute {
            val base64Image = base64Encoder(request.jpegBytes)
            scheduler.post detectPost@{
                if (activeDetectionRequest != request) {
                    return@detectPost
                }
                activeDetectionHandle = requestGateway.detectHasHazard(
                    request = request,
                    base64Image = base64Image,
                    callback = object : AiArSseService.DetectCallback {
                        override fun onOpened(handle: AiArSseService.RequestHandle) {
                            Log.i(TAG, "detect opened taskId=${handle.taskId} requestId=${request.requestId}")
                        }

                        override fun onSuccess(
                            handle: AiArSseService.RequestHandle,
                            hasHazard: Boolean,
                            fullText: String,
                        ) {
                            if (activeDetectionRequest != request) {
                                return
                            }
                            clearActiveDetection()
                            callback.onDetectionResult(request, hasHazard, fullText)
                        }

                        override fun onFailure(
                            handle: AiArSseService.RequestHandle,
                            message: String,
                        ) {
                            if (activeDetectionRequest != request) {
                                return
                            }
                            clearActiveDetection()
                            callback.onDetectionFailure(request, message)
                        }
                    },
                )
            }
        }
    }

    private fun scheduleDetectionTimeout() {
        scheduler.removeCallbacks(detectionTimeoutRunnable)
        scheduler.postDelayed(detectionTimeoutRunnable, detectTimeoutMs)
    }

    private fun clearActiveDetection() {
        scheduler.removeCallbacks(detectionTimeoutRunnable)
        activeDetectionHandle = null
        activeDetectionRequest = null
        activeDetectionStartedElapsedMs = 0L
    }

    private class SseRequestGateway(
        private val aiArSseService: AiArSseService,
    ) : RequestGateway {
        override fun detectHasHazard(
            request: DetectionRequest,
            base64Image: String,
            callback: AiArSseService.DetectCallback,
        ): AiArSseService.RequestHandle {
            return aiArSseService.detectHasHazard(base64Image, callback)
        }

        override fun fetchHazardDetails(
            request: DetailRequest,
            base64Image: String,
            onChunk: (String) -> Unit,
            callback: AiArSseService.DetailCallback,
        ): AiArSseService.RequestHandle {
            return aiArSseService.fetchHazardDetails(
                base64Image = base64Image,
                onChunk = onChunk,
                callback = callback,
            )
        }
    }

    private class AndroidMainThreadScheduler(
        private val handler: Handler = Handler(Looper.getMainLooper()),
    ) : MainThreadScheduler {
        override fun post(runnable: Runnable) {
            handler.post(runnable)
        }

        override fun postDelayed(runnable: Runnable, delayMs: Long) {
            handler.postDelayed(runnable, delayMs)
        }

        override fun removeCallbacks(runnable: Runnable) {
            handler.removeCallbacks(runnable)
        }
    }

    companion object {
        private const val TAG = "OnlineHazardDetect"
        const val REASON_TIMEOUT = "timeout"
        const val REASON_BUSY = "busy"
    }
}
