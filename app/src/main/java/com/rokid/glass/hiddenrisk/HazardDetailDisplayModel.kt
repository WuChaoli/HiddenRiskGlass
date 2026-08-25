package com.rokid.glass.hiddenrisk

internal data class HazardDetailDisplayModel(
    val label: String,
    val hazardCode: String,
    val level: String,
    val description: String,
    val advice: String,
    val lawBasis: String,
) {
    companion object {
        fun from(hazard: DeepV2PresentationHazard): HazardDetailDisplayModel {
            return HazardDetailDisplayModel(
                label = hazard.label.ifBlank { "--" },
                hazardCode = hazard.hazardCode.ifBlank { "--" },
                level = hazard.level.ifBlank { "--" },
                description = hazard.description.ifBlank { "暂无" },
                advice = hazard.advice.ifBlank { "暂无" },
                lawBasis = hazard.lawBasis.ifBlank { "暂无" },
            )
        }

        fun shouldShowPageIndicator(pageCount: Int): Boolean = pageCount > 1
    }
}
