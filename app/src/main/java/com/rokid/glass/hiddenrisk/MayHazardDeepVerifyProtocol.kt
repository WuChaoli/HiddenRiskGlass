package com.rokid.glass.hiddenrisk

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.rokid.glass.config.InspectionConfigRepository

/**
 * MayHazard 深度识别协议定义与解析工具。
 */
object MayHazardDeepVerifyProtocol {
    val ANSWER_URL: String
        get() = InspectionConfigRepository.get().network.mayHazardVerifyApi.answerUrl

    private val gson = Gson()

    data class AnswerRequest(
        val image_url: String,
    )

    data class AnswerResponse(
        val code: Int? = null,
        val msg: String? = null,
        val data: AnswerData? = null,
    )

    data class AnswerData(
        val answer: String? = null,
    )

    fun parseHasHazardAnswer(body: String): Boolean {
        val response = gson.fromJson(body, AnswerResponse::class.java)
            ?: throw IllegalStateException("has_hazard_answer 响应为空")
        if (response.code != 200) {
            throw IllegalStateException("has_hazard_answer code=${response.code}")
        }
        val answer = response.data?.answer?.trim().orEmpty()
        if (answer.isEmpty()) {
            throw IllegalStateException("has_hazard_answer 缺少 answer")
        }

        val root = try {
            JsonParser.parseString(answer)
        } catch (error: JsonParseException) {
            throw IllegalStateException("has_hazard_answer answer 不是合法 JSON", error)
        }
        val jsonObject = root.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalStateException("has_hazard_answer answer 不是 JSON 对象")
        if (jsonObject.size() != 1 || !jsonObject.has("has_hazard")) {
            throw IllegalStateException("has_hazard_answer answer 缺少 has_hazard")
        }
        val field = jsonObject.get("has_hazard")
        if (!field.isJsonPrimitive || !field.asJsonPrimitive.isBoolean) {
            throw IllegalStateException("has_hazard_answer has_hazard 不是布尔值")
        }
        return field.asBoolean
    }
}
