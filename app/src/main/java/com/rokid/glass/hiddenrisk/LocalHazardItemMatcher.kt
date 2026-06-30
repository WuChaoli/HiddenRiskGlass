package com.rokid.glass.hiddenrisk

/**
 * 解析并匹配 info.json 中的本地隐患 item 表达式。
 */
internal object LocalHazardItemMatcher {

    private const val AND_SEPARATOR = "&"

    data class Match(
        val matchedItem: String,
        val cooldownLabel: String,
        val score: Float,
    )

    fun match(
        itemName: String,
        detectedScoresByLabel: Map<String, Float>,
    ): Match? {
        val labels = splitItemLabels(itemName)
        if (labels.isEmpty()) {
            return null
        }
        val scores = labels.map { label ->
            detectedScoresByLabel[label] ?: return null
        }
        return Match(
            matchedItem = labels.joinToString(AND_SEPARATOR),
            cooldownLabel = labels.first(),
            score = scores.minOrNull() ?: return null,
        )
    }

    fun splitItemLabels(itemName: String): List<String> {
        return itemName
            .split(AND_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
}
