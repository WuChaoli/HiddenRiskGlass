package com.rokid.glass.hiddenrisk

/**
 * 根据同一次本地推理的标签集合判断“主对象存在但保护装置缺失”的隐患。
 */
internal object LocalHazardRuleEvaluator {
    enum class RuleId {
        LOAD_SWITCH,
        LPG_ALARM,
        GAS_RANGE,
        FIRE_CABINET,
    }

    data class Match(
        val ruleId: RuleId,
        val primaryLabel: String,
        val missingLabels: List<String>,
    )

    private data class Rule(
        val id: RuleId,
        val primaryLabel: String,
        val requiredLabels: List<String>,
    )

    private val rules = listOf(
        Rule(RuleId.LOAD_SWITCH, "负荷开关", listOf("T字按钮")),
        Rule(RuleId.LPG_ALARM, "液化石油气瓶", listOf("可燃气体报警器")),
        Rule(RuleId.GAS_RANGE, "燃气灶", listOf("熄火保护装置")),
        Rule(RuleId.FIRE_CABINET, "室内消火栓箱", listOf("水枪", "水带", "栓口")),
    )

    fun evaluate(labels: Collection<String>): List<Match> {
        val normalizedLabels = labels.map(String::trim).filter(String::isNotBlank).toSet()
        return rules.mapNotNull { rule ->
            if (rule.primaryLabel !in normalizedLabels) return@mapNotNull null
            val missingLabels = rule.requiredLabels.filterNot(normalizedLabels::contains)
            if (missingLabels.isEmpty()) null else Match(rule.id, rule.primaryLabel, missingLabels)
        }
    }
}
