package com.rokid.glass.utils

import android.util.Log
import com.rokid.glass.data.YXData
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import com.rokid.glass.network.HttpClientProvider

class SSEUtil {

    companion object {
        private const val TAG = "SSE"
        private const val SMART_GLASSES_URL = "http://183.147.142.133:7443/hxy/apis/third/smartGlasses"
        private val gson = com.google.gson.Gson()
    }

    private val client = HttpClientProvider.sseClient

    private val eventSourceFactory = EventSources.createFactory(client)

// ... existing code ...
    /**
     * 连接 SSE 接口
     * @param imageUrl 图片 Base64 字符串
     * @param snCode 设备序列号
     * @param sessionId 对话 ID（可选）
     * @param timestamp 时间戳（可选）
     * @param authorization Token（可选）
     */
    fun connect(
        imageUrl: String,
        snCode: String,
        sessionId: String? = null,
        timestamp: String? = null,
        authorization: String? = null,
        listener: SSEListener
    ) {
        val dto = buildImageDetectionDto(imageUrl, snCode, sessionId, timestamp)
        val jsonBody = gson.toJson(dto).toRequestBody("application/json".toMediaType())
        val payloadBytes = jsonBody.contentLength()

        val request = Request.Builder()
            .url(SMART_GLASSES_URL)
            .post(jsonBody)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .header("Connection", "keep-alive")
            .build()
        Log.i(
            TAG,
            "connect url=$SMART_GLASSES_URL snCode=$snCode sessionId=$sessionId timestamp=$timestamp payloadBytes=$payloadBytes"
        )
        var str = ""
        val eventSource = eventSourceFactory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                super.onOpen(eventSource, response)
                Log.i(
                    TAG,
                    "opened code=${response.code} contentType=${response.header("Content-Type")} requestUrl=${response.request.url}"
                )
                listener.onOpened()
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                super.onEvent(eventSource, id, type, data)
                Log.i(TAG, "event id=$id type=$type dataLength=${data.length}")

                val yxData = gson.fromJson(data, YXData::class.java)
                if (yxData.end == 1) {
                    str = yxData.answer
                    listener.onMessage(str)
                    listener.onClosed()
                }else{
                    str+= yxData.answer
                    listener.onMessage(str)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                super.onClosed(eventSource)
                Log.i(TAG, "closed requestUrl=${eventSource.request().url}")
                listener.onClosed()
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                super.onFailure(eventSource, t, response)
                val errorMsg = "SSE 连接失败 - ${t?.message ?: response?.message}"
                Log.e(
                    TAG,
                    buildString {
                        append(errorMsg)
                        append(" requestUrl=${eventSource.request().url}")
                        append(" throwable=${t?.javaClass?.name}")
                        append(" responseCode=${response?.code}")
                        append(" responseMessage=${response?.message}")
                        append(" responseContentType=${response?.header("Content-Type")}")
                    },
                    t
                )

                // 检查是否是 Content-Type 问题
                if (t?.message?.contains("Invalid content-type") == true) {
                    Log.e(TAG, "服务器可能不支持 SSE 格式，返回了错误的 Content-Type，尝试回退到普通 HTTP")
                    // 尝试使用普通 HTTP 请求替代
                    makeHttpRequestInstead(imageUrl, snCode, sessionId, timestamp, authorization, listener)
                } else {
                    listener.onFailure(t, response)
                }
            }
        })

        // 保存 eventSource 引用以便后续关闭
        listener.onEventSourceCreated(eventSource)
    }

    /**
     * 当 SSE 不工作时，使用普通的 HTTP 请求
     */
    private fun makeHttpRequestInstead(
        imageUrl: String,
        snCode: String,
        sessionId: String?,
        timestamp: String?,
        authorization: String?,
        listener: SSEListener
    ) {
        val client = HttpClientProvider.sseClient

        val dto = buildImageDetectionDto(imageUrl, snCode, sessionId, timestamp)
        val jsonBody = gson.toJson(dto).toRequestBody("application/json".toMediaType())
        val payloadBytes = jsonBody.contentLength()

        val request = Request.Builder()
            .url(SMART_GLASSES_URL)
            .post(jsonBody)
            .addHeader("Accept", "application/json")
            .apply {
                if (!authorization.isNullOrBlank()) {
                    addHeader("Authorization", authorization)
                }
            }
            .build()
        Log.i(
            TAG,
            "fallback_http url=$SMART_GLASSES_URL snCode=$snCode sessionId=$sessionId timestamp=$timestamp payloadBytes=$payloadBytes"
        )

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "fallback_http_failed url=${call.request().url} throwable=${e.javaClass.name} message=${e.message}", e)
                listener.onFailure(e, null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        Log.i(
                            TAG,
                            "fallback_http_success code=${response.code} contentType=${response.header("Content-Type")} bodyLength=${responseBody?.length ?: 0}"
                        )

                        // 模拟 SSE 事件，将响应作为单个事件发送
                        listener.onMessage(responseBody ?: "")
                        listener.onClosed()
                    } else {
                        Log.e(
                            TAG,
                            "fallback_http_response_failed code=${response.code} message=${response.message} contentType=${response.header("Content-Type")} requestUrl=${call.request().url}"
                        )
                        listener.onFailure(null, response)
                    }
                }
            }
        })
    }
// ... existing code ...


    /**
     * 构建 ImageDetectionDto
     */
    private fun buildImageDetectionDto(
        imageUrl: String,
        snCode: String,
        sessionId: String?,
        timestamp: String?
    ): Map<String, Any?> {
        return mapOf(
            "image" to imageUrl,
            "snCode" to snCode,
            "sessionId" to sessionId,
            "timestamp" to timestamp,
            "hiddenRisk" to emptyList<String>(),
            "labels" to emptyMap<String, List<Any>>()
        )
    }

    /**
     * SSE 回调接口
     */
    interface SSEListener {
        fun onOpened()
        fun onMessage(data: String)
        fun onClosed()
        fun onFailure(t: Throwable?, response: Response?)
        fun onEventSourceCreated(eventSource: EventSource)
    }
}
