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
        require(matches.isNotEmpty()) { "在线详情缺少结构化标签" }

        val fields = linkedMapOf<String, String>()
        matches.forEachIndexed { index, match ->
            val label = match.groupValues[1]
            val valueStart = match.range.last + 1
            val valueEnd = matches.getOrNull(index + 1)?.range?.first ?: normalizedText.length
            fields[label] = normalizedText.substring(valueStart, valueEnd).trim()
        }

        return ResolvedHazardContent(
            source = HazardSource.ONLINE,
            description = fields["隐患描述"].orEmpty(),
            advice = fields["整改建议"].orEmpty(),
            hidLevel = ResolvedHazardContent.levelCode(fields["隐患等级"].orEmpty()),
            hidNum = fields["隐患编号"].orEmpty(),
            lawBasis = fields["主要依据"].orEmpty(),
            displayTitle = displayTitle,
            jpegBytes = jpegBytes.copyOf(),
        )
    }
}
