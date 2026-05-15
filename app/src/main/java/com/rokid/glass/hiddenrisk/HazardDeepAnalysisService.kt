package com.rokid.glass.hiddenrisk

import okhttp3.Response
import okhttp3.sse.EventSource
import com.rokid.glass.utils.SSEUtil

/**
 * 深度分析流式接口服务。
 * 负责提交图片并将 SSE 事件转成 UI 可消费的回调。
 */
class HazardDeepAnalysisService(
    private val sseUtil: SSEUtil = SSEUtil(),
) {

    interface StreamCallback {
        fun onOpened(sessionId: String)
        fun onChunk(sessionId: String, text: String)
        fun onComplete(sessionId: String, fullText: String)
        fun onError(sessionId: String, message: String)
        fun onEventSourceCreated(sessionId: String, eventSource: EventSource)
    }

    fun analyzeBase64(
        base64Image: String,
        snCode: String,
        sessionId: String = InspectionBackendSessionId.create(snCode, prefix = "analysis"),
        callback: StreamCallback,
    ): String {
        sseUtil.connect(
            imageUrl = base64Image,
            snCode = snCode,
            sessionId = sessionId,
            listener = object : SSEUtil.SSEListener {
                override fun onOpened() {
                    callback.onOpened(sessionId)
                }

                override fun onMessage(data: String) {
                    callback.onChunk(sessionId, data)
                }

                override fun onClosed() {
                    callback.onComplete(sessionId, "")
                }

                override fun onFailure(t: Throwable?, response: Response?) {
                    callback.onError(sessionId, t?.message ?: response?.message ?: "深度分析失败")
                }

                override fun onEventSourceCreated(eventSource: EventSource) {
                    callback.onEventSourceCreated(sessionId, eventSource)
                }
            },
        )
        return sessionId
    }
}
