package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHazardRuleEvaluatorTest {
    @Test
    fun evaluatesMissingProtectionRulesInStableOrder() {
        val matches = LocalHazardRuleEvaluator.evaluate(
            listOf("燃气灶", "负荷开关", "液化石油气瓶", "室内消火栓箱", "水带"),
        )

        assertEquals(
            listOf(
                LocalHazardRuleEvaluator.RuleId.LOAD_SWITCH,
                LocalHazardRuleEvaluator.RuleId.LPG_ALARM,
                LocalHazardRuleEvaluator.RuleId.GAS_RANGE,
                LocalHazardRuleEvaluator.RuleId.FIRE_CABINET,
            ),
            matches.map { it.ruleId },
        )
        assertEquals(listOf("水枪", "栓口"), matches.last().missingLabels)
    }

    @Test
    fun completeProtectionDoesNotProduceHazards() {
        val matches = LocalHazardRuleEvaluator.evaluate(
            listOf(
                "负荷开关", "T字按钮",
                "液化石油气瓶", "可燃气体报警器",
                "燃气灶", "熄火保护装置",
                "室内消火栓箱", "水枪", "水带", "栓口",
            ),
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun protectionLabelsWithoutPrimaryObjectsDoNotProduceHazards() {
        assertTrue(
            LocalHazardRuleEvaluator.evaluate(
                listOf("T字按钮", "可燃气体报警器", "熄火保护装置", "水枪", "水带", "栓口"),
            ).isEmpty(),
        )
    }

    @Test
    fun trimsAndDeduplicatesLabels() {
        val matches = LocalHazardRuleEvaluator.evaluate(listOf(" 燃气灶 ", "燃气灶"))

        assertEquals(1, matches.size)
        assertEquals(listOf("熄火保护装置"), matches.single().missingLabels)
    }
}
