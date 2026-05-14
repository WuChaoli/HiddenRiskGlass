package com.rokid.glass.hiddenrisk

/**
 * 可直接展示并可进入保存链路的隐患结果。
 */
data class ResolvedHazardItem(
    val displayTitle: String,
    val description: String,
    val advice: String,
    val uploadAdvice: String,
    val hidLevel: String,
    val hidNum: String,
    val lawBasis: String,
) {
    fun hasStructuredFields(): Boolean {
        return description.isNotBlank() ||
            advice.isNotBlank() ||
            uploadAdvice.isNotBlank() ||
            hidLevel.isNotBlank() ||
            hidNum.isNotBlank() ||
            lawBasis.isNotBlank()
    }
}

data class ResolvedHazardContent(
    val source: HazardSource,
    val description: String,
    val advice: String,
    val uploadAdvice: String = "",
    val hidLevel: String,
    val hidNum: String,
    val lawBasis: String,
    val displayTitle: String,
    val jpegBytes: ByteArray,
    val rawDetailText: String = "",
    val hazards: List<ResolvedHazardItem> = emptyList(),
    val localCooldownLabels: List<String> = emptyList(),
) {
    fun resolvedHazards(): List<ResolvedHazardItem> {
        if (hazards.isNotEmpty()) {
            return hazards
        }
        val fallback = ResolvedHazardItem(
            displayTitle = displayTitle,
            description = description,
            advice = advice,
            uploadAdvice = uploadAdvice,
            hidLevel = hidLevel,
            hidNum = hidNum,
            lawBasis = lawBasis,
        )
        return if (fallback.hasStructuredFields()) listOf(fallback) else emptyList()
    }

    fun primaryHazard(): ResolvedHazardItem? {
        return resolvedHazards().firstOrNull()
    }

    fun hazardCount(): Int {
        return resolvedHazards().size
    }

    fun hasStructuredFields(): Boolean {
        return resolvedHazards().any { it.hasStructuredFields() }
    }

    fun displayDescription(): String {
        val resolvedHazards = resolvedHazards()
        val structuredText = when {
            resolvedHazards.isEmpty() -> ""
            resolvedHazards.size == 1 -> buildHazardDescriptionBlock(resolvedHazards.first())
            else -> resolvedHazards.mapIndexed { index, hazard ->
                buildList {
                    add("隐患${index + 1}")
                    buildHazardDescriptionBlock(hazard)
                        .lineSequence()
                        .map { it.trimEnd() }
                        .filter { it.isNotBlank() }
                        .forEach(::add)
                }.joinToString("\n")
            }.joinToString("\n\n")
        }
        return structuredText.ifBlank { rawDetailText.trim() }
    }

    /**
     * 在线 description 页保留原始文字流，避免流结束后再被结构化文案替换。
     */
    fun descriptionPageText(): String {
        return when (source) {
            HazardSource.ONLINE -> rawDetailText.trim().ifBlank { displayDescription() }
            HazardSource.LOCAL -> displayDescription()
        }
    }

    /**
     * 在线结果仅识别出一条，且隐患编号与隐患等级都为空或“无”时，视为无隐患。
     */
    fun isOnlineNoHazardResult(): Boolean {
        if (source != HazardSource.ONLINE) {
            return false
        }
        val hazards = resolvedHazards()
        if (hazards.size != 1) {
            return false
        }
        val hazard = hazards.first()
        return hazard.hidNum.isBlankOrNone() && hazard.hidLevel.isBlankOrNone()
    }

    fun displayAdvice(): String {
        val adviceText = primaryHazard()?.advice?.trim().orEmpty()
        return when {
            adviceText.isNotBlank() -> "基于上述隐患，建议您重点关注以下问题：\n$adviceText"
            !hasStructuredFields() -> rawDetailText.trim()
            else -> ""
        }
    }

    private fun buildHazardDescriptionBlock(hazard: ResolvedHazardItem): String {
        return buildList {
            hazard.description.trim().takeIf { it.isNotBlank() }?.let { add("隐患描述：$it") }
            hazard.hidLevel.trim().takeIf { it.isNotBlank() }?.let { add("隐患等级：${levelLabel(it)}") }
            hazard.lawBasis.trim().takeIf { it.isNotBlank() }?.let { add("主要依据：$it") }
            hazard.hidNum.trim().takeIf { it.isNotBlank() }?.let { add("隐患编号：$it") }
            hazard.uploadAdvice.trim().takeIf { it.isNotBlank() }?.let { add("整改建议：$it") }
        }.joinToString("\n")
    }

    private fun String.isBlankOrNone(): Boolean {
        val normalized = trim()
        return normalized.isBlank() || normalized == "无"
    }

    companion object {
        fun levelCode(labelOrCode: String): String {
            return when (labelOrCode.trim()) {
                "1", "一般隐患" -> "1"
                "2", "重大隐患" -> "2"
                "3", "重点问题" -> "3"
                else -> ""
            }
        }

        fun levelLabel(codeOrLabel: String): String {
            return when (codeOrLabel.trim()) {
                "1" -> "一般隐患"
                "2" -> "重大隐患"
                "3" -> "重点问题"
                else -> codeOrLabel.trim()
            }
        }
    }
}

enum class HazardSource {
    LOCAL,
    ONLINE,
}
