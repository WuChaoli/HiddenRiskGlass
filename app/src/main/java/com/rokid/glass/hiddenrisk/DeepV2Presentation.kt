package com.rokid.glass.hiddenrisk

internal data class DeepV2ImagePayload(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
)

internal data class DeepV2BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val area: Float
        get() = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
}

internal data class DeepV2PresentationHazard(
    val labelId: String,
    val label: String,
    val description: String,
    val level: String,
    val lawBasis: String,
    val advice: String,
    val hazardCode: String,
    val sourceIndex: Int,
)

internal data class DeepV2Target(
    val labelId: String,
    val label: String,
    val bbox: DeepV2BoundingBox,
    val detectionScore: Double,
    val detectionIndex: Int,
    val highestLevel: String,
    val hazards: List<DeepV2PresentationHazard>,
)

internal data class DeepV2GlobalHazards(
    val labelId: String = "others",
    val highestLevel: String,
    val hazards: List<DeepV2PresentationHazard>,
)

internal data class DeepV2Presentation(
    val targets: List<DeepV2Target>,
    val others: DeepV2GlobalHazards?,
    val uploadHazards: List<DeepV2PresentationHazard>,
    val suggestionHazardCode: String?,
) {
    val hasDisplayableHazards: Boolean
        get() = targets.isNotEmpty() || others != null
}
