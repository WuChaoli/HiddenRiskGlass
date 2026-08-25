package com.rokid.glass.hiddenrisk

import com.rokid.glass.config.BusinessMockConfig

internal data class StructuredHazardSavePolicy(
    val upload: Boolean,
    val requestSuggestionChecks: Boolean,
)

internal enum class StructuredHazardSource(
    val savePolicy: StructuredHazardSavePolicy,
) {
    AUTO_ITEM(StructuredHazardSavePolicy(upload = true, requestSuggestionChecks = true)),
    MANUAL(StructuredHazardSavePolicy(upload = true, requestSuggestionChecks = true)),
    SCENE(StructuredHazardSavePolicy(upload = true, requestSuggestionChecks = true)),
    HAZARD_RECORD(StructuredHazardSavePolicy(upload = true, requestSuggestionChecks = false)),
}

internal object StructuredHazardUploadPolicy {
    fun canUpload(source: StructuredHazardSource, businessMock: BusinessMockConfig): Boolean = when (source) {
        StructuredHazardSource.AUTO_ITEM,
        StructuredHazardSource.MANUAL,
        StructuredHazardSource.SCENE,
        StructuredHazardSource.HAZARD_RECORD,
        -> !businessMock.enabled || businessMock.allowHazardUpload
    }
}

internal class StructuredHazardResultSession(
    val source: StructuredHazardSource,
    imagePayload: DeepV2ImagePayload,
    val presentation: DeepV2Presentation,
    val requestId: Long,
    val epoch: Long,
) {
    val imagePayload = imagePayload.copy(jpegBytes = imagePayload.jpegBytes.copyOf())

    fun pageCounts(): IntArray = buildList {
        presentation.targets.forEach { target -> add(target.hazards.size.coerceAtLeast(1)) }
        presentation.others?.let { others -> add(others.hazards.size.coerceAtLeast(1)) }
    }.toIntArray()

    fun toResolvedHazardContent(): ResolvedHazardContent =
        DeepV2ResolvedHazardAdapter.adapt(presentation, imagePayload)
}
