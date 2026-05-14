package com.rokid.glass.hiddenrisk

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rokid.glass.config.AiArApiConfig
import com.rokid.glass.config.InspectionConfigRepository
import com.rokid.glass.utils.AppFileLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okio.BufferedSink
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在线识别服务。
 * 支持四个独立端点：隐患物品检测(/ai/auto)、深度分析(/ai/deep)、环境隐患识别(/ai/general)、设备指引(/ai/device)。
 */
class AiArSseService(
    private val autoDetectConfig: AiArApiConfig = InspectionConfigRepository.get().network.aiAutoApi,
    private val deepAnalysisConfig: AiArApiConfig = InspectionConfigRepository.get().network.aiDeepApi,
    private val generalDetectConfig: AiArApiConfig = InspectionConfigRepository.get().network.aiGeneralApi,
    private val generalDeepAnalysisConfig: AiArApiConfig = InspectionConfigRepository.get().network.aiGeneralDeepApi,
    private val deviceGuideConfig: AiArApiConfig = InspectionConfigRepository.get().network.aiDeviceApi,
    private val client: OkHttpClient = createDefaultClient(
        InspectionConfigRepository.get().network.aiAutoApi,
    ),
    private val gson: Gson = Gson(),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    interface DetectCallback {
        fun onOpened(handle: RequestHandle)
        fun onSuccess(handle: RequestHandle, hasHazard: Boolean, fullText: String)
        fun onFailure(handle: RequestHandle, message: String)
    }

    interface DetailCallback {
        fun onOpened(handle: RequestHandle)
        fun onSuccess(handle: RequestHandle, fullText: String)
        fun onFailure(handle: RequestHandle, message: String)
    }

    data class RequestPayload(
        val task_id: String,
        val stream: Boolean = true,
        val image: String? = null,
        val text: String? = null,
        val scene: String? = null,
    )

    data class IdentifyResponse(
        val code: Int,
        val msg: String? = null,
        val task_id: String? = null,
        val content: Boolean,
        val inference_result: List<InferenceResultItem>? = null,
        val cost: Double? = null,
    )

    data class InferenceResultItem(
        val label: String? = null,
        val bbox: List<Double>? = null,
        val score: Double? = null,
        val area_r: Double? = null,
        val inter: Int? = null,
    )

    class RequestHandle(
        val taskId: String,
    ) {
        @Volatile
        private var canceled = false
        @Volatile
        private var eventSource: EventSource? = null
        @Volatile
        private var call: Call? = null

        fun bind(source: EventSource) {
            if (canceled) {
                source.cancel()
                return
            }
            eventSource = source
        }

        fun bind(call: Call) {
            if (canceled) {
                call.cancel()
                return
            }
            this.call = call
        }

        fun cancel() {
            canceled = true
            eventSource?.cancel()
            call?.cancel()
        }

        fun isCanceled(): Boolean = canceled
    }

    private val eventSourceFactory = EventSources.createFactory(client)

    fun identifyItemHazard(
        base64Image: String,
        callback: DetectCallback,
    ): RequestHandle {
        val scene = com.rokid.glass.workflow.InspectionWorkflowSession.enterpriseInfo?.placeCode?.takeIf { it.isNotBlank() }
        return requestHazardDetection(
            base64Image = base64Image,
            scene = scene,
            url = autoDetectConfig.url,
            lane = "auto",
            requireInferenceResults = true,
            callback = callback,
        )
    }

    fun identifySceneHazard(
        base64Image: String,
        callback: DetectCallback,
    ): RequestHandle {
        val scene = com.rokid.glass.workflow.InspectionWorkflowSession.enterpriseInfo?.placeCode?.takeIf { it.isNotBlank() }
        return requestHazardDetection(
            base64Image = base64Image,
            scene = scene,
            url = generalDetectConfig.url,
            lane = "general",
            requireInferenceResults = false,
            callback = callback,
        )
    }

    private fun requestHazardDetection(
        base64Image: String,
        scene: String?,
        url: String,
        lane: String,
        requireInferenceResults: Boolean,
        callback: DetectCallback,
    ): RequestHandle {
        val taskId = System.currentTimeMillis().toString()
        val handle = RequestHandle(taskId = taskId)
        val payload = RequestPayload(task_id = taskId, image = base64Image, scene = scene)
        val requestStartedElapsedMs = SystemClock.elapsedRealtime()
        val jsonBuildStartedElapsedMs = requestStartedElapsedMs
        val jsonBodyString = gson.toJson(payload)
        val jsonBuildFinishedElapsedMs = SystemClock.elapsedRealtime()
        val requestBody = jsonBodyString.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url)
            .tag(RequestTimingTag::class.java, RequestTimingTag(taskId, lane, requestStartedElapsedMs))
            .post(requestBody)
            .build()
        AppFileLogger.i(
            TAG,
            "detectJson request lane=$lane taskId=$taskId endpoint=$url imageChars=${payload.image?.length ?: 0} jsonBuildMs=${jsonBuildFinishedElapsedMs - jsonBuildStartedElapsedMs}",
        )
        val call = client.newCall(request)
        call.enqueue(object : okhttp3.Callback {
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val responseElapsedMs = SystemClock.elapsedRealtime()
                AppFileLogger.i(
                    TAG,
                    "detectJson response lane=$lane taskId=$taskId httpCode=${response.code} elapsedMs=${responseElapsedMs - requestStartedElapsedMs}",
                )
                if (handle.isCanceled()) {
                    return
                }
                if (!response.isSuccessful) {
                    val bodySnippet = runCatching {
                        response.peekBody(MAX_ERROR_BODY_LOG_BYTES).string()
                            .replace(Regex("\\s+"), " ").trim()
                            .take(MAX_ERROR_BODY_LOG_CHARS)
                    }.getOrDefault("")
                    mainHandler.post {
                        if (!handle.isCanceled()) {
                            callback.onFailure(handle, "HTTP ${response.code} | ${response.message} | body=$bodySnippet")
                        }
                    }
                    return
                }
                val body = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                if (body.isBlank()) {
                    mainHandler.post {
                        if (!handle.isCanceled()) {
                            callback.onFailure(handle, "在线识别返回空响应")
                        }
                    }
                    return
                }
                runCatching {
                    val parsed = parseHazardDetectionBody(
                        body = body,
                        requireInferenceResults = requireInferenceResults,
                        preferSse = lane == "general",
                    )
                    AppFileLogger.i(
                        TAG,
                        "detectJson success lane=$lane taskId=$taskId hasHazard=${parsed.hasHazard} inferenceCount=${parsed.inferenceCount} code=${parsed.code}",
                    )
                    mainHandler.post {
                        if (!handle.isCanceled()) {
                            callback.onSuccess(handle, parsed.hasHazard, parsed.rawText)
                        }
                    }
                }.onFailure { error ->
                    AppFileLogger.e(
                        TAG,
                        "detectJson parse failed lane=$lane taskId=$taskId body=${body.take(256)}",
                        error,
                    )
                    mainHandler.post {
                        if (!handle.isCanceled()) {
                            callback.onFailure(handle, error.message ?: "在线识别响应解析失败")
                        }
                    }
                }
            }

            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                AppFileLogger.e(
                    TAG,
                    "detectJson network failed lane=$lane taskId=$taskId error=${e.javaClass.simpleName}:${e.message}",
                    e,
                )
                mainHandler.post {
                    if (!handle.isCanceled()) {
                        callback.onFailure(handle, e.message ?: "网络请求失败")
                    }
                }
            }
        })
        handle.bind(call)
        return handle
    }

    fun requestDeepAnalysis(
        base64Image: String,
        onChunk: (String) -> Unit = {},
        callback: DetailCallback,
    ): RequestHandle {
        return requestDeepAnalysis(
            base64Image = base64Image,
            url = deepAnalysisConfig.url,
            lane = "deep",
            onChunk = onChunk,
            callback = callback,
        )
    }

    fun requestGeneralDeepAnalysis(
        base64Image: String,
        onChunk: (String) -> Unit = {},
        callback: DetailCallback,
    ): RequestHandle {
        return requestDeepAnalysis(
            base64Image = base64Image,
            url = generalDeepAnalysisConfig.url,
            lane = "general_deep",
            onChunk = onChunk,
            callback = callback,
        )
    }

    private fun requestDeepAnalysis(
        base64Image: String,
        url: String,
        lane: String,
        onChunk: (String) -> Unit,
        callback: DetailCallback,
    ): RequestHandle {
        val taskId = System.currentTimeMillis().toString()
        val handle = RequestHandle(taskId = taskId)
        val aggregator = AiArEventAggregator(gson)
        val scene = com.rokid.glass.workflow.InspectionWorkflowSession.enterpriseInfo?.placeCode?.takeIf { it.isNotBlank() }
        openStream(
            handle = handle,
            payload = RequestPayload(task_id = taskId, image = base64Image, scene = scene),
            url = url,
            lane = lane,
            onOpened = { callback.onOpened(handle) },
            onChunk = onChunk,
            onClosed = { fullText ->
                callback.onSuccess(handle, fullText)
            },
            onFailure = { message ->
                callback.onFailure(handle, message)
            },
            aggregator = aggregator,
        )
        return handle
    }

    fun fetchInspectionGuide(
        text: String,
        onChunk: (String) -> Unit = {},
        callback: DetailCallback,
    ): RequestHandle {
        val taskId = System.currentTimeMillis().toString()
        val handle = RequestHandle(taskId = taskId)
        val aggregator = AiArEventAggregator(gson)
        val scene = com.rokid.glass.workflow.InspectionWorkflowSession.enterpriseInfo?.placeCode?.takeIf { it.isNotBlank() }
        openStream(
            handle = handle,
            payload = RequestPayload(task_id = taskId, text = text, scene = scene),
            url = deviceGuideConfig.url,
            lane = "device",
            onOpened = { callback.onOpened(handle) },
            onChunk = onChunk,
            onClosed = { fullText ->
                callback.onSuccess(handle, fullText)
            },
            onFailure = { message ->
                callback.onFailure(handle, message)
            },
            aggregator = aggregator,
        )
        return handle
    }

    private fun openStream(
        handle: RequestHandle,
        payload: RequestPayload,
        url: String,
        lane: String,
        onOpened: () -> Unit,
        onChunk: (String) -> Unit = {},
        onClosed: (String) -> Unit,
        onFailure: (String) -> Unit,
        aggregator: AiArEventAggregator,
    ) {
        val requestStartedElapsedMs = SystemClock.elapsedRealtime()
        val jsonBuildStartedElapsedMs = requestStartedElapsedMs
        val rawRequestBody = gson.toJson(payload).toRequestBody(JSON_MEDIA_TYPE)
        val jsonBuildFinishedElapsedMs = SystemClock.elapsedRealtime()
        val requestBody = TimingRequestBody(
            delegate = rawRequestBody,
            taskId = payload.task_id,
            lane = lane,
            requestStartedElapsedMs = requestStartedElapsedMs,
        )
        val requestBuildStartedElapsedMs = SystemClock.elapsedRealtime()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .tag(RequestTimingTag::class.java, RequestTimingTag(payload.task_id, lane, requestStartedElapsedMs))
            .post(requestBody)
            .build()
        val requestBuildFinishedElapsedMs = SystemClock.elapsedRealtime()
        AppFileLogger.i(
            TAG,
            "openStream requestStart lane=$lane taskId=${payload.task_id} endpoint=$url imageChars=${payload.image?.length ?: 0} textLength=${payload.text?.length ?: 0} jsonBuildMs=${jsonBuildFinishedElapsedMs - jsonBuildStartedElapsedMs} requestBuildMs=${requestBuildFinishedElapsedMs - requestBuildStartedElapsedMs}",
        )
        val newEventSourceStartedElapsedMs = SystemClock.elapsedRealtime()
        val eventSource = eventSourceFactory.newEventSource(
            request,
            object : EventSourceListener() {
                private val terminalDelivered = AtomicBoolean(false)
                private var firstEventElapsedMs = 0L

                override fun onOpen(eventSource: EventSource, response: Response) {
                    val openedElapsedMs = SystemClock.elapsedRealtime()
                    AppFileLogger.i(
                        TAG,
                        "openStream opened lane=$lane taskId=${payload.task_id} uploadToOpenedMs=${openedElapsedMs - requestStartedElapsedMs} endpoint=$url requestUrl=${response.request.url} httpCode=${response.code} httpMessage=${response.message} contentType=${response.header("Content-Type")}",
                    )
                    mainHandler.post {
                        if (!handle.isCanceled()) {
                            onOpened()
                        }
                    }
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    if (handle.isCanceled()) {
                        return
                    }
                    val normalizedData = data.trim()
                    if (isDoneEvent(type, normalizedData)) {
                        AppFileLogger.i(TAG, "openStream received done sentinel lane=$lane taskId=${payload.task_id}")
                        return
                    }
                    if (normalizedData.isEmpty()) {
                        return
                    }
                    if (firstEventElapsedMs == 0L) {
                        firstEventElapsedMs = SystemClock.elapsedRealtime()
                        AppFileLogger.i(
                            TAG,
                            "openStream firstEvent lane=$lane taskId=${payload.task_id} uploadToFirstEventMs=${firstEventElapsedMs - requestStartedElapsedMs} id=${id ?: "(none)"} type=${type ?: "(none)"} dataChars=${normalizedData.length}",
                        )
                    }
                    runCatching {
                        aggregator.append(normalizedData)
                        aggregator.fullText()
                    }.onSuccess { accumulatedText ->
                        mainHandler.post {
                            if (!handle.isCanceled() && !terminalDelivered.get()) {
                                // 详情流式阶段统一向上游传递累计全文，避免 UI 层重复拼接。
                                onChunk(accumulatedText)
                            }
                        }
                    }.onFailure { error ->
                        AppFileLogger.e(TAG, "openStream parse event failed lane=$lane taskId=${payload.task_id} data=$normalizedData", error)
                        eventSource.cancel()
                        deliverFailure(
                            handle = handle,
                            onFailure = onFailure,
                            message = error.message ?: "在线识别事件解析失败",
                            terminalDelivered = terminalDelivered,
                        )
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    if (handle.isCanceled()) {
                        return
                    }
                    if (!terminalDelivered.compareAndSet(false, true)) {
                        return
                    }
                    val fullText = aggregator.fullText().trim()
                    val closedElapsedMs = SystemClock.elapsedRealtime()
                    AppFileLogger.i(
                        TAG,
                        "openStream closed lane=$lane taskId=${payload.task_id} uploadToClosedMs=${closedElapsedMs - requestStartedElapsedMs} firstEventToClosedMs=${durationOrMinusOne(firstEventElapsedMs, closedElapsedMs)} fullTextLength=${fullText.length}",
                    )
                    AppFileLogger.i(
                        TAG,
                        "openStream fullText lane=$lane taskId=${payload.task_id} text=${summarizeSseLogText(fullText)}",
                    )
                    mainHandler.post {
                        if (!handle.isCanceled()) {
                            runCatching {
                                onClosed(fullText)
                            }.onFailure { error ->
                                terminalDelivered.set(false)
                                deliverFailure(
                                    handle = handle,
                                    onFailure = onFailure,
                                    message = error.message ?: "在线识别结果解析失败",
                                    terminalDelivered = terminalDelivered,
                                )
                            }
                        }
                    }
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?,
                ) {
                    if (handle.isCanceled()) {
                        return
                    }
                    val responseCode = response?.code
                    val responseMessage = response?.message
                    val responseContentType = response?.header("Content-Type")
                    val responseBodySnippet = extractResponseBodySnippet(response)
                    val message = buildFailureMessage(
                        throwable = t,
                        responseCode = responseCode,
                        responseMessage = responseMessage,
                        responseBodySnippet = responseBodySnippet,
                    )
                    AppFileLogger.e(
                        TAG,
                        "openStream failed lane=$lane taskId=${payload.task_id} endpoint=$url requestUrl=${eventSource.request().url} throwable=${t?.javaClass?.simpleName} httpCode=$responseCode httpMessage=$responseMessage contentType=$responseContentType bodySnippet=$responseBodySnippet",
                        t,
                    )
                    deliverFailure(
                        handle = handle,
                        onFailure = onFailure,
                        message = message,
                        terminalDelivered = terminalDelivered,
                    )
                }
            },
        )
        val newEventSourceFinishedElapsedMs = SystemClock.elapsedRealtime()
        AppFileLogger.i(
            TAG,
            "openStream newEventSourceReturned lane=$lane taskId=${payload.task_id} requestStartToReturnMs=${newEventSourceFinishedElapsedMs - requestStartedElapsedMs} newEventSourceMs=${newEventSourceFinishedElapsedMs - newEventSourceStartedElapsedMs}",
        )
        handle.bind(eventSource)
    }

    private fun deliverFailure(
        handle: RequestHandle,
        onFailure: (String) -> Unit,
        message: String,
        terminalDelivered: AtomicBoolean,
    ) {
        if (!terminalDelivered.compareAndSet(false, true)) {
            return
        }
        mainHandler.post {
            if (!handle.isCanceled()) {
                onFailure(message)
            }
        }
    }

    private fun buildFailureMessage(
        throwable: Throwable?,
        responseCode: Int?,
        responseMessage: String?,
        responseBodySnippet: String?,
    ): String {
        val parts = mutableListOf<String>()
        if (responseCode != null) {
            parts += "HTTP $responseCode"
        }
        if (!responseMessage.isNullOrBlank()) {
            parts += responseMessage.trim()
        }
        if (!throwable?.message.isNullOrBlank()) {
            parts += throwable!!.message!!.trim()
        }
        if (!responseBodySnippet.isNullOrBlank()) {
            parts += "body=$responseBodySnippet"
        }
        return parts.joinToString(separator = " | ").ifBlank { "在线识别失败" }
    }

    private fun extractResponseBodySnippet(response: Response?): String? {
        val body = runCatching { response?.peekBody(MAX_ERROR_BODY_LOG_BYTES)?.string().orEmpty() }
            .getOrDefault("")
        if (body.isBlank()) {
            return null
        }
        return body.replace(Regex("\\s+"), " ").trim().take(MAX_ERROR_BODY_LOG_CHARS)
    }

    companion object {
        private const val TAG = "AiArSseService"
        private const val DONE_SENTINEL = "[DONE]"
        private const val DONE_SENTINEL_JSON_ARRAY = "[\"DONE\"]"
        private const val DONE_EVENT_TYPE = "done"
        private const val MAX_ERROR_BODY_LOG_BYTES = 4096L
        private const val MAX_ERROR_BODY_LOG_CHARS = 512
        private const val MAX_SSE_BODY_LOG_CHARS = 4096
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private fun summarizeSseLogText(text: String): String {
            val normalized = text.replace("\r", "\\r").replace("\n", "\\n")
            return if (normalized.length <= MAX_SSE_BODY_LOG_CHARS) {
                normalized
            } else {
                "${normalized.take(MAX_SSE_BODY_LOG_CHARS)}...(truncated ${normalized.length - MAX_SSE_BODY_LOG_CHARS} chars)"
            }
        }

        private fun createDefaultClient(apiConfig: AiArApiConfig): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(apiConfig.connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(apiConfig.readTimeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(apiConfig.writeTimeoutMs, TimeUnit.MILLISECONDS)
                .eventListenerFactory(TimingEventListenerFactory)
                .build()
        }

        private fun durationOrMinusOne(startElapsedMs: Long, endElapsedMs: Long): Long {
            return if (startElapsedMs > 0L && endElapsedMs >= startElapsedMs) {
                endElapsedMs - startElapsedMs
            } else {
                -1L
            }
        }

        internal fun isDoneEvent(type: String?, normalizedData: String): Boolean {
            if (type.equals(DONE_EVENT_TYPE, ignoreCase = true)) {
                return true
            }
            return normalizedData == DONE_SENTINEL || normalizedData == DONE_SENTINEL_JSON_ARRAY
        }

        internal fun hasHazardFromIdentifyResponse(
            parsed: IdentifyResponse,
            requireInferenceResults: Boolean,
        ): Boolean {
            return parsed.content &&
                (!requireInferenceResults || !parsed.inference_result.isNullOrEmpty())
        }

        internal fun parseHazardDetectionBody(
            body: String,
            requireInferenceResults: Boolean,
            preferSse: Boolean,
            gson: Gson = Gson(),
        ): HazardDetectionParseResult {
            val sseObjects = parseSseDataObjects(body)
            if (preferSse && sseObjects.isNotEmpty()) {
                return parseHazardDetectionObjects(sseObjects, requireInferenceResults, gson)
            }
            return runCatching {
                parseHazardDetectionObjects(
                    objects = listOf(JsonParser.parseString(body).asJsonObject),
                    requireInferenceResults = requireInferenceResults,
                    gson = gson,
                )
            }.getOrElse { jsonError ->
                if (sseObjects.isNotEmpty()) {
                    parseHazardDetectionObjects(sseObjects, requireInferenceResults, gson)
                } else {
                    throw jsonError
                }
            }
        }

        private fun parseHazardDetectionObjects(
            objects: List<JsonObject>,
            requireInferenceResults: Boolean,
            gson: Gson,
        ): HazardDetectionParseResult {
            require(objects.isNotEmpty()) { "No hazard detection payload found" }
            val hazardObject = objects.firstOrNull { contentAsBoolean(it.get("content")) } ?: objects.last()
            val inferenceElement = hazardObject.get("inference_result")
            val inferenceCount = inferenceElement
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.size()
                ?: 0
            val hasHazard = contentAsBoolean(hazardObject.get("content")) &&
                (!requireInferenceResults || inferenceCount > 0)
            val code = hazardObject.get("code")
                ?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
                ?.asJsonPrimitive
                ?.takeIf { it.isNumber }
                ?.asInt
            val rawText = if (inferenceElement != null && !inferenceElement.isJsonNull) {
                gson.toJson(inferenceElement)
            } else {
                gson.toJson(hazardObject)
            }
            return HazardDetectionParseResult(
                hasHazard = hasHazard,
                rawText = rawText,
                inferenceCount = inferenceCount,
                code = code,
            )
        }

        private fun parseSseDataObjects(body: String): List<JsonObject> {
            return body.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .filter { it.isNotBlank() && it != DONE_SENTINEL && it != DONE_SENTINEL_JSON_ARRAY }
                .mapNotNull { data ->
                    runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull()
                }
                .toList()
        }

        private fun contentAsBoolean(content: JsonElement?): Boolean {
            if (content == null || content.isJsonNull || !content.isJsonPrimitive) {
                return false
            }
            val primitive = content.asJsonPrimitive
            if (primitive.isBoolean) {
                return primitive.asBoolean
            }
            if (!primitive.isString) {
                return false
            }
            return when (primitive.asString.trim().lowercase()) {
                "true", "yes", "y", "1", "是", "有", "存在" -> true
                else -> false
            }
        }
    }

    data class HazardDetectionParseResult(
        val hasHazard: Boolean,
        val rawText: String,
        val inferenceCount: Int,
        val code: Int?,
    )

    private data class RequestTimingTag(
        val taskId: String,
        val lane: String,
        val requestStartedElapsedMs: Long,
    )

    private class TimingRequestBody(
        private val delegate: RequestBody,
        private val taskId: String,
        private val lane: String,
        private val requestStartedElapsedMs: Long,
    ) : RequestBody() {
        override fun contentType() = delegate.contentType()

        override fun contentLength(): Long = delegate.contentLength()

        override fun writeTo(sink: BufferedSink) {
            val bodyStartElapsedMs = SystemClock.elapsedRealtime()
            Log.i(
                TAG,
                "openStream bodyTiming event=writeStart lane=$lane taskId=$taskId requestStartToBodyStartMs=${bodyStartElapsedMs - requestStartedElapsedMs} contentLength=${contentLength()}",
            )
            delegate.writeTo(sink)
            val bodyEndElapsedMs = SystemClock.elapsedRealtime()
            Log.i(
                TAG,
                "openStream bodyTiming event=writeEnd lane=$lane taskId=$taskId requestStartToBodyEndMs=${bodyEndElapsedMs - requestStartedElapsedMs} requestBodyMs=${durationOrMinusOne(bodyStartElapsedMs, bodyEndElapsedMs)} contentLength=${contentLength()}",
            )
        }
    }

    private object TimingEventListenerFactory : EventListener.Factory {
        override fun create(call: Call): EventListener {
            val tag = call.request().tag(RequestTimingTag::class.java)
            return if (tag == null) {
                EventListener.NONE
            } else {
                TimingEventListener(tag)
            }
        }
    }

    private class TimingEventListener(
        private val tag: RequestTimingTag,
    ) : EventListener() {
        private var requestBodyStartedElapsedMs = 0L
        private var requestBodyEndedElapsedMs = 0L

        override fun callStart(call: Call) {
            logTiming("callStart")
        }

        override fun requestBodyStart(call: Call) {
            requestBodyStartedElapsedMs = SystemClock.elapsedRealtime()
            logTiming("requestBodyStart")
        }

        override fun requestBodyEnd(call: Call, byteCount: Long) {
            requestBodyEndedElapsedMs = SystemClock.elapsedRealtime()
            Log.i(
                TAG,
                "openStream networkTiming event=requestBodyEnd lane=${tag.lane} taskId=${tag.taskId} requestStartToEventMs=${requestBodyEndedElapsedMs - tag.requestStartedElapsedMs} requestBodyMs=${durationOrMinusOne(requestBodyStartedElapsedMs, requestBodyEndedElapsedMs)} byteCount=$byteCount",
            )
        }

        override fun responseHeadersStart(call: Call) {
            val nowElapsedMs = SystemClock.elapsedRealtime()
            Log.i(
                TAG,
                "openStream networkTiming event=responseHeadersStart lane=${tag.lane} taskId=${tag.taskId} requestStartToEventMs=${nowElapsedMs - tag.requestStartedElapsedMs} bodyEndToHeadersMs=${durationOrMinusOne(requestBodyEndedElapsedMs, nowElapsedMs)}",
            )
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            val nowElapsedMs = SystemClock.elapsedRealtime()
            Log.i(
                TAG,
                "openStream networkTiming event=responseHeadersEnd lane=${tag.lane} taskId=${tag.taskId} requestStartToEventMs=${nowElapsedMs - tag.requestStartedElapsedMs} httpCode=${response.code} httpMessage=${response.message}",
            )
        }

        override fun callFailed(call: Call, ioe: java.io.IOException) {
            val nowElapsedMs = SystemClock.elapsedRealtime()
            Log.w(
                TAG,
                "openStream networkTiming event=callFailed lane=${tag.lane} taskId=${tag.taskId} requestStartToEventMs=${nowElapsedMs - tag.requestStartedElapsedMs} error=${ioe.javaClass.simpleName}:${ioe.message}",
            )
        }

        private fun logTiming(event: String) {
            val nowElapsedMs = SystemClock.elapsedRealtime()
            Log.i(
                TAG,
                "openStream networkTiming event=$event lane=${tag.lane} taskId=${tag.taskId} requestStartToEventMs=${nowElapsedMs - tag.requestStartedElapsedMs}",
            )
        }
    }
}
