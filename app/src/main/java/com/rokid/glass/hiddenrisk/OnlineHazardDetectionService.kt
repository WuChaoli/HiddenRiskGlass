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
 * ctype=1 / ctype=2 检测阶段在单个 service 实例内仅允许单飞，并对单次请求施加超时控制；
 * ctype=0 深度分析阶段按需单次拉取。
 */
internal class OnlineHazardDetectionService(
    private val callback: Callback,
    private val requestGateway: RequestGateway = SseRequestGateway(AiArSseService()),
    private val scheduler: MainThreadScheduler = AndroidMainThreadScheduler(),
    private val elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() },
    private val base64Encoder: (ByteArray) -> String = { Base64.encodeToString(it, Base64.NO_WRAP) },
    private val encodeExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val detectTimeoutMs: Long = InspectionConfigRepository.get().network.aiArApi.detectTimeoutMs,
    private val infoLogger: (String) -> Unit = { message -> Log.i(TAG, message) },
    private val warningLogger: (String) -> Unit = { message -> Log.w(TAG, message) },
) {
    enum class DetectionLane(
        val ctype: Int,
        val logName: String,
    ) {
        ITEM(1, "item"),
        SCENE(2, "scene"),
    }

    data class DetectionRequest(
        val epoch: Long,
        val requestId: Long,
        val jpegBytes: ByteArray,
        val lane: DetectionLane = DetectionLane.ITEM,
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
        fun onDeepAnalysisChunk(request: DetailRequest, accumulatedText: String)
        fun onDeepAnalysisSuccess(request: DetailRequest, fullText: String)
        fun onDeepAnalysisFailure(request: DetailRequest, message: String)
    }

    internal interface RequestGateway {
        fun identifyHazard(
            request: DetectionRequest,
            base64Image: String,
            callback: AiArSseService.DetectCallback,
        ): AiArSseService.RequestHandle

        fun requestDeepAnalysis(
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
        runCatching { warningLogger("detect timeout requestId=${request.requestId}") }
        activeDetectionHandle?.cancel()
        clearActiveDetection()
        callback.onDetectionDropped(request, REASON_TIMEOUT)
    }

    fun submitDetection(request: DetectionRequest) {
        scheduler.post {
            if (activeDetectionRequest != null) {
                warningLogger(
                    "submitDetection droppedBusy lane=${request.lane.logName} requestId=${request.requestId} epoch=${request.epoch} jpegBytes=${request.jpegBytes.size}",
                )
                callback.onDetectionDropped(request, REASON_BUSY)
                return@post
            }
            infoLogger(
                "submitDetection accepted lane=${request.lane.logName} requestId=${request.requestId} epoch=${request.epoch} jpegBytes=${request.jpegBytes.size}",
            )
            startDetection(request)
        }
    }

    fun cancelActiveDetection() {
        scheduler.post {
            activeDetectionHandle?.cancel()
            clearActiveDetection()
        }
    }

    fun requestDeepAnalysis(request: DetailRequest) {
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
                    activeDetailHandle = requestGateway.requestDeepAnalysis(
                        request = request,
                        base64Image = base64Image,
                        onChunk = { accumulatedText ->
                            if (activeDetailRequest == request) {
                                callback.onDeepAnalysisChunk(request, accumulatedText)
                            }
                        },
                        callback = object : AiArSseService.DetailCallback {
                            override fun onOpened(handle: AiArSseService.RequestHandle) {
                                infoLogger("detail opened taskId=${handle.taskId} requestId=${request.requestId}")
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
                                callback.onDeepAnalysisSuccess(request, fullText)
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
                                callback.onDeepAnalysisFailure(request, message)
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
        infoLogger(
            "startDetection encodeStart lane=${request.lane.logName} requestId=${request.requestId} epoch=${request.epoch} jpegBytes=${request.jpegBytes.size} timeoutMs=$detectTimeoutMs",
        )
        encodeExecutor.execute {
            val base64Image = base64Encoder(request.jpegBytes)
            scheduler.post detectPost@{
                if (activeDetectionRequest != request) {
                    infoLogger(
                        "startDetection encodeDiscarded lane=${request.lane.logName} requestId=${request.requestId} epoch=${request.epoch}",
                    )
                    return@detectPost
                }
                infoLogger(
                    "startDetection encoded lane=${request.lane.logName} requestId=${request.requestId} epoch=${request.epoch} jpegBytes=${request.jpegBytes.size} base64Chars=${base64Image.length} elapsedMs=${elapsedRealtimeProvider() - activeDetectionStartedElapsedMs}",
                )
                activeDetectionHandle = requestGateway.identifyHazard(
                    request = request,
                    base64Image = base64Image,
                    callback = object : AiArSseService.DetectCallback {
                        override fun onOpened(handle: AiArSseService.RequestHandle) {
                            infoLogger(
                                "detect opened lane=${request.lane.logName} taskId=${handle.taskId} requestId=${request.requestId} epoch=${request.epoch} jpegBytes=${request.jpegBytes.size} elapsedMs=${elapsedRealtimeProvider() - activeDetectionStartedElapsedMs}",
                            )
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
                            infoLogger(
                                "detect success lane=${request.lane.logName} taskId=${handle.taskId} requestId=${request.requestId} hasHazard=$hasHazard rawTextLength=${fullText.length} totalElapsedMs=${elapsedRealtimeProvider() - activeDetectionStartedElapsedMs}",
                            )
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
                            warningLogger(
                                "detect failure lane=${request.lane.logName} taskId=${handle.taskId} requestId=${request.requestId} epoch=${request.epoch} jpegBytes=${request.jpegBytes.size} totalElapsedMs=${elapsedRealtimeProvider() - activeDetectionStartedElapsedMs} message=$message",
                            )
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
        override fun identifyHazard(
            request: DetectionRequest,
            base64Image: String,
            callback: AiArSseService.DetectCallback,
        ): AiArSseService.RequestHandle {
            return when (request.lane) {
                DetectionLane.ITEM -> aiArSseService.identifyItemHazard(base64Image, callback)
                DetectionLane.SCENE -> aiArSseService.identifySceneHazard(base64Image, callback)
            }
        }

        override fun requestDeepAnalysis(
            request: DetailRequest,
            base64Image: String,
            onChunk: (String) -> Unit,
            callback: AiArSseService.DetailCallback,
        ): AiArSseService.RequestHandle {
            return aiArSseService.requestDeepAnalysis(
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
