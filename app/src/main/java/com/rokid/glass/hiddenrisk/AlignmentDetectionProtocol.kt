package com.rokid.glass.hiddenrisk

import com.google.gson.Gson
import com.google.gson.JsonParser

internal data class AlignmentDetection(
    val label: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal data class AlignmentDetectionResponse(
    val detections: List<AlignmentDetection>,
)

internal data class AlignmentInferenceImageSize(
    val width: Int,
    val height: Int,
) {
    companion object {
        fun fromWidth(width: Int): AlignmentInferenceImageSize {
            require(width > 0 && width % 3 == 0) { "3:4 image width must be a positive multiple of 3" }
            return AlignmentInferenceImageSize(width = width, height = width * 4 / 3)
        }
    }
}

internal object AlignmentDetectionProtocol {
    private val gson = Gson()

    fun buildRequestJson(base64Image: String): String {
        return gson.toJson(
            RequestPayload(
                task_id = TASK_ID,
                stream = true,
                image = base64Image,
                text = "",
                scene = SCENE,
            ),
        )
    }

    fun parseResponse(body: String): AlignmentDetectionResponse {
        val root = runCatching { JsonParser.parseString(body).asJsonObject }
            .getOrElse { throw IllegalStateException("自动隐患接口返回不是合法 JSON", it) }
        val code = root.get("code")?.takeIf { it.isJsonPrimitive }?.asInt
        if (code != 0) {
            val message = root.get("msg")?.takeIf { it.isJsonPrimitive }?.asString
            throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "自动隐患接口返回失败 code=$code")
        }
        val detections = root.getAsJsonArray("inference_result")
            ?.mapNotNull { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val label = item.get("label")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val bbox = item.getAsJsonArray("bbox")?.takeIf { it.size() == BBOX_VALUE_COUNT }
                    ?: return@mapNotNull null
                val values = runCatching { bbox.map { it.asFloat } }.getOrNull()
                    ?.takeIf { coordinates -> coordinates.all(Float::isFinite) }
                    ?: return@mapNotNull null
                if (values[2] <= values[0] || values[3] <= values[1]) {
                    return@mapNotNull null
                }
                val score = item.get("score")
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asFloat
                    ?.takeIf(Float::isFinite)
                    ?: 0f
                AlignmentDetection(
                    label = label,
                    score = score,
                    left = values[0],
                    top = values[1],
                    right = values[2],
                    bottom = values[3],
                )
            }
            .orEmpty()
        return AlignmentDetectionResponse(detections)
    }

    private const val BBOX_VALUE_COUNT = 4
    const val TASK_ID = "task_001"
    const val SCENE = "XFAQ-JXCS-001"

    private data class RequestPayload(
        val task_id: String,
        val stream: Boolean,
        val image: String,
        val text: String,
        val scene: String,
    )
}

internal object AlignmentDetectionMapper {
    fun mapToScreen(
        detection: AlignmentDetection,
        imageWidth: Int,
        imageHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): AlignmentDetection {
        require(imageWidth > 0 && imageHeight > 0)
        require(screenWidth > 0 && screenHeight > 0)
        val scaleX = screenWidth.toFloat() / imageWidth
        val scaleY = screenHeight.toFloat() / imageHeight
        return detection.copy(
            left = (detection.left * scaleX).coerceIn(0f, screenWidth.toFloat()),
            top = (detection.top * scaleY).coerceIn(0f, screenHeight.toFloat()),
            right = (detection.right * scaleX).coerceIn(0f, screenWidth.toFloat()),
            bottom = (detection.bottom * scaleY).coerceIn(0f, screenHeight.toFloat()),
        )
    }
}

internal object AlignmentDetectionCadence {
    fun nextDelayMs(nowMs: Long, lastStartedMs: Long, requestInFlight: Boolean): Long? {
        if (requestInFlight) return null
        return (REQUEST_INTERVAL_MS - (nowMs - lastStartedMs)).coerceAtLeast(0L)
    }

    const val REQUEST_INTERVAL_MS = 500L
    const val REQUEST_TIMEOUT_MS = 1_000L
}
