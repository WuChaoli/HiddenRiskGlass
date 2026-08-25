package com.rokid.glass.hiddenrisk

internal class DeepV2ResultNormalizer {

    fun normalize(response: DeepV2Response): DeepV2Presentation {
        val selectedDetections = response.detections
            .groupBy(DeepV2Detection::labelId)
            .mapValues { (_, detections) -> detections.maxWith(DETECTION_COMPARATOR) }

        val candidates = response.hazards.mapNotNull { hazard ->
            when {
                hazard.labelId == OTHERS_LABEL_ID -> HazardCandidate(hazard, null)
                else -> selectedDetections[hazard.labelId]?.let { detection ->
                    HazardCandidate(hazard, detection)
                }
            }
        }
        val normalizedCandidates = deduplicateHazardCodes(candidates)
        val candidatesByLabel = normalizedCandidates.groupBy { it.hazard.labelId }

        val targets = selectedDetections.values.mapNotNull { detection ->
            val hazards = candidatesByLabel[detection.labelId]
                .orEmpty()
                .sortedBy { it.hazard.sourceIndex }
                .map { candidate -> candidate.toPresentationHazard(detection.label) }
            if (hazards.isEmpty()) return@mapNotNull null
            val bbox = detection.toBoundingBox()
            DeepV2Target(
                labelId = detection.labelId,
                label = detection.label,
                bbox = bbox,
                detectionScore = detection.score,
                detectionIndex = detection.sourceIndex,
                highestLevel = DeepV2Severity.highest(hazards),
                hazards = hazards,
            )
        }.sortedWith(
            compareBy<DeepV2Target> { it.bbox.top }
                .thenBy { it.bbox.left }
                .thenBy { it.detectionIndex },
        )

        val globalHazards = candidatesByLabel[OTHERS_LABEL_ID]
            .orEmpty()
            .sortedBy { it.hazard.sourceIndex }
            .map { candidate -> candidate.toPresentationHazard(GLOBAL_LABEL) }
            .takeIf(List<DeepV2PresentationHazard>::isNotEmpty)
            ?.let { hazards ->
                DeepV2GlobalHazards(
                    highestLevel = DeepV2Severity.highest(hazards),
                    hazards = hazards,
                )
            }

        val uploadHazards = buildList {
            targets.forEach { target -> addAll(target.hazards.filter(::hasUploadCode)) }
            globalHazards?.hazards?.filter(::hasUploadCode)?.let(::addAll)
        }
        return DeepV2Presentation(
            targets = targets,
            others = globalHazards,
            uploadHazards = uploadHazards,
            suggestionHazardCode = uploadHazards.firstOrNull()?.hazardCode,
        )
    }

    private fun deduplicateHazardCodes(candidates: List<HazardCandidate>): List<HazardCandidate> {
        val winnersByCode = candidates
            .filter { it.hazard.hazardCode.isNotBlank() }
            .groupBy { it.hazard.hazardCode }
            .mapValues { (_, duplicates) -> duplicates.maxWith(HAZARD_CODE_COMPARATOR) }
        return candidates.filter { candidate ->
            candidate.hazard.hazardCode.isBlank() ||
                winnersByCode[candidate.hazard.hazardCode] === candidate
        }
    }

    private fun DeepV2Detection.toBoundingBox(): DeepV2BoundingBox {
        return DeepV2BoundingBox(
            left = bbox[0].toFloat(),
            top = bbox[1].toFloat(),
            right = bbox[2].toFloat(),
            bottom = bbox[3].toFloat(),
        )
    }

    private fun HazardCandidate.toPresentationHazard(label: String): DeepV2PresentationHazard {
        return DeepV2PresentationHazard(
            labelId = hazard.labelId,
            label = label,
            description = hazard.description,
            level = DeepV2Severity.displayLabel(hazard.level),
            lawBasis = hazard.lawBasis,
            advice = hazard.advice,
            hazardCode = hazard.hazardCode,
            sourceIndex = hazard.sourceIndex,
        )
    }

    private fun hasUploadCode(hazard: DeepV2PresentationHazard): Boolean {
        return hazard.hazardCode.isNotBlank()
    }

    private data class HazardCandidate(
        val hazard: DeepV2Hazard,
        val detection: DeepV2Detection?,
    ) {
        val detectionArea: Double
            get() = detection?.let { value ->
                (value.bbox[2] - value.bbox[0]) * (value.bbox[3] - value.bbox[1])
            } ?: Double.NEGATIVE_INFINITY
    }

    private companion object {
        const val OTHERS_LABEL_ID = "others"
        const val GLOBAL_LABEL = "全局隐患"

        val DETECTION_COMPARATOR = compareBy<DeepV2Detection> { it.score }
            .thenBy { detection ->
                (detection.bbox[2] - detection.bbox[0]) *
                    (detection.bbox[3] - detection.bbox[1])
            }
            .thenByDescending { it.sourceIndex }

        val HAZARD_CODE_COMPARATOR = compareBy<HazardCandidate> {
            it.detection?.score ?: Double.NEGATIVE_INFINITY
        }.thenBy { it.detectionArea }
            .thenByDescending { it.hazard.sourceIndex }
    }
}

internal object DeepV2Severity {
    fun rank(level: String): Int {
        return when (displayLabel(level)) {
            "重大隐患" -> 3
            "重点问题" -> 2
            "一般隐患" -> 1
            else -> 0
        }
    }

    fun displayLabel(level: String): String {
        return ResolvedHazardContent.levelLabel(level).ifBlank { level.trim() }
    }

    fun highest(hazards: List<DeepV2PresentationHazard>): String {
        return hazards.maxWithOrNull(
            compareBy<DeepV2PresentationHazard> { rank(it.level) }
                .thenByDescending { it.sourceIndex },
        )?.level.orEmpty()
    }
}
