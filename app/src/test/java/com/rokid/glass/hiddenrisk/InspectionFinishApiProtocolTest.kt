package com.rokid.glass.hiddenrisk

import com.rokid.glass.config.SaveResultApiConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionFinishApiProtocolTest {

    @Test
    fun buildRequestUrl_appendsSmartGlassesPathWhenMissing() {
        val url = InspectionFinishApiProtocol.buildPrimaryRequestUrl("http://example.com/hxy/apis/third")

        assertEquals(
            "http://example.com/hxy/apis/third/smartGlasses/pushHidDangerEnd",
            url,
        )
    }

    @Test
    fun buildRequestUrl_reusesExistingSmartGlassesPath() {
        val url = InspectionFinishApiProtocol.buildPrimaryRequestUrl("http://example.com/hxy/apis/third/smartGlasses")

        assertEquals(
            "http://example.com/hxy/apis/third/smartGlasses/pushHidDangerEnd",
            url,
        )
    }

    @Test
    fun buildBackupRequestUrl_usesBackupFinishEndpoint() {
        val url = InspectionFinishApiProtocol.buildBackupRequestUrl(
            SaveResultApiConfig(backupBaseUrl = "http://183.147.142.133:7443/"),
        )

        assertEquals(
            "http://183.147.142.133:7443/hxy/apis/hazardCheckRecord/hazardIsEnd",
            url,
        )
    }

    @Test
    fun parseResponseBody_acceptsSuccessCode() {
        val result = InspectionFinishApiProtocol.parseResponseBody("""{"code":0,"message":"识别成功"}""")

        assertTrue(result.success)
        assertEquals(null, result.message)
    }

    @Test
    fun parseResponseBody_acceptsBackupSuccessCode() {
        val result = InspectionFinishApiProtocol.parseResponseBody("""{"code":200,"msg":"success"}""")

        assertTrue(result.success)
        assertEquals(null, result.message)
    }

    @Test
    fun parseResponseBody_usesBusinessFailureMessage() {
        val result = InspectionFinishApiProtocol.parseResponseBody("""{"code":1,"message":"结束失败"}""")

        assertFalse(result.success)
        assertEquals("结束失败", result.message)
    }

    @Test
    fun parseResponseBody_fallsBackForMalformedBody() {
        val result = InspectionFinishApiProtocol.parseResponseBody("not-json")

        assertFalse(result.success)
        assertEquals(InspectionFinishApiProtocol.DEFAULT_FAILURE_MESSAGE, result.message)
    }

}
