package com.rokid.glass.hiddenrisk

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal data class DeepV2Request(
    val taskId: String,
    val scene: String?,
    val temp: Double,
    val image: String,
)

internal sealed interface DeepV2Inter {
    data class BooleanValue(val value: Boolean) : DeepV2Inter
    data class NumberValue(val value: Double) : DeepV2Inter
}

internal data class DeepV2Detection(
    val label: String,
    val bbox: List<Double>,
    val score: Double,
    val inter: DeepV2Inter?,
    val labelId: String,
    val sourceIndex: Int,
)

internal data class DeepV2Hazard(
    val labelId: String,
    val description: String,
    val level: String,
    val lawBasis: String,
    val advice: String,
    val hazardCode: String,
    val sourceIndex: Int,
)

internal data class DeepV2Response(
    val code: Int,
    val message: String,
    val taskId: String,
    val type: String,
    val detections: List<DeepV2Detection>,
    val hazards: List<DeepV2Hazard>,
    val checkItems: List<JsonElement>,
    val timeSeconds: Double?,
)

internal class DeepV2ProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

internal object DeepV2Protocol {
    private val gson = Gson()

    fun buildRequestJson(request: DeepV2Request): String {
        val json = JsonObject().apply {
            addProperty("task_id", request.taskId)
            request.scene?.trim()?.takeIf(String::isNotEmpty)?.let { scene ->
                addProperty("scene", scene)
            }
            addProperty("temp", request.temp)
            addProperty("image", request.image)
        }
        return gson.toJson(json)
    }

    fun parseResponse(body: String, expectedType: String = "deep_v2"): DeepV2Response {
        try {
            val root = JsonParser.parseString(body)
            if (!root.isJsonObject) {
                throw DeepV2ProtocolException("deep v2 response must be a JSON object")
            }
            val json = root.asJsonObject
            val code = json.requiredInt("code")
            if (code != 0) {
                throw DeepV2ProtocolException(
                    "deep v2 returned code=$code msg=${json.stringOrEmpty("msg")}",
                )
            }
            val type = json.requiredString("type")
            if (type != expectedType) {
                throw DeepV2ProtocolException("unexpected response type=$type expected=$expectedType")
            }
            val detectionsJson = json.get("detections")
            val hazardsJson = json.get("hazards")
            val checkItemsJson = json.get("check_items")
            if (detectionsJson == null || !detectionsJson.isJsonArray) {
                throw DeepV2ProtocolException("detections must be an array")
            }
            if (hazardsJson == null || !hazardsJson.isJsonArray) {
                throw DeepV2ProtocolException("hazards must be an array")
            }
            if (checkItemsJson == null || !checkItemsJson.isJsonArray) {
                throw DeepV2ProtocolException("check_items must be an array")
            }

            return DeepV2Response(
                code = code,
                message = json.stringOrEmpty("msg"),
                taskId = json.requiredString("task_id"),
                type = type,
                detections = detectionsJson.asJsonArray.mapIndexedNotNull(::parseDetection),
                hazards = hazardsJson.asJsonArray.mapIndexedNotNull(::parseHazard),
                checkItems = checkItemsJson.asJsonArray.toList(),
                timeSeconds = json.finiteDoubleOrNull("time"),
            )
        } catch (error: DeepV2ProtocolException) {
            throw error
        } catch (error: RuntimeException) {
            throw DeepV2ProtocolException("invalid deep v2 response", error)
        }
    }

    private fun parseDetection(index: Int, element: JsonElement): DeepV2Detection? {
        if (!element.isJsonObject) return null
        val json = element.asJsonObject
        val labelId = json.stringOrNull("label_id")?.trim().orEmpty()
        if (labelId.isBlank()) return null
        val bboxElement = json.get("bbox") ?: return null
        if (!bboxElement.isJsonArray || bboxElement.asJsonArray.size() != 4) return null
        val bbox = bboxElement.asJsonArray.map { coordinate ->
            coordinate.takeIf(JsonElement::isJsonPrimitive)?.asDouble
        }
        if (bbox.any { it == null || !it.isFinite() }) return null
        val coordinates = bbox.filterNotNull()
        if (coordinates[2] <= coordinates[0] || coordinates[3] <= coordinates[1]) return null
        val score = json.finiteDoubleOrNull("score") ?: return null
        return DeepV2Detection(
            label = json.stringOrNull("label")?.trim().orEmpty().ifBlank { labelId },
            bbox = coordinates,
            score = score,
            inter = parseInter(json.get("inter")),
            labelId = labelId,
            sourceIndex = index,
        )
    }

    private fun parseHazard(index: Int, element: JsonElement): DeepV2Hazard? {
        if (!element.isJsonObject) return null
        val json = element.asJsonObject
        val labelId = json.stringOrNull("label_id")?.trim().orEmpty()
        if (labelId.isBlank()) return null
        return DeepV2Hazard(
            labelId = labelId,
            description = json.stringOrEmpty("隐患描述").trim(),
            level = json.stringOrEmpty("隐患等级").trim(),
            lawBasis = json.stringOrEmpty("主要依据").trim(),
            advice = json.stringOrEmpty("整改建议").trim(),
            hazardCode = json.stringOrEmpty("隐患编号").trim(),
            sourceIndex = index,
        )
    }

    private fun parseInter(element: JsonElement?): DeepV2Inter? {
        if (element == null || !element.isJsonPrimitive) return null
        val primitive = element.asJsonPrimitive
        return when {
            primitive.isBoolean -> DeepV2Inter.BooleanValue(primitive.asBoolean)
            primitive.isNumber -> primitive.asDouble
                .takeIf(Double::isFinite)
                ?.let(DeepV2Inter::NumberValue)
            else -> null
        }
    }

    private fun JsonObject.requiredString(name: String): String {
        return stringOrNull(name)?.trim()?.takeIf(String::isNotEmpty)
            ?: throw DeepV2ProtocolException("missing or blank $name")
    }

    private fun JsonObject.requiredInt(name: String): Int {
        val element = get(name)
        if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            throw DeepV2ProtocolException("missing or invalid $name")
        }
        return element.asInt
    }

    private fun JsonObject.stringOrNull(name: String): String? {
        val element = get(name)
        return if (element != null && element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            element.asString
        } else {
            null
        }
    }

    private fun JsonObject.stringOrEmpty(name: String): String = stringOrNull(name).orEmpty()

    private fun JsonObject.finiteDoubleOrNull(name: String): Double? {
        val element = get(name)
        if (element == null || !element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
        return element.asDouble.takeIf(Double::isFinite)
    }
}
