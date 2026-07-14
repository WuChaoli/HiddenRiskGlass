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
import com.rokid.glass.network.HttpClientProvider
import com.rokid.glass.utils.AppFileLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okio.BufferedSink
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在线识别服务。
 * 支持四个独立端点：隐患物品检测(/ai/auto)、深度分析(/ai/deep)、环境隐患识别(/ai/general)、设备指引(/ai/device)。
 */
class AiArSseService(
    private val autoDetectConfig: AiArApiConfig = DEFAULTS.autoDetectConfig,
    private val deepAnalysisConfig: AiArApiConfig = DEFAULTS.deepAnalysisConfig,
    private val gmAnalysisConfig: AiArApiConfig = DEFAULTS.gmAnalysisConfig,
    private val generalDetectConfig: AiArApiConfig = DEFAULTS.generalDetectConfig,
    private val generalDeepAnalysisConfig: AiArApiConfig = DEFAULTS.generalDeepAnalysisConfig,
    private val deviceGuideConfig: AiArApiConfig = DEFAULTS.deviceGuideConfig,
    private val suggestionChecksConfig: AiArApiConfig = DEFAULTS.suggestionChecksConfig,
    private val client: OkHttpClient = DEFAULTS.defaultClient,
    private val gson: Gson = Gson(),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    interface DetectCallback {
        fun onOpened(handle: RequestHandle)
        fun onSuccess(handle: RequestHandle, hasHazard: Boolean, fullText: String, labels: List<String>)
        fun onFailure(handle: RequestHandle, message: String)
    }

    interface DetailCallback {
        fun onOpened(handle: RequestHandle)
        fun onSuccess(handle: RequestHandle, fullText: String)
        fun onFailure(handle: RequestHandle, message: String)
    }

    interface SuggestionChecksCallback {
        fun onSuccess(handle: RequestHandle, content: String)
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

    data class DeviceGuideResponse(
        val code: Int? = null,
        val msg: String? = null,
        val task_id: String? = null,
        val type: String? = null,
        val content: String? = null,
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

    /**
     * 根据当前企业信息构建路由上下文，统一决定各检测接口的调用策略。
     */
    private val routeContext: DetectionRouteContext
        get() = DetectionRouteContext(
            autoUrl = autoDetectConfig.url,
            generalUrl = generalDetectConfig.url,
            deepUrl = deepAnalysisConfig.url,
            gmUrl = gmAnalysisConfig.url,
            enterpriseInfo = com.rokid.glass.workflow.InspectionWorkflowSession.enterpriseInfo,
        )

    /**
     * 强制关闭当前 client 的所有空闲连接。
     * 应在 Activity 退出时调用，避免服务器端残留大量 ESTABLISHED 连接。
     */
    fun releaseConnections() {
        client.connectionPool.evictAll()
    }

    /**
     * 跳过检测请求（placeCode 缺失等场景），回调 hasHazard=false 让管线继续正常运行。
     */
    private fun skipHazardDetection(callback: DetectCallback): RequestHandle {
        val handle = RequestHandle(taskId = "skipped_${System.currentTimeMillis()}")
        mainHandler.post {
            if (!handle.isCanceled()) {
                callback.onSuccess(handle, hasHazard = false, fullText = "", labels = emptyList())
            }
        }
        return handle
    }

    fun identifyItemHazard(
        base64Image: String,
        callback: DetectCallback,
    ): RequestHandle {
        val url = routeContext.itemDetectionEndpoint() ?: return skipHazardDetection(callback)
        return requestHazardDetection(
            base64Image = base64Image,
            scene = routeContext.sceneParam(),
            url = url,
            lane = "auto",
            requireInferenceResults = true,
            callback = callback,
        )
    }

    fun identifySceneHazard(
        base64Image: String,
        callback: DetectCallback,
    ): RequestHandle {
        val url = routeContext.sceneDetectionEndpoint() ?: return skipHazardDetection(callback)
        return requestHazardDetection(
            base64Image = base64Image,
            scene = routeContext.sceneParam(),
            url = url,
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
                            .replace(WHITESPACE_COLLAPSE, " ").trim()
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
                            callback.onSuccess(handle, parsed.hasHazard, parsed.rawText, parsed.labels)
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
            scene = routeContext.sceneParam(),
            url = routeContext.deepAnalysisEndpoint(),
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
        val scene = com.rokid.glass.workflow.InspectionWorkflowSession.enterpriseInfo?.placeCode?.takeIf { it.isNotBlank() }
        return requestDeepAnalysis(
            base64Image = base64Image,
            scene = scene,
            url = generalDeepAnalysisConfig.url,
            lane = "general_deep",
            onChunk = onChunk,
            callback = callback,
        )
    }

    private fun requestDeepAnalysis(
        base64Image: String,
        scene: String?,
        url: String,
        lane: String,
        onChunk: (String) -> Unit,
        callback: DetailCallback,
    ): RequestHandle {
        val taskId = System.currentTimeMillis().toString()
        val handle = RequestHandle(taskId = taskId)
        val aggregator = AiArEventAggregator(gson)
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
        base64Image: String,
        onChunk: (String) -> Unit = {},
        callback: DetailCallback,
    ): RequestHandle {
        val taskId = System.currentTimeMillis().toString()
        val handle = RequestHandle(taskId = taskId)
        val scene = com.rokid.glass.workflow.InspectionWorkflowSession.enterpriseInfo?.placeCode?.takeIf { it.isNotBlank() }
        val payload = RequestPayload(task_id = taskId, image = base64Image, scene = scene)
        val requestStartedElapsedMs = SystemClock.elapsedRealtime()
        val jsonBuildStartedElapsedMs = requestStartedElapsedMs
        val jsonBodyString = gson.toJson(payload)
        val jsonBuildFinishedElapsedMs = SystemClock.elapsedRealtime()
        val requestBody = jsonBodyString.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(deviceGuideConfig.url)
            .tag(RequestTimingTag::class.java, RequestTimingTag(taskId, "device", requestStartedElapsedMs))
            .post(requestBody)
            .build()
        AppFileLogger.i(
            TAG,
            "deviceJson request taskId=$taskId endpoint=${deviceGuideConfig.url} imageChars=${payload.image?.length ?: 0} jsonBuildMs=${jsonBuildFinishedElapsedMs - jsonBuildStartedElapsedMs}",
        )
        val call = client.newCall(request)
        call.enqueue(object : okhttp3.Callback {
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val responseElapsedMs = SystemClock.elapsedRealtime()
                AppFileLogger.i(
                    TAG,
                    "deviceJson response taskId=$taskId httpCode=${response.code} elapsedMs=${responseElapsedMs - requestStartedElapsedMs}",
                )
                if (handle.isCanceled()) {
                    return
                }
                if (!response.isSuccessful) {
                    val bodySnippet = runCatching {
                        response.peekBody(MAX_ERROR_BODY_LOG_BYTES).string()
                            .replace(WHITESPACE_COLLAPSE, " ").trim()
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
                            callback.onFailure(handle, "设备指引返回空响应")
                        }
                    }
                    return
                }
                runCatching {
                    parseDeviceGuideBody(body, gson)
                }.onSuccess { fullText ->
                    AppFileLogger.i(
                        TAG,
                        "deviceJson success taskId=$taskId contentLength=${fullText.length} text=${summarizeSseLogText(fullText)}",
                    )
                    mainHandler.post {
                        if (handle.isCanceled()) {
                            return@post
                        }
                        callback.onOpened(handle)
                        playbackDeviceGuideContent(
                            handle = handle,
                            fullText = fullText,
                            onChunk = onChunk,
                            onComplete = { callback.onSuccess(handle, fullText) },
                        )
                    }
                }.onFailure { error ->
                    AppFileLogger.e(TAG, "deviceJson parse failed taskId=$taskId body=${body.take(256)}", error)
                    mainHandler.post {
                        if (!handle.isCanceled()) {
                            callback.onFailure(handle, error.message ?: "设备指引响应解析失败")
                        }
                    }
                }
            }

            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                AppFileLogger.e(
                    TAG,
                    "deviceJson network failed taskId=$taskId error=${e.javaClass.simpleName}:${e.message}",
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

    fun fetchSuggestionChecks(
        hazardCode: String,
        callback: SuggestionChecksCallback,
    ): RequestHandle {
        val taskId = System.currentTimeMillis().toString()
        val handle = RequestHandle(taskId = taskId)
        val requestStartedElapsedMs = SystemClock.elapsedRealtime()
        val jsonBodyString = SuggestionChecksProtocol.buildRequestBodyJson(
            gson = gson,
            taskId = taskId,
            hazardCode = hazardCode,
        )
        val requestBody = jsonBodyString.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(suggestionChecksConfig.url)
            .tag(RequestTimingTag::class.java, RequestTimingTag(taskId, "sug_checks", requestStartedElapsedMs))
            .post(requestBody)
            .build()
        AppFileLogger.i(
            TAG,
            "sug_checks request taskId=$taskId hazardCode=$hazardCode endpoint=${suggestionChecksConfig.url}",
        )
        val call = client.newCall(request)
        call.enqueue(object : okhttp3.Callback {
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val responseElapsedMs = SystemClock.elapsedRealtime()
                AppFileLogger.i(
                    TAG,
                    "sug_checks response taskId=$taskId httpCode=${response.code} elapsedMs=${responseElapsedMs - requestStartedElapsedMs}",
                )
                if (handle.isCanceled()) {
                    return
                }
                if (!response.isSuccessful) {
                    val bodySnippet = runCatching {
                        response.peekBody(MAX_ERROR_BODY_LOG_BYTES).string()
                            .replace(WHITESPACE_COLLAPSE, " ").trim()
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
                            callback.onFailure(handle, "sug_checks 返回空响应")
                        }
                    }
                    return
                }
                runCatching {
                    SuggestionChecksProtocol.parseContent(body, gson)
                }.onSuccess { content ->
                    AppFileLogger.i(TAG, "sug_checks success taskId=$taskId contentLength=${content.length}")
                    mainHandler.post {
                        if (!handle.isCanceled()) {
                            callback.onSuccess(handle, content)
                        }
                    }
                }.onFailure { error ->
                    AppFileLogger.e(TAG, "sug_checks parse failed taskId=$taskId body=${body.take(256)}", error)
                    mainHandler.post {
                        if (!handle.isCanceled()) {
                            callback.onFailure(handle, error.message ?: "sug_checks 响应解析失败")
                        }
                    }
                }
            }

            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                AppFileLogger.e(
                    TAG,
                    "sug_checks network failed taskId=$taskId error=${e.javaClass.simpleName}:${e.message}",
                    e,
                )
                mainHandler.post {
                    if (!handle.isCanceled()) {
                        callback.onFailure(handle, e.message ?: "sug_checks 网络请求失败")
                    }
                }
            }
        })
        handle.bind(call)
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
                    val deliveredMessage = if (response == null && t != null) {
                        "$NETWORK_FAILURE_PREFIX$message"
                    } else {
                        message
                    }
                    AppFileLogger.e(
                        TAG,
                        "openStream failed lane=$lane taskId=${payload.task_id} endpoint=$url requestUrl=${eventSource.request().url} throwable=${t?.javaClass?.simpleName} httpCode=$responseCode httpMessage=$responseMessage contentType=$responseContentType bodySnippet=$responseBodySnippet",
                        t,
                    )
                    deliverFailure(
                        handle = handle,
                        onFailure = onFailure,
                        message = deliveredMessage,
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
        return body.replace(WHITESPACE_COLLAPSE, " ").trim().take(MAX_ERROR_BODY_LOG_CHARS)
    }

    private fun playbackDeviceGuideContent(
        handle: RequestHandle,
        fullText: String,
        onChunk: (String) -> Unit,
        onComplete: () -> Unit,
    ) {
        if (fullText.isBlank()) {
            onComplete()
            return
        }
        val normalizedText = fullText.trim()
        val chunkSize = DEVICE_GUIDE_PLAYBACK_CHUNK_SIZE
        fun emit(nextIndex: Int) {
            if (handle.isCanceled()) {
                return
            }
            val endIndex = minOf(nextIndex + chunkSize, normalizedText.length)
            onChunk(normalizedText.substring(0, endIndex))
            if (endIndex >= normalizedText.length) {
                onComplete()
                return
            }
            mainHandler.postDelayed(
                { emit(endIndex) },
                DEVICE_GUIDE_PLAYBACK_INTERVAL_MS,
            )
        }
        emit(0)
    }

    companion object {
        const val NETWORK_FAILURE_PREFIX = "NETWORK_FAILURE:"
        private const val TAG = "AiArSseService"
        private const val DONE_SENTINEL = "[DONE]"
        private const val DONE_SENTINEL_JSON_ARRAY = "[\"DONE\"]"
        private const val DONE_EVENT_TYPE = "done"
        private const val MAX_ERROR_BODY_LOG_BYTES = 4096L
        private const val MAX_ERROR_BODY_LOG_CHARS = 512
        private const val MAX_SSE_BODY_LOG_CHARS = 4096
        private const val DEVICE_GUIDE_PLAYBACK_CHUNK_SIZE = 12
        private const val DEVICE_GUIDE_PLAYBACK_INTERVAL_MS = 35L
        private val WHITESPACE_COLLAPSE = Regex("\\s+")
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private object DEFAULTS {
            val cfg = InspectionConfigRepository.get()
            val autoDetectConfig = cfg.network.aiAutoApi
            val deepAnalysisConfig = cfg.network.aiDeepApi
            val gmAnalysisConfig = cfg.network.aiGmApi
            val generalDetectConfig = cfg.network.aiGeneralApi
            val generalDeepAnalysisConfig = cfg.network.aiGeneralDeepApi
            val deviceGuideConfig = cfg.network.aiDeviceApi
            val suggestionChecksConfig = cfg.network.aiSuggestionChecksApi
            val defaultClient = HttpClientProvider.sseClient
        }

        private fun summarizeSseLogText(text: String): String {
            val normalized = text.replace("\r", "\\r").replace("\n", "\\n")
            return if (normalized.length <= MAX_SSE_BODY_LOG_CHARS) {
                normalized
            } else {
                "${normalized.take(MAX_SSE_BODY_LOG_CHARS)}...(truncated ${normalized.length - MAX_SSE_BODY_LOG_CHARS} chars)"
            }
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

        internal fun parseDeviceGuideBody(
            body: String,
            gson: Gson = Gson(),
        ): String {
            val parsed = runCatching {
                gson.fromJson(body, DeviceGuideResponse::class.java)
            }.getOrElse { error ->
                throw IllegalStateException("设备指引返回不是合法 JSON", error)
            } ?: throw IllegalStateException("设备指引返回为空")
            if (parsed.code != 0) {
                throw IllegalStateException(parsed.msg?.trim().takeUnless { it.isNullOrBlank() } ?: "设备指引接口返回失败")
            }
            return parsed.content?.trim().takeUnless { it.isNullOrBlank() }
                ?: throw IllegalStateException("设备指引返回缺少 content")
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
            val labels = inferenceElement
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.mapNotNull { item ->
                    item.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?.get("label")
                        ?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
                        ?.asJsonPrimitive
                        ?.takeIf { it.isString }
                        ?.asString
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                }
                ?.distinct()
                .orEmpty()
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
                labels = labels,
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
        val labels: List<String>,
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
        private var dnsStartedElapsedMs = 0L
        private var connectStartedElapsedMs = 0L
        private var connectionAcquiredElapsedMs = 0L
        private var requestBodyStartedElapsedMs = 0L
        private var requestBodyEndedElapsedMs = 0L

        override fun callStart(call: Call) {
            logTiming("callStart")
        }

        override fun dnsStart(call: Call, domainName: String) {
            dnsStartedElapsedMs = SystemClock.elapsedRealtime()
            logTiming("dnsStart", "domain=$domainName")
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            val nowElapsedMs = SystemClock.elapsedRealtime()
            logTimingAt(
                event = "dnsEnd",
                nowElapsedMs = nowElapsedMs,
                extra = "domain=$domainName dnsMs=${durationOrMinusOne(dnsStartedElapsedMs, nowElapsedMs)} addressCount=${inetAddressList.size}",
            )
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connectStartedElapsedMs = SystemClock.elapsedRealtime()
            logTiming(
                event = "connectStart",
                extra = "address=${inetSocketAddress.hostString}:${inetSocketAddress.port} proxy=${proxy.type()}",
            )
        }

        override fun connectEnd(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
        ) {
            val nowElapsedMs = SystemClock.elapsedRealtime()
            logTimingAt(
                event = "connectEnd",
                nowElapsedMs = nowElapsedMs,
                extra = "address=${inetSocketAddress.hostString}:${inetSocketAddress.port} proxy=${proxy.type()} protocol=$protocol connectMs=${durationOrMinusOne(connectStartedElapsedMs, nowElapsedMs)}",
            )
        }

        override fun connectFailed(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
            ioe: java.io.IOException,
        ) {
            val nowElapsedMs = SystemClock.elapsedRealtime()
            Log.w(
                TAG,
                "openStream networkTiming event=connectFailed lane=${tag.lane} taskId=${tag.taskId} requestStartToEventMs=${nowElapsedMs - tag.requestStartedElapsedMs} address=${inetSocketAddress.hostString}:${inetSocketAddress.port} proxy=${proxy.type()} protocol=$protocol connectMs=${durationOrMinusOne(connectStartedElapsedMs, nowElapsedMs)} error=${ioe.javaClass.simpleName}:${ioe.message}",
            )
        }

        override fun connectionAcquired(call: Call, connection: Connection) {
            connectionAcquiredElapsedMs = SystemClock.elapsedRealtime()
            logTimingAt(
                event = "connectionAcquired",
                nowElapsedMs = connectionAcquiredElapsedMs,
                extra = "callStartToConnectionAcquiredMs=${connectionAcquiredElapsedMs - tag.requestStartedElapsedMs} route=${connection.route().socketAddress} protocol=${connection.protocol()}",
            )
        }

        override fun connectionReleased(call: Call, connection: Connection) {
            val nowElapsedMs = SystemClock.elapsedRealtime()
            logTimingAt(
                event = "connectionReleased",
                nowElapsedMs = nowElapsedMs,
                extra = "connectionHeldMs=${durationOrMinusOne(connectionAcquiredElapsedMs, nowElapsedMs)} route=${connection.route().socketAddress} protocol=${connection.protocol()}",
            )
        }

        override fun requestHeadersStart(call: Call) {
            logTiming("requestHeadersStart")
        }

        override fun requestHeadersEnd(call: Call, request: Request) {
            logTiming("requestHeadersEnd")
        }

        override fun requestBodyStart(call: Call) {
            requestBodyStartedElapsedMs = SystemClock.elapsedRealtime()
            logTimingAt(
                event = "requestBodyStart",
                nowElapsedMs = requestBodyStartedElapsedMs,
                extra = "connectionAcquiredToRequestBodyStartMs=${durationOrMinusOne(connectionAcquiredElapsedMs, requestBodyStartedElapsedMs)}",
            )
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

        override fun callEnd(call: Call) {
            logTiming("callEnd")
        }

        override fun callFailed(call: Call, ioe: java.io.IOException) {
            val nowElapsedMs = SystemClock.elapsedRealtime()
            Log.w(
                TAG,
                "openStream networkTiming event=callFailed lane=${tag.lane} taskId=${tag.taskId} requestStartToEventMs=${nowElapsedMs - tag.requestStartedElapsedMs} error=${ioe.javaClass.simpleName}:${ioe.message}",
            )
        }

        private fun logTiming(event: String, extra: String? = null) {
            val nowElapsedMs = SystemClock.elapsedRealtime()
            logTimingAt(event, nowElapsedMs, extra)
        }

        private fun logTimingAt(event: String, nowElapsedMs: Long, extra: String? = null) {
            val suffix = extra?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            Log.i(
                TAG,
                "openStream networkTiming event=$event lane=${tag.lane} taskId=${tag.taskId} requestStartToEventMs=${nowElapsedMs - tag.requestStartedElapsedMs}$suffix",
            )
        }
    }
}
