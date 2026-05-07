package com.rokid.glass.hiddenrisk

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.rokid.glass.config.AiArApiConfig
import com.rokid.glass.config.InspectionConfigRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * /ai/ar 专用 SSE 服务。
 */
class AiArSseService(
    private val apiConfig: AiArApiConfig = InspectionConfigRepository.get().network.aiArApi,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(apiConfig.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(apiConfig.readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(apiConfig.writeTimeoutMs, TimeUnit.MILLISECONDS)
        .build(),
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
        val ctype: Int,
        val image: String? = null,
        val text: String? = null,
    )

    class RequestHandle(
        val taskId: String,
        val ctype: Int,
    ) {
        @Volatile
        private var canceled = false
        @Volatile
        private var eventSource: EventSource? = null

        fun bind(source: EventSource) {
            if (canceled) {
                source.cancel()
                return
            }
            eventSource = source
        }

        fun cancel() {
            canceled = true
            eventSource?.cancel()
        }

        fun isCanceled(): Boolean = canceled
    }

    private val eventSourceFactory = EventSources.createFactory(client)

    fun identifyItemHazard(
        base64Image: String,
        callback: DetectCallback,
    ): RequestHandle {
        return requestHazardDetection(
            base64Image = base64Image,
            detectCtype = CTYPE_IDENTIFY_ITEM_HAZARD,
            callback = callback,
        )
    }

    fun identifySceneHazard(
        base64Image: String,
        callback: DetectCallback,
    ): RequestHandle {
        return requestHazardDetection(
            base64Image = base64Image,
            detectCtype = CTYPE_IDENTIFY_SCENE_HAZARD,
            callback = callback,
        )
    }

    private fun requestHazardDetection(
        base64Image: String,
        detectCtype: Int,
        callback: DetectCallback,
    ): RequestHandle {
        val taskId = System.currentTimeMillis().toString()
        val handle = RequestHandle(taskId = taskId, ctype = detectCtype)
        val aggregator = AiArEventAggregator(gson)
        openStream(
            handle = handle,
            payload = RequestPayload(task_id = taskId, ctype = detectCtype, image = base64Image),
            onOpened = { callback.onOpened(handle) },
            onClosed = { fullText ->
                val hasHazard = parseHasHazard(fullText)
                Log.i(
                    TAG,
                    "detect closed taskId=$taskId hasHazard=$hasHazard fullText=${fullText.trim()}",
                )
                callback.onSuccess(handle, hasHazard, fullText)
            },
            onFailure = { message ->
                callback.onFailure(handle, message)
            },
            aggregator = aggregator,
        )
        return handle
    }

    fun requestDeepAnalysis(
        base64Image: String,
        onChunk: (String) -> Unit = {},
        callback: DetailCallback,
    ): RequestHandle {
        val taskId = System.currentTimeMillis().toString()
        val handle = RequestHandle(taskId = taskId, ctype = CTYPE_DEEP_ANALYSIS)
        val aggregator = AiArEventAggregator(gson)
        openStream(
            handle = handle,
            payload = RequestPayload(task_id = taskId, ctype = CTYPE_DEEP_ANALYSIS, image = base64Image),
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
        val handle = RequestHandle(taskId = taskId, ctype = CTYPE_FETCH_INSPECTION_GUIDE)
        val aggregator = AiArEventAggregator(gson)
        openStream(
            handle = handle,
            payload = RequestPayload(task_id = taskId, ctype = CTYPE_FETCH_INSPECTION_GUIDE, text = text),
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
        onOpened: () -> Unit,
        onChunk: (String) -> Unit = {},
        onClosed: (String) -> Unit,
        onFailure: (String) -> Unit,
        aggregator: AiArEventAggregator,
    ) {
        val requestStartedElapsedMs = SystemClock.elapsedRealtime()
        val requestBody = gson.toJson(payload).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(apiConfig.url)
            .header("Accept", "text/event-stream")
            .post(requestBody)
            .build()
        Log.i(
            TAG,
            "openStream requestStart ctype=${payload.ctype} taskId=${payload.task_id} endpoint=${apiConfig.url} imageChars=${payload.image?.length ?: 0} textLength=${payload.text?.length ?: 0}",
        )
        val eventSource = eventSourceFactory.newEventSource(
            request,
            object : EventSourceListener() {
                private val terminalDelivered = AtomicBoolean(false)
                private var firstEventElapsedMs = 0L

                override fun onOpen(eventSource: EventSource, response: Response) {
                    Log.i(
                        TAG,
                        "openStream opened ctype=${payload.ctype} taskId=${payload.task_id} endpoint=${apiConfig.url} requestUrl=${response.request.url} httpCode=${response.code} httpMessage=${response.message} contentType=${response.header("Content-Type")}",
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
                    if (payload.ctype == CTYPE_IDENTIFY_ITEM_HAZARD ||
                        payload.ctype == CTYPE_IDENTIFY_SCENE_HAZARD
                    ) {
                        Log.i(
                            TAG,
                            "openStream raw event ctype=${payload.ctype} taskId=${payload.task_id} id=${id ?: "(none)"} type=${type ?: "(none)"} data=$normalizedData",
                        )
                    }
                    if (isDoneEvent(type, normalizedData)) {
                        Log.i(TAG, "openStream received done sentinel ctype=${payload.ctype} taskId=${payload.task_id}")
                        return
                    }
                    if (normalizedData.isEmpty()) {
                        return
                    }
                    if (firstEventElapsedMs == 0L) {
                        firstEventElapsedMs = SystemClock.elapsedRealtime()
                        Log.i(
                            TAG,
                            "openStream firstEvent ctype=${payload.ctype} taskId=${payload.task_id} uploadToFirstEventMs=${firstEventElapsedMs - requestStartedElapsedMs} id=${id ?: "(none)"} type=${type ?: "(none)"} dataChars=${normalizedData.length}",
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
                        Log.e(TAG, "openStream parse event failed ctype=${payload.ctype} taskId=${payload.task_id} data=$normalizedData", error)
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
                    Log.i(
                        TAG,
                        "openStream closed ctype=${payload.ctype} taskId=${payload.task_id} uploadToClosedMs=${closedElapsedMs - requestStartedElapsedMs} firstEventToClosedMs=${durationOrMinusOne(firstEventElapsedMs, closedElapsedMs)} fullTextLength=${fullText.length}",
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
                    Log.e(
                        TAG,
                        "openStream failed ctype=${payload.ctype} taskId=${payload.task_id} endpoint=${apiConfig.url} requestUrl=${eventSource.request().url} throwable=${t?.javaClass?.simpleName} httpCode=$responseCode httpMessage=$responseMessage contentType=$responseContentType bodySnippet=$responseBodySnippet",
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

    private fun parseHasHazard(fullText: String): Boolean {
        val normalized = fullText.replace(Regex("\\s+"), "")
        return when {
            normalized == "是" || normalized.startsWith("是") -> true
            normalized == "否" || normalized.startsWith("否") -> false
            else -> throw IllegalStateException("在线识别返回非法判定：$fullText")
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
        private const val CTYPE_DEEP_ANALYSIS = 0
        private const val CTYPE_IDENTIFY_ITEM_HAZARD = 1
        private const val CTYPE_IDENTIFY_SCENE_HAZARD = 2
        private const val CTYPE_FETCH_INSPECTION_GUIDE = 3
        private const val MAX_ERROR_BODY_LOG_BYTES = 4096L
        private const val MAX_ERROR_BODY_LOG_CHARS = 512
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

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
    }
}
