package com.rokid.glass.hiddenrisk

internal object DeepV2HazardTextFormatter {
    const val ITEM_SEPARATOR = "────────"

    fun format(hazard: DeepV2PresentationHazard): String {
        return buildList {
            hazard.description.takeIf(String::isNotBlank)?.let { add("隐患描述：$it") }
            hazard.level.takeIf(String::isNotBlank)?.let { add("隐患等级：$it") }
            hazard.lawBasis.takeIf(String::isNotBlank)?.let { add("主要依据：$it") }
            hazard.advice.takeIf(String::isNotBlank)?.let { add("整改建议：$it") }
        }.joinToString("\n")
    }

    fun formatGroup(hazards: List<DeepV2PresentationHazard>): String {
        if (hazards.size == 1) return format(hazards.single())
        return hazards.mapIndexed { index, hazard ->
            buildList {
                add("隐患 ${index + 1}")
                format(hazard).takeIf(String::isNotBlank)?.let(::add)
            }.joinToString("\n")
        }.joinToString("\n$ITEM_SEPARATOR\n")
    }
}

internal data class DeepV2MeasuredTextLine(
    val start: Int,
    val end: Int,
    val bottom: Int,
)

internal object DeepV2MeasuredPagePlanner {
    fun plan(
        lines: List<DeepV2MeasuredTextLine>,
        viewportHeightPx: Int,
    ): List<IntRange> {
        if (lines.isEmpty()) return emptyList()
        require(viewportHeightPx > 0)
        val pages = mutableListOf<IntRange>()
        var pageStart = 0
        while (pageStart < lines.size) {
            val pageTop = if (pageStart == 0) 0 else lines[pageStart - 1].bottom
            var pageEnd = pageStart
            while (
                pageEnd + 1 < lines.size &&
                lines[pageEnd + 1].bottom - pageTop <= viewportHeightPx
            ) {
                pageEnd += 1
            }
            pages += pageStart..pageEnd
            pageStart = pageEnd + 1
        }
        return pages
    }

    fun slice(
        text: CharSequence,
        lines: List<DeepV2MeasuredTextLine>,
        ranges: List<IntRange>,
    ): List<CharSequence> {
        return ranges.map { range ->
            text.subSequence(lines[range.first].start, lines[range.last].end)
        }
    }
}
