package com.rokid.glass.hiddenrisk

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
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
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
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

    fun detectHasHazard(
        base64Image: String,
        callback: DetectCallback,
    ): RequestHandle {
        val taskId = System.currentTimeMillis().toString()
        val handle = RequestHandle(taskId = taskId, ctype = CTYPE_HAS_HAZARD)
        val aggregator = AiArEventAggregator(gson)
        openStream(
            handle = handle,
            payload = RequestPayload(task_id = taskId, ctype = CTYPE_HAS_HAZARD, image = base64Image),
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

    fun fetchHazardDetails(
        base64Image: String,
        onChunk: (String) -> Unit = {},
        callback: DetailCallback,
    ): RequestHandle {
        val taskId = System.currentTimeMillis().toString()
        val handle = RequestHandle(taskId = taskId, ctype = CTYPE_DETAIL)
        val aggregator = AiArEventAggregator(gson)
        openStream(
            handle = handle,
            payload = RequestPayload(task_id = taskId, ctype = CTYPE_DETAIL, image = base64Image),
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

    fun fetchHazardAdvice(
        text: String,
        onChunk: (String) -> Unit = {},
        callback: DetailCallback,
    ): RequestHandle {
        val taskId = System.currentTimeMillis().toString()
        val handle = RequestHandle(taskId = taskId, ctype = CTYPE_ADVICE)
        val aggregator = AiArEventAggregator(gson)
        openStream(
            handle = handle,
            payload = RequestPayload(task_id = taskId, ctype = CTYPE_ADVICE, text = text),
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
        val requestBody = gson.toJson(payload).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(AI_AR_URL)
            .header("Accept", "text/event-stream")
            .post(requestBody)
            .build()
        Log.i(TAG, "openStream ctype=${payload.ctype} taskId=${payload.task_id} endpoint=$AI_AR_URL")
        val eventSource = eventSourceFactory.newEventSource(
            request,
            object : EventSourceListener() {
                private val terminalDelivered = AtomicBoolean(false)

                override fun onOpen(eventSource: EventSource, response: Response) {
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
                    if (normalizedData.isEmpty()) {
                        return
                    }
                    if (normalizedData == DONE_SENTINEL) {
                        Log.i(TAG, "openStream received done sentinel ctype=${payload.ctype} taskId=${payload.task_id}")
                        return
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
                    val message = t?.message
                        ?: response?.message
                        ?: "在线识别失败"
                    Log.e(TAG, "openStream failed ctype=${payload.ctype} taskId=${payload.task_id}", t)
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

    companion object {
        private const val TAG = "AiArSseService"
        private const val AI_AR_URL = "http://183.147.142.133:50016/ai/ar"
        private const val DONE_SENTINEL = "[DONE]"
        private const val CTYPE_DETAIL = 0
        private const val CTYPE_HAS_HAZARD = 1
        private const val CTYPE_ADVICE = 2
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
