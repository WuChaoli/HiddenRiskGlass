package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiArHazardDetailParserTest {
    @Test
    fun parse_extractsStructuredFieldsAndMapsLevel() {
        val parsed = AiArHazardDetailParser.parse(
            text = """
                隐患描述：燃气灶未配置熄火保护装置
                隐患等级：重点问题
                主要依据：GB 55009-2021
                整改建议：立即更换合规灶具
                隐患编号：ZJYJ-001
            """.trimIndent(),
            jpegBytes = byteArrayOf(1, 2, 3),
        )

        assertEquals(HazardSource.ONLINE, parsed.source)
        assertEquals("燃气灶未配置熄火保护装置", parsed.description)
        assertEquals("3", parsed.hidLevel)
        assertEquals("GB 55009-2021", parsed.lawBasis)
        assertEquals("立即更换合规灶具", parsed.advice)
        assertEquals("ZJYJ-001", parsed.hidNum)
    }

    @Test
    fun parse_supportsHalfWidthColonAndMultilineAdvice() {
        val parsed = AiArHazardDetailParser.parse(
            text = """
                隐患描述: 配电箱未安装漏电保护器
                隐患等级: 一般隐患
                主要依据: 《消防法》第27条
                整改建议: 1. 更换保护器
                2. 补充月度自检
                隐患编号: ZJYJ-002
            """.trimIndent(),
            jpegBytes = byteArrayOf(9),
        )

        assertEquals("1", parsed.hidLevel)
        assertEquals("1. 更换保护器\n2. 补充月度自检", parsed.advice)
    }

    @Test
    fun parse_allowsMissingStructuredFields() {
        val parsed = AiArHazardDetailParser.parse(
            text = """
                隐患描述：配电箱前堆放杂物
                整改建议：立即清理并保持通道畅通
            """.trimIndent(),
            jpegBytes = byteArrayOf(7, 8),
        )

        assertEquals("配电箱前堆放杂物", parsed.description)
        assertEquals("", parsed.hidLevel)
        assertEquals("", parsed.lawBasis)
        assertEquals("立即清理并保持通道畅通", parsed.advice)
        assertEquals("", parsed.hidNum)
        assertTrue(parsed.hasStructuredFields())
    }

    @Test
    fun parse_keepsRawTextWhenNoStructuredLabelsFound() {
        val text = "现场存在杂物堆积和电线裸露，建议尽快整改。"
        val parsed = AiArHazardDetailParser.parse(
            text = text,
            jpegBytes = byteArrayOf(5),
        )

        assertFalse(parsed.hasStructuredFields())
        assertEquals(text, parsed.rawDetailText)
        assertEquals(text, parsed.displayDescription())
        assertEquals(text, parsed.displayAdvice())
    }
}
