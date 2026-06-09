package com.rokid.glass.wifi

data class WifiQrPayload(
    val ssid: String,
    val password: String?,
    val securityType: SecurityType,
    val hiddenSsid: Boolean,
) {
    enum class SecurityType {
        OPEN,
        WEP,
        WPA_PSK,
        WPA3_SAE,
    }
}

sealed interface WifiQrParseResult {
    data class Success(val payload: WifiQrPayload) : WifiQrParseResult
    data class Error(val reason: Reason) : WifiQrParseResult

    enum class Reason {
        INVALID_FORMAT,
        MISSING_SSID,
        UNSUPPORTED_SECURITY_TYPE,
        INVALID_PASSWORD,
    }
}

object WifiQrParser {
    private val hexPskRegex = Regex("^[0-9a-fA-F]{64}$")

    fun parse(rawContent: String): WifiQrParseResult {
        if (!rawContent.startsWith("WIFI:") || !rawContent.endsWith(";;")) {
            return WifiQrParseResult.Error(WifiQrParseResult.Reason.INVALID_FORMAT)
        }

        val fields = parseFields(rawContent.substring(5, rawContent.length - 2))
            ?: return WifiQrParseResult.Error(WifiQrParseResult.Reason.INVALID_FORMAT)
        val ssid = fields["S"]?.takeIf { it.isNotBlank() }
            ?: return WifiQrParseResult.Error(WifiQrParseResult.Reason.MISSING_SSID)
        val securityType = when (fields["T"]?.trim()?.uppercase().orEmpty()) {
            "", "NOPASS" -> WifiQrPayload.SecurityType.OPEN
            "WEP" -> WifiQrPayload.SecurityType.WEP
            "WPA", "WPA2", "WPA/WPA2", "WPA2-PSK" -> WifiQrPayload.SecurityType.WPA_PSK
            "SAE", "WPA3", "WPA3-SAE" -> WifiQrPayload.SecurityType.WPA3_SAE
            else -> return WifiQrParseResult.Error(WifiQrParseResult.Reason.UNSUPPORTED_SECURITY_TYPE)
        }
        val password = fields["P"]?.takeIf { it.isNotEmpty() }
        if (
            securityType != WifiQrPayload.SecurityType.OPEN &&
            (password == null || !isValidPassword(securityType, password))
        ) {
            return WifiQrParseResult.Error(WifiQrParseResult.Reason.INVALID_PASSWORD)
        }

        return WifiQrParseResult.Success(
            WifiQrPayload(
                ssid = ssid,
                password = password,
                securityType = securityType,
                hiddenSsid = fields["H"]?.equals("true", ignoreCase = true) == true,
            ),
        )
    }

    private fun isValidPassword(securityType: WifiQrPayload.SecurityType, password: String): Boolean {
        if (securityType == WifiQrPayload.SecurityType.WEP) {
            return password.isNotEmpty()
        }
        return password.length in 8..63 || hexPskRegex.matches(password)
    }

    private fun parseFields(content: String): Map<String, String>? {
        val fields = linkedMapOf<String, String>()
        val token = StringBuilder()
        var escaped = false
        val tokens = mutableListOf<String>()
        content.forEach { char ->
            when {
                escaped -> {
                    token.append(char)
                    escaped = false
                }
                char == '\\' -> escaped = true
                char == ';' -> {
                    tokens += token.toString()
                    token.clear()
                }
                else -> token.append(char)
            }
        }
        if (escaped) return null
        tokens += token.toString()

        tokens.filter { it.isNotEmpty() }.forEach { field ->
            val separator = field.indexOf(':')
            if (separator <= 0) return null
            fields[field.substring(0, separator)] = field.substring(separator + 1)
        }
        return fields
    }
}
