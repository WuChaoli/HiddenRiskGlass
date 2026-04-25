package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalHazardUploadItemBuilderTest {

    @Test
    fun build_dedupesNonBlankHidNumAndKeepsFirstMatch() {
        val items = LocalHazardUploadItemBuilder.build(
            hazardContent(
                hazards = listOf(
                    hazardItem(title = "燃气灶", description = "首条", hidNum = "HZ-001"),
                    hazardItem(title = "液化石油气瓶", description = "重复应丢弃", hidNum = "HZ-001"),
                    hazardItem(title = "电箱", description = "保留", hidNum = "HZ-002"),
                ),
            ),
        )

        assertEquals(2, items.size)
        assertEquals("1", items[0].indexNum)
        assertEquals("首条", items[0].descrip)
        assertEquals("整改-首条", items[0].advice)
        assertEquals("HZ-001", items[0].hidNum)
        assertEquals("2", items[1].indexNum)
        assertEquals("整改-保留", items[1].advice)
        assertEquals("HZ-002", items[1].hidNum)
    }

    @Test
    fun build_keepsBlankHidNumItems() {
        val items = LocalHazardUploadItemBuilder.build(
            hazardContent(
                hazards = listOf(
                    hazardItem(title = "燃气灶", description = "空编号1", hidNum = ""),
                    hazardItem(title = "液化石油气瓶", description = "空编号2", hidNum = "   "),
                    hazardItem(title = "电箱", description = "唯一编号", hidNum = "HZ-003"),
                ),
            ),
        )

        assertEquals(3, items.size)
        assertEquals("1", items[0].indexNum)
        assertEquals("", items[0].hidNum)
        assertEquals("2", items[1].indexNum)
        assertEquals("   ", items[1].hidNum)
        assertEquals("3", items[2].indexNum)
        assertEquals("HZ-003", items[2].hidNum)
    }

    @Test
    fun build_preservesOrderWhileSkippingDuplicateHidNum() {
        val items = LocalHazardUploadItemBuilder.build(
            hazardContent(
                hazards = listOf(
                    hazardItem(title = "燃气灶", description = "A", hidNum = "HZ-001"),
                    hazardItem(title = "液化石油气瓶", description = "B", hidNum = ""),
                    hazardItem(title = "电箱", description = "C", hidNum = "HZ-001"),
                    hazardItem(title = "消防通道", description = "D", hidNum = "HZ-004"),
                ),
            ),
        )

        assertEquals(listOf("A", "B", "D"), items.map { it.descrip })
        assertEquals(listOf("整改-A", "整改-B", "整改-D"), items.map { it.advice })
        assertEquals(listOf("1", "2", "3"), items.map { it.indexNum })
        assertEquals(listOf("HZ-001", "", "HZ-004"), items.map { it.hidNum })
    }

    private fun hazardContent(hazards: List<ResolvedHazardItem>): ResolvedHazardContent {
        val primary = hazards.first()
        return ResolvedHazardContent(
            source = HazardSource.LOCAL,
            description = primary.description,
            advice = primary.advice,
            uploadAdvice = primary.uploadAdvice,
            hidLevel = primary.hidLevel,
            hidNum = primary.hidNum,
            lawBasis = primary.lawBasis,
            displayTitle = primary.displayTitle,
            jpegBytes = byteArrayOf(1),
            hazards = hazards,
        )
    }

    private fun hazardItem(
        title: String,
        description: String,
        hidNum: String,
    ): ResolvedHazardItem {
        return ResolvedHazardItem(
            displayTitle = title,
            description = description,
            advice = "建议-$description",
            uploadAdvice = "整改-$description",
            hidLevel = "1",
            hidNum = hidNum,
            lawBasis = "依据-$description",
        )
    }
}
