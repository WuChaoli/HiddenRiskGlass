package com.rokid.glass.hiddenrisk

import com.google.gson.Gson

/**
 * 聚合 SSE 的 content 字段。
 */
class AiArEventAggregator(
    private val gson: Gson = Gson(),
) {
    data class EventPayload(
        val task_id: String? = null,
        val content: String? = null,
    )

    private val contentBuilder = StringBuilder()
    private var taskId: String? = null

    fun append(data: String): String {
        val payload = runCatching {
            gson.fromJson(data, EventPayload::class.java)
        }.getOrElse { error ->
            throw IllegalStateException("在线识别事件不是合法 JSON", error)
        } ?: throw IllegalStateException("在线识别事件为空")
        val resolvedTaskId = payload.task_id?.trim().orEmpty()
        if (resolvedTaskId.isBlank()) {
            throw IllegalStateException("在线识别事件缺少 task_id")
        }
        val chunk = payload.content.orEmpty()
        if (taskId == null) {
            taskId = resolvedTaskId
        }
        if (taskId != resolvedTaskId) {
            throw IllegalStateException("在线识别 task_id 不一致")
        }
        contentBuilder.append(chunk)
        return chunk
    }

    fun taskId(): String = taskId.orEmpty()

    fun fullText(): String = contentBuilder.toString()
}
