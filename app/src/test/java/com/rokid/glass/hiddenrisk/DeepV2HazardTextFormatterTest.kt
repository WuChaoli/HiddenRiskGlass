package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepV2HazardTextFormatterTest {

    @Test
    fun `format includes four fields and omits hazard code`() {
        val text = DeepV2HazardTextFormatter.format(hazard("HZ-001", "描述"))

        assertTrue(text.contains("隐患描述：描述"))
        assertTrue(text.contains("隐患等级：一般隐患"))
        assertTrue(text.contains("主要依据：依据"))
        assertTrue(text.contains("整改建议：建议"))
        assertFalse(text.contains("HZ-001"))
        assertFalse(text.contains("隐患编号"))
    }

    @Test
    fun `format group keeps hazards in order with separator`() {
        val text = DeepV2HazardTextFormatter.formatGroup(
            listOf(hazard("HZ-1", "第一条"), hazard("HZ-2", "第二条")),
        )

        assertTrue(text.indexOf("隐患 1") < text.indexOf("隐患 2"))
        assertTrue(text.indexOf("第一条") < text.indexOf("第二条"))
        assertTrue(text.contains(DeepV2HazardTextFormatter.ITEM_SEPARATOR))
    }

    @Test
    fun `blank fields do not produce empty labels`() {
        val text = DeepV2HazardTextFormatter.format(
            hazard("HZ", "").copy(level = "", lawBasis = "", advice = ""),
        )

        assertEquals("", text)
    }

    @Test
    fun `page planner uses measured line bottoms without dropping lines`() {
        val lines = listOf(
            DeepV2MeasuredTextLine(0, 5, 20),
            DeepV2MeasuredTextLine(5, 10, 40),
            DeepV2MeasuredTextLine(10, 15, 60),
        )

        assertEquals(
            listOf(0..1, 2..2),
            DeepV2MeasuredPagePlanner.plan(lines, viewportHeightPx = 40),
        )
    }

    @Test
    fun `page planner always makes progress when one line exceeds viewport`() {
        val lines = listOf(
            DeepV2MeasuredTextLine(0, 5, 60),
            DeepV2MeasuredTextLine(5, 10, 80),
        )

        assertEquals(
            listOf(0..0, 1..1),
            DeepV2MeasuredPagePlanner.plan(lines, viewportHeightPx = 40),
        )
    }

    private fun hazard(code: String, description: String): DeepV2PresentationHazard {
        return DeepV2PresentationHazard(
            labelId = "det",
            label = "燃气灶",
            description = description,
            level = "一般隐患",
            lawBasis = "依据",
            advice = "建议",
            hazardCode = code,
            sourceIndex = 0,
        )
    }
}
