package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHazardDetailResolverTest {
    private val knowledge = listOf(
        knowledge("ZJYJ_HZ_JX_XCY_010", "负荷开关未配置T字按钮"),
        knowledge("ZJYJ_HZ_JX_XCY_006", "使用燃气场景未安装报警器"),
        knowledge("ZJYJ_HZ_JX_XCY_009", "燃气灶未安装熄火保护"),
        knowledge("ZJYJ_HZ_JX_XCY_005", "消火栓箱配件缺失"),
    )

    @Test
    fun resolvesSingleRuleToStructuredOfflineContent() {
        val content = LocalHazardDetailResolver.resolve(
            matches = LocalHazardRuleEvaluator.evaluate(listOf("负荷开关")),
            knowledge = knowledge,
            jpegBytes = byteArrayOf(1, 2),
        )

        assertEquals(HazardSource.LOCAL, content?.source)
        assertEquals("ZJYJ_HZ_JX_XCY_010", content?.hidNum)
        assertTrue(content?.rawDetailText.orEmpty().contains("隐患描述：负荷开关未配置T字按钮"))
        assertTrue(content?.rawDetailText.orEmpty().contains("整改建议：整改-ZJYJ_HZ_JX_XCY_010"))
    }

    @Test
    fun resolvesMultipleRulesInStableOrder() {
        val content = LocalHazardDetailResolver.resolve(
            LocalHazardRuleEvaluator.evaluate(listOf("燃气灶", "负荷开关")),
            knowledge,
            byteArrayOf(1),
        )

        assertEquals(
            listOf("ZJYJ_HZ_JX_XCY_010", "ZJYJ_HZ_JX_XCY_009"),
            content?.resolvedHazards()?.map { it.hidNum },
        )
    }

    @Test
    fun fireCabinetDescriptionListsActualMissingParts() {
        val content = LocalHazardDetailResolver.resolve(
            LocalHazardRuleEvaluator.evaluate(listOf("室内消火栓箱", "水带")),
            knowledge,
            byteArrayOf(1),
        )

        assertTrue(content?.description.orEmpty().contains("水枪、栓口"))
        assertTrue(!content?.description.orEmpty().contains("水带、"))
    }

    @Test
    fun missingKnowledgeDoesNotCreatePartialContent() {
        val content = LocalHazardDetailResolver.resolve(
            LocalHazardRuleEvaluator.evaluate(listOf("负荷开关")),
            emptyList(),
            byteArrayOf(1),
        )

        assertEquals(null, content)
    }

    private fun knowledge(hidNum: String, description: String) = LocalHazardKnowledge(
        hidNum = hidNum,
        description = description,
        hidLevel = "1",
        lawBasis = "依据-$hidNum",
        advice = "检查-$hidNum",
        modify = "整改-$hidNum",
    )
}
