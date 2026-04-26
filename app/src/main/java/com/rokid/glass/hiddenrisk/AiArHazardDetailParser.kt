package com.rokid.glass.hiddenrisk

/**
 * 解析 /ai/ar ctype=0 返回的结构化文本。
 */
object AiArHazardDetailParser {
    private val labelRegex = Regex(
        pattern = """(?m)^(隐患描述|隐患等级|主要依据|整改建议|隐患编号)\s*[：:]\s*""",
    )

    fun parse(
        text: String,
        jpegBytes: ByteArray,
        displayTitle: String = "在线识别隐患",
    ): ResolvedHazardContent {
        val normalizedText = text.trim()
        require(normalizedText.isNotBlank()) { "在线详情为空" }

        val matches = labelRegex.findAll(normalizedText).toList()
        val hazards = mutableListOf<ResolvedHazardItem>()
        var fields = linkedMapOf<String, String>()
        matches.forEachIndexed { index, match ->
            val label = match.groupValues[1]
            val valueStart = match.range.last + 1
            val valueEnd = matches.getOrNull(index + 1)?.range?.first ?: normalizedText.length
            if (label == "隐患描述" && fields.isNotEmpty()) {
                fields.toResolvedHazard(displayTitle)
                    .takeIf { it.hasStructuredFields() }
                    ?.let(hazards::add)
                fields = linkedMapOf()
            }
            fields[label] = normalizedText.substring(valueStart, valueEnd).trim()
        }
        fields.toResolvedHazard(displayTitle)
            .takeIf { it.hasStructuredFields() }
            ?.let(hazards::add)

        val primaryHazard = hazards.firstOrNull() ?: emptyResolvedHazard(displayTitle)
        return ResolvedHazardContent(
            source = HazardSource.ONLINE,
            description = primaryHazard.description,
            advice = primaryHazard.advice,
            uploadAdvice = primaryHazard.uploadAdvice,
            hidLevel = primaryHazard.hidLevel,
            hidNum = primaryHazard.hidNum,
            lawBasis = primaryHazard.lawBasis,
            displayTitle = displayTitle,
            jpegBytes = jpegBytes.copyOf(),
            rawDetailText = normalizedText,
            hazards = hazards,
        )
    }

    private fun Map<String, String>.toResolvedHazard(displayTitle: String): ResolvedHazardItem {
        return ResolvedHazardItem(
            displayTitle = displayTitle,
            description = this["隐患描述"].orEmpty(),
            advice = this["整改建议"].orEmpty(),
            uploadAdvice = this["整改建议"].orEmpty(),
            hidLevel = ResolvedHazardContent.levelCode(this["隐患等级"].orEmpty()),
            hidNum = this["隐患编号"].orEmpty(),
            lawBasis = this["主要依据"].orEmpty(),
        )
    }

    private fun emptyResolvedHazard(displayTitle: String): ResolvedHazardItem {
        return ResolvedHazardItem(
            displayTitle = displayTitle,
            description = "",
            advice = "",
            uploadAdvice = "",
            hidLevel = "",
            hidNum = "",
            lawBasis = "",
        )
    }
}
