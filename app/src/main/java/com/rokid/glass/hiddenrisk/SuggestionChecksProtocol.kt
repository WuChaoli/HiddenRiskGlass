package com.rokid.glass.hiddenrisk

import com.google.gson.Gson
import com.google.gson.JsonObject

internal object SuggestionChecksProtocol {
    data class RequestPayload(
        val task_id: String,
        val hazard_code: String,
    )

    fun buildRequestBodyJson(
        gson: Gson,
        taskId: String,
        hazardCode: String,
    ): String {
        return gson.toJson(RequestPayload(task_id = taskId, hazard_code = hazardCode))
    }

    fun parseContent(
        body: String,
        gson: Gson = Gson(),
    ): String {
        val parsed = runCatching {
            gson.fromJson(body, JsonObject::class.java)
        }.getOrElse { error ->
            throw IllegalStateException("sug_checks 响应不是合法 JSON", error)
        } ?: throw IllegalStateException("sug_checks 响应为空")
        val content = parsed.get("content")
            ?.takeIf { !it.isJsonNull }
            ?.asString
            ?.trim()
            .orEmpty()
        return content
    }
}
