package com.rokid.glass.hiddenrisk

import com.google.gson.Gson
import com.rokid.glass.config.SaveResultApiConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHazardPushApiProtocolTest {

    @Test
    fun buildPrimaryRequestUrl_appendsSmartGlassesPathWhenMissing() {
        val url = LocalHazardPushApiProtocol.buildPrimaryRequestUrl("http://example.com/hxy/apis/third")

        assertEquals(
            "http://example.com/hxy/apis/third/smartGlasses/pushHidDanger",
            url,
        )
    }

    @Test
    fun buildPrimaryRequestUrl_reusesExistingSmartGlassesPath() {
        val url = LocalHazardPushApiProtocol.buildPrimaryRequestUrl("http://example.com/hxy/apis/third/smartGlasses")

        assertEquals(
            "http://example.com/hxy/apis/third/smartGlasses/pushHidDanger",
            url,
        )
    }

    @Test
    fun buildBackupRequestUrl_usesBackupSaveEndpoint() {
        val url = LocalHazardPushApiProtocol.buildBackupRequestUrl(
            SaveResultApiConfig(backupBaseUrl = "http://183.147.142.133:7443/"),
        )

        assertEquals(
            "http://183.147.142.133:7443/hxy/apis/hazardCheckRecord/saveHazard",
            url,
        )
    }

    @Test
    fun parseResponseBody_acceptsPrimarySuccessCode() {
        val result = LocalHazardPushApiProtocol.parseResponseBody("""{"code":0,"msg":"success"}""")

        assertTrue(result.success)
        assertEquals(null, result.message)
    }

    @Test
    fun parseResponseBody_acceptsBackupSuccessCode() {
        val result = LocalHazardPushApiProtocol.parseResponseBody("""{"code":200,"message":"识别成功"}""")

        assertTrue(result.success)
        assertEquals(null, result.message)
    }

    @Test
    fun parseResponseBody_usesBusinessFailureMessage() {
        val result = LocalHazardPushApiProtocol.parseResponseBody("""{"code":1,"message":"保存失败"}""")

        assertFalse(result.success)
        assertEquals("保存失败", result.message)
    }

    @Test
    fun parseResponseBody_fallsBackForMalformedBody() {
        val result = LocalHazardPushApiProtocol.parseResponseBody("not-json")

        assertFalse(result.success)
        assertEquals("本地隐患保存失败，请重试", result.message)
    }

    @Test
    fun buildRequestBodyJson_includesLawBasisAndEmptyStrings() {
        val json = LocalHazardPushApiProtocol.buildRequestBodyJson(
            gson = Gson(),
            authCode = "auth",
            objectId = "obj",
            userId = "user",
            customParam = "extra",
            jpegBytes = byteArrayOf(1, 2, 3),
            hidDanger = listOf(
                LocalHazardPushService.HidDangerItem(
                    indexNum = "1",
                    descrip = "",
                    advice = "",
                    hidNum = "",
                    hidLevel = "",
                    lawBasis = "",
                ),
            ),
        )

        assertTrue(json.contains("\"lawBasis\":\"\""))
        assertTrue(json.contains("\"descrip\":\"\""))
        assertTrue(json.contains("\"advice\":\"\""))
        assertTrue(json.contains("\"hidNum\":\"\""))
        assertTrue(json.contains("\"hidLevel\":\"\""))
    }
}
