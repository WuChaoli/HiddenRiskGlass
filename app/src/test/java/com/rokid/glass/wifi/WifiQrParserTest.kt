package com.rokid.glass.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiQrParserTest {
    @Test
    fun parse_wpaNetwork() {
        val result = WifiQrParser.parse("WIFI:T:WPA;S:Office;P:password123;H:false;;")

        assertEquals(
            WifiQrParseResult.Success(
                WifiQrPayload("Office", "password123", WifiQrPayload.SecurityType.WPA_PSK, false),
            ),
            result,
        )
    }

    @Test
    fun parse_openAndWpa3Networks() {
        assertEquals(
            WifiQrParseResult.Success(
                WifiQrPayload("Guest", null, WifiQrPayload.SecurityType.OPEN, true),
            ),
            WifiQrParser.parse("WIFI:T:nopass;S:Guest;H:true;;"),
        )
        assertEquals(
            WifiQrParseResult.Success(
                WifiQrPayload("Secure", "password123", WifiQrPayload.SecurityType.WPA3_SAE, false),
            ),
            WifiQrParser.parse("WIFI:T:SAE;S:Secure;P:password123;;"),
        )
    }

    @Test
    fun parse_unescapesReservedCharacters() {
        val result = WifiQrParser.parse("WIFI:T:WPA;S:Office\\;East;P:pass\\:word1;;")

        assertEquals("Office;East", (result as WifiQrParseResult.Success).payload.ssid)
        assertEquals("pass:word1", result.payload.password)
    }

    @Test
    fun parse_rejectsMissingSsidInvalidPasswordAndUnknownSecurity() {
        assertEquals(
            WifiQrParseResult.Error(WifiQrParseResult.Reason.MISSING_SSID),
            WifiQrParser.parse("WIFI:T:WPA;P:password123;;"),
        )
        assertEquals(
            WifiQrParseResult.Error(WifiQrParseResult.Reason.INVALID_PASSWORD),
            WifiQrParser.parse("WIFI:T:WPA;S:Office;P:short;;"),
        )
        assertEquals(
            WifiQrParseResult.Error(WifiQrParseResult.Reason.UNSUPPORTED_SECURITY_TYPE),
            WifiQrParser.parse("WIFI:T:EAP;S:Office;P:password123;;"),
        )
        assertTrue(WifiQrParser.parse("not-wifi") is WifiQrParseResult.Error)
    }
}
