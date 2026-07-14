package com.rokid.glass.hiddenrisk

/** 本地隐患知识记录，字段与 assets/info.json 对齐。 */
internal data class LocalHazardKnowledge(
    val hidNum: String,
    val description: String,
    val hidLevel: String,
    val lawBasis: String,
    val advice: String,
    val modify: String,
)

/** 将本地组合规则命中转换为在线详情页可消费的统一结果。 */
internal object LocalHazardDetailResolver {
    private val hazardCodeByRule = mapOf(
        LocalHazardRuleEvaluator.RuleId.LOAD_SWITCH to "ZJYJ_HZ_JX_XCY_010",
        LocalHazardRuleEvaluator.RuleId.LPG_ALARM to "ZJYJ_HZ_JX_XCY_006",
        LocalHazardRuleEvaluator.RuleId.GAS_RANGE to "ZJYJ_HZ_JX_XCY_009",
        LocalHazardRuleEvaluator.RuleId.FIRE_CABINET to "ZJYJ_HZ_JX_XCY_005",
    )

    fun resolve(
        matches: List<LocalHazardRuleEvaluator.Match>,
        knowledge: List<LocalHazardKnowledge>,
        jpegBytes: ByteArray,
    ): ResolvedHazardContent? {
        if (matches.isEmpty()) return null
        val knowledgeByCode = knowledge.associateBy { it.hidNum.trim() }
        val hazards = matches.map { match ->
            val code = requireNotNull(hazardCodeByRule[match.ruleId])
            val item = knowledgeByCode[code] ?: return null
            val description = if (match.ruleId == LocalHazardRuleEvaluator.RuleId.FIRE_CABINET) {
                "室内消火栓箱缺少${match.missingLabels.joinToString("、")}，存在安全隐患"
            } else {
                item.description
            }
            ResolvedHazardItem(
                displayTitle = match.primaryLabel,
                description = description,
                advice = item.advice,
                uploadAdvice = item.modify,
                hidLevel = item.hidLevel,
                hidNum = item.hidNum,
                lawBasis = item.lawBasis,
            )
        }
        val primary = hazards.first()
        val rawText = hazards.joinToString("\n\n") { hazard ->
            listOf(
                "隐患描述：${hazard.description}",
                "隐患等级：${ResolvedHazardContent.levelLabel(hazard.hidLevel)}",
                "主要依据：${hazard.lawBasis}",
                "隐患编号：${hazard.hidNum}",
                "整改建议：${hazard.uploadAdvice}",
            ).joinToString("\n")
        }
        return ResolvedHazardContent(
            source = HazardSource.LOCAL,
            description = primary.description,
            advice = primary.advice,
            uploadAdvice = primary.uploadAdvice,
            hidLevel = primary.hidLevel,
            hidNum = primary.hidNum,
            lawBasis = primary.lawBasis,
            displayTitle = primary.displayTitle,
            jpegBytes = jpegBytes.copyOf(),
            rawDetailText = rawText,
            hazards = hazards,
            localCooldownLabels = matches.map { it.primaryLabel },
            remoteSaveAllowed = false,
        )
    }
}
