package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedHazardContentTest {
    @Test
    fun displayDescription_rendersMultipleHazardsInOrder() {
        val content = ResolvedHazardContent(
            source = HazardSource.LOCAL,
            description = "主隐患描述",
            advice = "主隐患建议",
            uploadAdvice = "主隐患整改",
            hidLevel = "1",
            hidNum = "HZ-001",
            lawBasis = "依据 A",
            displayTitle = "燃气灶",
            jpegBytes = byteArrayOf(1),
            hazards = listOf(
                ResolvedHazardItem(
                    displayTitle = "燃气灶",
                    description = "主隐患描述",
                    advice = "主隐患建议",
                    uploadAdvice = "主隐患整改",
                    hidLevel = "1",
                    hidNum = "HZ-001",
                    lawBasis = "依据 A",
                ),
                ResolvedHazardItem(
                    displayTitle = "液化石油气瓶",
                    description = "次隐患描述",
                    advice = "次隐患建议",
                    uploadAdvice = "次隐患整改",
                    hidLevel = "2",
                    hidNum = "HZ-002",
                    lawBasis = "依据 B",
                ),
            ),
        )

        val description = content.displayDescription()

        assertTrue(description.contains("隐患1"))
        assertTrue(description.contains("隐患描述：主隐患描述"))
        assertTrue(description.contains("隐患2"))
        assertTrue(description.contains("隐患等级：重大隐患"))
        assertTrue(description.contains("整改建议：主隐患整改"))
        assertTrue(description.contains("整改建议：次隐患整改"))
        assertEquals(2, content.hazardCount())
    }

    @Test
    fun displayAdvice_usesPrimaryHazardOnly() {
        val content = ResolvedHazardContent(
            source = HazardSource.LOCAL,
            description = "主隐患描述",
            advice = "主隐患建议",
            uploadAdvice = "主隐患整改",
            hidLevel = "1",
            hidNum = "HZ-001",
            lawBasis = "依据 A",
            displayTitle = "燃气灶",
            jpegBytes = byteArrayOf(1),
            hazards = listOf(
                ResolvedHazardItem(
                    displayTitle = "燃气灶",
                    description = "主隐患描述",
                    advice = "主隐患建议",
                    uploadAdvice = "主隐患整改",
                    hidLevel = "1",
                    hidNum = "HZ-001",
                    lawBasis = "依据 A",
                ),
                ResolvedHazardItem(
                    displayTitle = "液化石油气瓶",
                    description = "次隐患描述",
                    advice = "次隐患建议",
                    uploadAdvice = "次隐患整改",
                    hidLevel = "1",
                    hidNum = "HZ-002",
                    lawBasis = "依据 B",
                ),
            ),
        )

        assertEquals(
            "基于上述隐患，建议您重点关注以下问题：\n主隐患建议",
            content.displayAdvice(),
        )
    }

    @Test
    fun descriptionPageText_prefersRawStreamingTextForOnlineHazard() {
        val content = ResolvedHazardContent(
            source = HazardSource.ONLINE,
            description = "结构化隐患描述",
            advice = "结构化建议",
            uploadAdvice = "结构化整改",
            hidLevel = "2",
            hidNum = "HZ-009",
            lawBasis = "依据 C",
            displayTitle = "在线识别隐患",
            jpegBytes = byteArrayOf(2),
            rawDetailText = """
                隐患描述：原始流文本中的隐患描述
                隐患等级：重大隐患
                主要依据：原始流文本中的依据
                整改建议：原始流文本中的整改建议
            """.trimIndent(),
            hazards = listOf(
                ResolvedHazardItem(
                    displayTitle = "在线识别隐患",
                    description = "结构化隐患描述",
                    advice = "结构化建议",
                    uploadAdvice = "结构化整改",
                    hidLevel = "2",
                    hidNum = "HZ-009",
                    lawBasis = "依据 C",
                ),
            ),
        )

        assertEquals(content.rawDetailText, content.descriptionPageText())
        assertTrue(content.displayDescription().contains("隐患描述：结构化隐患描述"))
    }

    @Test
    fun isOnlineNoHazardResult_returnsTrueForSingleOnlineHazardWithWuHidNumAndLevel() {
        val content = ResolvedHazardContent(
            source = HazardSource.ONLINE,
            description = "现场未发现明显隐患",
            advice = "",
            uploadAdvice = "",
            hidLevel = "无",
            hidNum = "无",
            lawBasis = "GB 55009-2021",
            displayTitle = "在线识别隐患",
            jpegBytes = byteArrayOf(3),
            hazards = listOf(
                ResolvedHazardItem(
                    displayTitle = "在线识别隐患",
                    description = "现场未发现明显隐患",
                    advice = "",
                    uploadAdvice = "",
                    hidLevel = "无",
                    hidNum = "无",
                    lawBasis = "GB 55009-2021",
                ),
            ),
        )

        assertTrue(content.isOnlineNoHazardResult())
    }

    @Test
    fun isOnlineNoHazardResult_returnsTrueForBlankCodeAndLevel() {
        val content = ResolvedHazardContent(
            source = HazardSource.ONLINE,
            description = "现场未发现明显隐患",
            advice = "",
            uploadAdvice = "",
            hidLevel = " ",
            hidNum = " ",
            lawBasis = "GB 55009-2021",
            displayTitle = "在线识别隐患",
            jpegBytes = byteArrayOf(9),
            hazards = listOf(
                ResolvedHazardItem(
                    displayTitle = "在线识别隐患",
                    description = "现场未发现明显隐患",
                    advice = "",
                    uploadAdvice = "",
                    hidLevel = " ",
                    hidNum = " ",
                    lawBasis = "GB 55009-2021",
                ),
            ),
        )

        assertTrue(content.isOnlineNoHazardResult())
    }

    @Test
    fun isOnlineNoHazardResult_returnsFalseForOtherCases() {
        val multiHazardContent = ResolvedHazardContent(
            source = HazardSource.ONLINE,
            description = "主隐患",
            advice = "",
            uploadAdvice = "",
            hidLevel = "",
            hidNum = "无",
            lawBasis = "",
            displayTitle = "在线识别隐患",
            jpegBytes = byteArrayOf(4),
            hazards = listOf(
                ResolvedHazardItem(
                    displayTitle = "在线识别隐患",
                    description = "主隐患",
                    advice = "",
                    uploadAdvice = "",
                    hidLevel = "",
                    hidNum = "无",
                    lawBasis = "",
                ),
                ResolvedHazardItem(
                    displayTitle = "在线识别隐患",
                    description = "次隐患",
                    advice = "",
                    uploadAdvice = "",
                    hidLevel = "",
                    hidNum = "HZ-002",
                    lawBasis = "",
                ),
            ),
        )
        val localContent = ResolvedHazardContent(
            source = HazardSource.LOCAL,
            description = "现场未发现明显隐患",
            advice = "",
            uploadAdvice = "",
            hidLevel = "",
            hidNum = "无",
            lawBasis = "",
            displayTitle = "本地识别隐患",
            jpegBytes = byteArrayOf(5),
        )
        val onlineHazardContent = ResolvedHazardContent(
            source = HazardSource.ONLINE,
            description = "发现燃气隐患",
            advice = "",
            uploadAdvice = "",
            hidLevel = "",
            hidNum = "HZ-003",
            lawBasis = "",
            displayTitle = "在线识别隐患",
            jpegBytes = byteArrayOf(6),
        )
        val onlineBasisContent = ResolvedHazardContent(
            source = HazardSource.ONLINE,
            description = "发现燃气隐患",
            advice = "",
            uploadAdvice = "",
            hidLevel = "1",
            hidNum = "无",
            lawBasis = "GB 55009-2021",
            displayTitle = "在线识别隐患",
            jpegBytes = byteArrayOf(7),
        )
        val onlineLevelContent = ResolvedHazardContent(
            source = HazardSource.ONLINE,
            description = "发现燃气隐患",
            advice = "",
            uploadAdvice = "",
            hidLevel = "1",
            hidNum = "无",
            lawBasis = "",
            displayTitle = "在线识别隐患",
            jpegBytes = byteArrayOf(8),
        )

        assertFalse(multiHazardContent.isOnlineNoHazardResult())
        assertFalse(localContent.isOnlineNoHazardResult())
        assertFalse(onlineHazardContent.isOnlineNoHazardResult())
        assertFalse(onlineBasisContent.isOnlineNoHazardResult())
        assertFalse(onlineLevelContent.isOnlineNoHazardResult())
    }

    @Test
    fun recordableHazards_filtersOnlyItemsWithBlankOrNoneCodeAndLevel() {
        val noneCodeAndLevel = hazardItem(hidNum = "无", hidLevel = "无")
        val blankCodeAndLevel = hazardItem(hidNum = " ", hidLevel = "")
        val validCodeBlankLevel = hazardItem(hidNum = "HZ-001", hidLevel = "")
        val noneCodeValidLevel = hazardItem(hidNum = "无", hidLevel = "1")
        val content = ResolvedHazardContent(
            source = HazardSource.ONLINE,
            description = noneCodeAndLevel.description,
            advice = noneCodeAndLevel.advice,
            uploadAdvice = noneCodeAndLevel.uploadAdvice,
            hidLevel = noneCodeAndLevel.hidLevel,
            hidNum = noneCodeAndLevel.hidNum,
            lawBasis = noneCodeAndLevel.lawBasis,
            displayTitle = noneCodeAndLevel.displayTitle,
            jpegBytes = byteArrayOf(10),
            hazards = listOf(
                noneCodeAndLevel,
                blankCodeAndLevel,
                validCodeBlankLevel,
                noneCodeValidLevel,
            ),
        )

        assertTrue(noneCodeAndLevel.isNoHazardPlaceholder())
        assertTrue(blankCodeAndLevel.isNoHazardPlaceholder())
        assertFalse(validCodeBlankLevel.isNoHazardPlaceholder())
        assertFalse(noneCodeValidLevel.isNoHazardPlaceholder())
        assertEquals(listOf(validCodeBlankLevel, noneCodeValidLevel), content.recordableHazards())
    }

    private fun hazardItem(
        hidNum: String,
        hidLevel: String,
    ): ResolvedHazardItem {
        return ResolvedHazardItem(
            displayTitle = "online hazard",
            description = "description-$hidNum-$hidLevel",
            advice = "",
            uploadAdvice = "",
            hidLevel = hidLevel,
            hidNum = hidNum,
            lawBasis = "",
        )
    }
}
