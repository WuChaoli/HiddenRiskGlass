package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineHazardAdviceFormatterTest {

    @Test
    fun format_addsIntroBeforeOnlineAdviceText() {
        val formatted = OnlineHazardAdviceFormatter.format(" 1. 立即整改\n2. 定期复查 ")

        assertEquals(
            "基于上述隐患，建议您重点关注以下问题：\n1. 立即整改\n2. 定期复查",
            formatted,
        )
    }

    @Test
    fun format_returnsBlankForBlankAdviceText() {
        assertEquals("", OnlineHazardAdviceFormatter.format("   "))
    }
}
