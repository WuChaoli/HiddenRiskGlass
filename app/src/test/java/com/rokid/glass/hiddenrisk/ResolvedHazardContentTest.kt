package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
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
}
