package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MayHazardDeepVerifyProtocolTest {
    @Test
    fun parseHasHazardAnswer_parsesTrueResult() {
        val body = """{"code":200,"msg":"success","data":{"answer":"{\"has_hazard\":true}"}}"""

        assertTrue(MayHazardDeepVerifyProtocol.parseHasHazardAnswer(body))
    }

    @Test
    fun parseHasHazardAnswer_parsesFalseResult() {
        val body = """{"code":200,"msg":"success","data":{"answer":"{\"has_hazard\":false}"}}"""

        assertFalse(MayHazardDeepVerifyProtocol.parseHasHazardAnswer(body))
    }

    @Test(expected = IllegalStateException::class)
    fun parseHasHazardAnswer_rejectsMalformedAnswer() {
        val body = """{"code":200,"msg":"success","data":{"answer":"not-json"}}"""

        MayHazardDeepVerifyProtocol.parseHasHazardAnswer(body)
    }
}
