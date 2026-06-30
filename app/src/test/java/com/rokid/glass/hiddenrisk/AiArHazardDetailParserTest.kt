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
    fun parse_extractsMultipleHazardsAndAdviceUsesFirstHazardOnly() {
        val parsed = AiArHazardDetailParser.parse(
            text = """
                隐患描述：燃气灶未配置熄火保护装置
                隐患等级：一般隐患
                主要依据：GB 55009-2021 第 6.1.2 条
                整改建议：立即更换合规灶具
                隐患编号：ZJYJ-001

                隐患描述：配电箱前堆放杂物
                隐患等级：重大隐患
                主要依据：《消防法》第 27 条
                整改建议：清理配电箱周边杂物
                隐患编号：ZJYJ-002
            """.trimIndent(),
            jpegBytes = byteArrayOf(1),
        )

        val hazards = parsed.resolvedHazards()
        assertEquals(2, hazards.size)
        assertEquals("燃气灶未配置熄火保护装置", hazards[0].description)
        assertEquals("1", hazards[0].hidLevel)
        assertEquals("GB 55009-2021 第 6.1.2 条", hazards[0].lawBasis)
        assertEquals("立即更换合规灶具", hazards[0].advice)
        assertEquals("ZJYJ-001", hazards[0].hidNum)
        assertEquals("配电箱前堆放杂物", hazards[1].description)
        assertEquals("2", hazards[1].hidLevel)
        assertEquals("《消防法》第 27 条", hazards[1].lawBasis)
        assertEquals("清理配电箱周边杂物", hazards[1].advice)
        assertEquals("ZJYJ-002", hazards[1].hidNum)

        val description = parsed.displayDescription()
        assertTrue(description.contains("隐患1\n隐患描述：燃气灶未配置熄火保护装置"))
        assertTrue(description.contains("整改建议：立即更换合规灶具"))
        assertTrue(description.contains("\n\n隐患2\n隐患描述：配电箱前堆放杂物"))
        assertTrue(description.contains("整改建议：清理配电箱周边杂物"))
        assertEquals(
            "基于上述隐患，建议您重点关注以下问题：\n立即更换合规灶具",
            parsed.displayAdvice(),
        )
        assertFalse(parsed.displayAdvice().contains("清理配电箱周边杂物"))
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

    @Test
    fun parse_supportsHazardCodeAliasAndDetectsNoHazard() {
        val parsed = AiArHazardDetailParser.parse(
            text = """
                隐患描述：无
                隐患等级：无
                隐患编码：无
                整改建议：无
                主要依据：无
            """.trimIndent(),
            jpegBytes = byteArrayOf(6),
        )

        assertEquals("无", parsed.hidNum)
        assertEquals("无", parsed.lawBasis)
        assertTrue(parsed.isOnlineNoHazardResult())
    }

    @Test
    fun parse_treatsBlankHazardCodeAndLevelAsNoHazard() {
        val parsed = AiArHazardDetailParser.parse(
            text = """
                隐患描述：无
                隐患等级：
                隐患编码：
                主要依据：GB 55009-2021
                整改建议：无
            """.trimIndent(),
            jpegBytes = byteArrayOf(7),
        )

        assertEquals("", parsed.hidNum)
        assertEquals("", parsed.hidLevel)
        assertEquals("GB 55009-2021", parsed.lawBasis)
        assertTrue(parsed.isOnlineNoHazardResult())
    }

    @Test
    fun parse_keepsLegacyHazardNumberField() {
        val parsed = AiArHazardDetailParser.parse(
            text = """
                隐患描述：燃气软管老化
                隐患编号：HZ-LEGACY-001
                主要依据：GB 55009-2021
                整改建议：更换燃气软管
            """.trimIndent(),
            jpegBytes = byteArrayOf(8),
        )

        assertEquals("HZ-LEGACY-001", parsed.hidNum)
        assertEquals("GB 55009-2021", parsed.lawBasis)
        assertFalse(parsed.isOnlineNoHazardResult())
    }

    @Test
    fun parse_doesNotLetSuggestedCheckItemPolluteHazardCode() {
        val parsed = AiArHazardDetailParser.parse(
            text = """
                隐患描述：经核查，图像中未发现相关的安全隐患。
                隐患等级：无
                主要依据：无
                整改建议：由于未见任何隐患，无法提供整改建议。
                隐患编码：无
                建议检查项:无
            """.trimIndent(),
            jpegBytes = byteArrayOf(9),
        )

        assertEquals("无", parsed.hidNum)
        assertEquals("", parsed.hidLevel)
        assertEquals("无", parsed.lawBasis)
        assertTrue(parsed.isOnlineNoHazardResult())
    }
}
