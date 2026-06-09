package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SuggestionChecksProtocolTest {
    @Test
    fun parseContent_returnsContentFromSuccessJson() {
        val body = """
            {"code":0,"msg":"success","task_id":"1","type":"sug_checks","content":"1、建议检查。\n2、立即整改。","inference_result":[],"time":0.001}
        """.trimIndent()

        assertEquals(
            "1、建议检查。\n2、立即整改。",
            SuggestionChecksProtocol.parseContent(body),
        )
    }

    @Test
    fun parseContent_rejectsInvalidJson() {
        assertThrows(IllegalStateException::class.java) {
            SuggestionChecksProtocol.parseContent("{")
        }
    }

    @Test
    fun parseContent_returnsBlankWhenContentMissing() {
        assertEquals(
            "",
            SuggestionChecksProtocol.parseContent("""{"code":0,"msg":"success"}"""),
        )
    }

    @Test
    fun parseContent_returnsBlankWhenContentNull() {
        assertEquals(
            "",
            SuggestionChecksProtocol.parseContent("""{"code":0,"content":null}"""),
        )
    }

    @Test
    fun parseContent_returnsBlankWhenContentEmpty() {
        assertEquals(
            "",
            SuggestionChecksProtocol.parseContent("""{"code":0,"content":""}"""),
        )
    }

    @Test
    fun parseContent_returnsBlankWhenContentWhitespace() {
        assertEquals(
            "",
            SuggestionChecksProtocol.parseContent("""{"code":0,"content":"   "}"""),
        )
    }
}
