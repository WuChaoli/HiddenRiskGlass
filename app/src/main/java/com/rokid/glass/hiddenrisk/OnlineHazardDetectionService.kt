package com.rokid.glass.hiddenrisk

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import com.rokid.glass.config.AutoDetectProvider
import com.rokid.glass.config.InspectionConfigRepository
import com.rokid.glass.utils.AppFileLogger

/**
 * 在线隐患识别调度服务。
 * 物品检测阶段在单个 service 实例内允许受限并发，并对每个请求施加独立超时控制；
 * 深度分析阶段按需单次拉取。
 */
internal class OnlineHazardDetectionService(
    private val callback: Callback,
    private val base64Encoder: (ByteArray) -> String = { Base64.encodeToString(it, Base64.NO_WRAP) },
    private val requestGateway: RequestGateway = createDefaultRequestGateway(base64Encoder = base64Encoder),
    private val scheduler: MainThreadScheduler = AndroidMainThreadScheduler(),
    private val elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() },
    private val detectTimeoutMs: Long = DEFAULTS.detectTimeoutMs,
    private val detectConcurrencyLimit: Int = DEFAULTS.detectConcurrencyLimit,
    private val infoLogger: (String) -> Unit = { message -> AppFileLogger.i(TAG, message) },
    private val warningLogger: (String) -> Unit = { message -> AppFileLogger.w(TAG, message) },
) {
    enum class DetectionLane(
        val logName: String,
    ) {
        ITEM("item"),
        SCENE("scene"),
    }

    data class DetectionRequest(
        val epoch: Long,
        val requestId: Long,
        val jpegBytes: ByteArray,
        val lane: DetectionLane = DetectionLane.ITEM,
        val frameTimestamp: Long = 0L,
        val frameCapturedAtElapsedMs: Long = 0L,
        val framePayloadBuiltAtElapsedMs: Long = 0L,
        val cooldownLabels: List<String> = emptyList(),
    )

    data class DetailRequest(
        val epoch: Long,
        val requestId: Long,
        val jpegBytes: ByteArray,
        val lane: DetectionLane = DetectionLane.ITEM,
    )

    interface Callback {
        fun onDetectionResult(request: DetectionRequest, hasHazard: Boolean, rawText: String, labels: List<String>)
        fun onDetectionFailure(request: DetectionRequest, message: String)
        fun onDetectionDropped(request: DetectionRequest, reason: String)
        fun onDeepAnalysisChunk(request: DetailRequest, accumulatedText: String)
        fun onDeepAnalysisSuccess(request: DetailRequest, fullText: String)
        fun onDeepAnalysisFailure(request: DetailRequest, message: String)
    }

    internal interface RequestGateway {
        fun identifyHazard(
            request: DetectionRequest,
            callback: AiArSseService.DetectCallback,
        ): AiArSseService.RequestHandle

        fun requestDeepAnalysis(
            request: DetailRequest,
            onChunk: (String) -> Unit,
            callback: AiArSseService.DetailCallback,
        ): AiArSseService.RequestHandle
    }

    internal interface MainThreadScheduler {
        fun post(runnable: Runnable)
        fun postDelayed(runnable: Runnable, delayMs: Long)
        fun removeCallbacks(runnable: Runnable)
    }

    private data class ActiveDetection(
        val request: DetectionRequest,
        val startedElapsedMs: Long,
        val timeoutRunnable: Runnable,
        var handle: AiArSseService.RequestHandle? = null,
    )

    private val activeDetections = linkedMapOf<Long, ActiveDetection>()
    private var activeDetailRequest: DetailRequest? = null
    private var activeDetailHandle: AiArSseService.RequestHandle? = null

    fun submitDetection(request: DetectionRequest) {
        scheduler.post {
            val limit = detectConcurrencyLimit.coerceAtLeast(1)
            if (activeDetections.size >= limit) {
                warningLogger(
                    "submitDetection droppedBusy lane=${request.lane.logName} requestId=${request.requestId} epoch=${request.epoch} activePoolSize=${activeDetections.size} concurrencyLimit=$limit jpegBytes=${request.jpegBytes.size}",
                )
                callback.onDetectionDropped(request, REASON_BUSY)
                return@post
            }
            infoLogger(
                "submitDetection accepted lane=${request.lane.logName} requestId=${request.requestId} epoch=${request.epoch} activePoolSize=${activeDetections.size + 1} concurrencyLimit=$limit jpegBytes=${request.jpegBytes.size}",
            )
            startDetection(request)
        }
    }

    fun cancelActiveDetection() {
        scheduler.post {
            cancelAllActiveDetections(reason = "cancel_active")
        }
    }

    fun requestDeepAnalysis(request: DetailRequest) {
        scheduler.post {
            infoLogger(
                "detail request queued lane=${request.lane.logName} requestId=${request.requestId} epoch=${request.epoch} jpegBytes=${request.jpegBytes.size}",
            )
            activeDetailHandle?.cancel()
            activeDetailHandle = null
            activeDetailRequest = request
            activeDetailHandle = requestGateway.requestDeepAnalysis(
                request = request,
                onChunk = { accumulatedText ->
                    if (activeDetailRequest == request) {
                        callback.onDeepAnalysisChunk(request, accumulatedText)
                    }
                },
                callback = object : AiArSseService.DetailCallback {
                    override fun onOpened(handle: AiArSseService.RequestHandle) {
                        infoLogger(
                            "detail opened lane=${request.lane.logName} taskId=${handle.taskId} requestId=${request.requestId}",
                        )
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

    fun cancelAll() {
        scheduler.post {
            cancelAllActiveDetections(reason = "cancel_all")
            activeDetailHandle?.cancel()
            activeDetailHandle = null
            activeDetailRequest = null
        }
    }

    fun shutdown() {
        cancelAll()
    }

    private fun startDetection(request: DetectionRequest) {
        val startedElapsedMs = elapsedRealtimeProvider()
        val timeoutRunnable = Runnable {
            handleDetectionTimeout(request.requestId)
        }
        activeDetections[request.requestId] = ActiveDetection(
            request = request,
            startedElapsedMs = startedElapsedMs,
            timeoutRunnable = timeoutRunnable,
        )
        scheduler.postDelayed(timeoutRunnable, detectTimeoutMs)
        infoLogger(
            "startDetection uploadStart lane=${request.lane.logName} requestId=${request.requestId} epoch=${request.epoch} activePoolSize=${activeDetections.size} concurrencyLimit=${detectConcurrencyLimit.coerceAtLeast(1)} jpegBytes=${request.jpegBytes.size} timeoutMs=$detectTimeoutMs captureToSubmitMs=${durationOrMinusOne(request.frameCapturedAtElapsedMs, startedElapsedMs)} payloadBuiltToSubmitMs=${durationOrMinusOne(request.framePayloadBuiltAtElapsedMs, startedElapsedMs)}",
        )
        val active = activeDetections[request.requestId] ?: return
        val uploadStartedElapsedMs = elapsedRealtimeProvider()
        val handle = requestGateway.identifyHazard(
            request = request,
            callback = object : AiArSseService.DetectCallback {
                override fun onOpened(handle: AiArSseService.RequestHandle) {
                    val openedActive = activeDetections[request.requestId] ?: return
                    infoLogger(
                        "detect opened lane=${request.lane.logName} taskId=${handle.taskId} requestId=${request.requestId} epoch=${request.epoch} activePoolSize=${activeDetections.size} jpegBytes=${request.jpegBytes.size} elapsedMs=${elapsedRealtimeProvider() - openedActive.startedElapsedMs}",
                    )
                }

                override fun onSuccess(
                    handle: AiArSseService.RequestHandle,
                    hasHazard: Boolean,
                    fullText: String,
                    labels: List<String>,
                ) {
                    val completedActive = removeActiveDetection(request.requestId) ?: return
                    val completedElapsedMs = elapsedRealtimeProvider()
                    val detectElapsedMs = completedElapsedMs - completedActive.startedElapsedMs
                    val submitToUploadMs = uploadStartedElapsedMs - completedActive.startedElapsedMs
                    val captureToHasHazardMs = durationOrMinusOne(
                        request.frameCapturedAtElapsedMs,
                        completedElapsedMs,
                    )
                    val uploadToHasHazardMs = completedElapsedMs - uploadStartedElapsedMs
                    infoLogger(
                        "detect success lane=${request.lane.logName} taskId=${handle.taskId} requestId=${request.requestId} hasHazard=$hasHazard activePoolSize=${activeDetections.size} rawTextLength=${fullText.length} labelCount=${labels.size} totalElapsedMs=$detectElapsedMs",
                    )
                    infoLogger(
                        "detect timing summary lane=${request.lane.logName} taskId=${handle.taskId} requestId=${request.requestId} epoch=${request.epoch} frameTs=${request.frameTimestamp} hasHazard=$hasHazard captureToUploadMs=${durationOrMinusOne(request.frameCapturedAtElapsedMs, uploadStartedElapsedMs)} payloadBuiltToUploadMs=${durationOrMinusOne(request.framePayloadBuiltAtElapsedMs, uploadStartedElapsedMs)} submitToUploadMs=$submitToUploadMs uploadToHasHazardMs=$uploadToHasHazardMs captureToHasHazardMs=$captureToHasHazardMs detectServiceElapsedMs=$detectElapsedMs rawTextLength=${fullText.length} labelCount=${labels.size} jpegBytes=${request.jpegBytes.size}",
                    )
                    callback.onDetectionResult(
                        request.copy(cooldownLabels = labels),
                        hasHazard,
                        fullText,
                        labels,
                    )
                }

                override fun onFailure(
                    handle: AiArSseService.RequestHandle,
                    message: String,
                ) {
                    val failedActive = removeActiveDetection(request.requestId) ?: return
                    val failedElapsedMs = elapsedRealtimeProvider()
                    val detectElapsedMs = failedElapsedMs - failedActive.startedElapsedMs
                    warningLogger(
                        "detect failure lane=${request.lane.logName} taskId=${handle.taskId} requestId=${request.requestId} epoch=${request.epoch} activePoolSize=${activeDetections.size} jpegBytes=${request.jpegBytes.size} totalElapsedMs=$detectElapsedMs captureToFailureMs=${durationOrMinusOne(request.frameCapturedAtElapsedMs, failedElapsedMs)} message=$message",
                    )
                    callback.onDetectionFailure(request, message)
                }
            },
        )
        active.handle = handle
    }

    private fun handleDetectionTimeout(requestId: Long) {
        val active = removeActiveDetection(requestId) ?: return
        val request = active.request
        if (elapsedRealtimeProvider() - active.startedElapsedMs < detectTimeoutMs) {
            return
        }
        runCatching {
            warningLogger(
                "detect timeout lane=${request.lane.logName} requestId=${request.requestId} activePoolSize=${activeDetections.size} timeoutMs=$detectTimeoutMs",
            )
        }
        active.handle?.cancel()
        callback.onDetectionDropped(request, REASON_TIMEOUT)
    }

    private fun removeActiveDetection(requestId: Long): ActiveDetection? {
        val active = activeDetections.remove(requestId) ?: return null
        scheduler.removeCallbacks(active.timeoutRunnable)
        return active
    }

    private fun cancelAllActiveDetections(reason: String) {
        if (activeDetections.isEmpty()) {
            return
        }
        val requestIds = activeDetections.keys.toList()
        infoLogger("cancel active detections reason=$reason requestIds=$requestIds activePoolSize=${activeDetections.size}")
        val activeItems = activeDetections.values.toList()
        activeDetections.clear()
        activeItems.forEach { active ->
            scheduler.removeCallbacks(active.timeoutRunnable)
            active.handle?.cancel()
        }
    }

    private class SseRequestGateway(
        aiArSseServiceProvider: () -> AiArSseService,
        private val base64Encoder: (ByteArray) -> String,
    ) : RequestGateway {
        private val aiArSseService: AiArSseService by lazy(aiArSseServiceProvider)

        override fun identifyHazard(
            request: DetectionRequest,
            callback: AiArSseService.DetectCallback,
        ): AiArSseService.RequestHandle {
            val base64Image = base64Encoder(request.jpegBytes)
            return when (request.lane) {
                DetectionLane.ITEM -> aiArSseService.identifyItemHazard(base64Image, callback)
                DetectionLane.SCENE -> aiArSseService.identifySceneHazard(base64Image, callback)
            }
        }

        override fun requestDeepAnalysis(
            request: DetailRequest,
            onChunk: (String) -> Unit,
            callback: AiArSseService.DetailCallback,
        ): AiArSseService.RequestHandle {
            val base64Image = base64Encoder(request.jpegBytes)
            return when (request.lane) {
                DetectionLane.ITEM -> aiArSseService.requestDeepAnalysis(
                    base64Image = base64Image,
                    onChunk = onChunk,
                    callback = callback,
                )
                DetectionLane.SCENE -> aiArSseService.requestGeneralDeepAnalysis(
                    base64Image = base64Image,
                    onChunk = onChunk,
                    callback = callback,
                )
            }
        }
    }

    private class LocalTriggerRequestGateway(
        private val localTriggerDetectionService: LocalTriggerDetectionService,
        private val detailGateway: RequestGateway,
    ) : RequestGateway {
        override fun identifyHazard(
            request: DetectionRequest,
            callback: AiArSseService.DetectCallback,
        ): AiArSseService.RequestHandle {
            return when (request.lane) {
                DetectionLane.ITEM -> localTriggerDetectionService.detect(request, callback)
                DetectionLane.SCENE -> detailGateway.identifyHazard(request, callback)
            }
        }

        override fun requestDeepAnalysis(
            request: DetailRequest,
            onChunk: (String) -> Unit,
            callback: AiArSseService.DetailCallback,
        ): AiArSseService.RequestHandle {
            return detailGateway.requestDeepAnalysis(request, onChunk, callback)
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

        internal fun createDefaultRequestGateway(
            provider: AutoDetectProvider = InspectionConfigRepository.get().aiInspection.autoDetectProvider,
            aiArSseService: AiArSseService? = null,
            localTriggerDetectionService: LocalTriggerDetectionService? = null,
            base64Encoder: (ByteArray) -> String = { Base64.encodeToString(it, Base64.NO_WRAP) },
        ): RequestGateway {
            return when (provider) {
                AutoDetectProvider.HTTP -> SseRequestGateway(
                    aiArSseServiceProvider = { aiArSseService ?: AiArSseService() },
                    base64Encoder = base64Encoder,
                )
                AutoDetectProvider.LOCAL_TRIGGER -> LocalTriggerRequestGateway(
                    localTriggerDetectionService = requireNotNull(localTriggerDetectionService) {
                        "LOCAL_TRIGGER provider requires LocalTriggerDetectionService"
                    },
                    detailGateway = SseRequestGateway(
                        aiArSseServiceProvider = { aiArSseService ?: AiArSseService() },
                        base64Encoder = base64Encoder,
                    ),
                )
            }
        }

        private object DEFAULTS {
            val cfg = InspectionConfigRepository.get()
            val detectTimeoutMs = cfg.network.aiAutoApi.detectTimeoutMs
            val detectConcurrencyLimit = cfg.aiInspection.onlineDetectConcurrencyLimit
        }

        private fun durationOrMinusOne(startElapsedMs: Long, endElapsedMs: Long): Long {
            return if (startElapsedMs > 0L && endElapsedMs >= startElapsedMs) {
                endElapsedMs - startElapsedMs
            } else {
                -1L
            }
        }
    }
}
