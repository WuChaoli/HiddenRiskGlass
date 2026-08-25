package com.rokid.glass.hiddenrisk

internal object DeepV2ResolvedHazardAdapter {
    fun adapt(
        presentation: DeepV2Presentation,
        image: DeepV2ImagePayload,
    ): ResolvedHazardContent {
        val items = presentation.uploadHazards.map(::toResolvedHazardItem)
        require(items.isNotEmpty()) { "deep v2 presentation has no savable hazard" }
        val primary = items.first()
        return ResolvedHazardContent(
            source = HazardSource.ONLINE,
            description = primary.description,
            advice = primary.advice,
            uploadAdvice = primary.uploadAdvice,
            hidLevel = primary.hidLevel,
            hidNum = primary.hidNum,
            lawBasis = primary.lawBasis,
            displayTitle = primary.displayTitle,
            jpegBytes = image.jpegBytes.copyOf(),
            hazards = items,
            remoteSaveAllowed = true,
        )
    }

    private fun toResolvedHazardItem(hazard: DeepV2PresentationHazard): ResolvedHazardItem {
        val level = ResolvedHazardContent.levelCode(hazard.level).ifBlank { hazard.level }
        return ResolvedHazardItem(
            displayTitle = hazard.label,
            description = hazard.description,
            advice = hazard.advice,
            uploadAdvice = hazard.advice,
            hidLevel = level,
            hidNum = hazard.hazardCode,
            lawBasis = hazard.lawBasis,
        )
    }
}
