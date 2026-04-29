package com.rokid.glass.utils

data class WifiQrPayload(
    val ssid: String,
    val password: String?,
    val security: SecurityType,
    val hidden: Boolean,
) {
    enum class SecurityType {
        OPEN,
        WPA,
        WEP,
    }
}

object WifiQrParser {

    fun parse(rawValue: String?): WifiQrPayload? {
        if (rawValue.isNullOrBlank() || !rawValue.startsWith("WIFI:")) {
            return null
        }
        val pairs = mutableMapOf<String, String>()
        splitEscaped(rawValue.removePrefix("WIFI:"), ';')
            .filter { it.isNotBlank() }
            .forEach { entry ->
                val separatorIndex = findUnescaped(entry, ':')
                if (separatorIndex <= 0) {
                    return@forEach
                }
                val key = entry.substring(0, separatorIndex)
                val value = unescape(entry.substring(separatorIndex + 1))
                pairs[key] = value
            }

        val ssid = pairs["S"]?.takeIf { it.isNotBlank() } ?: return null
        val security = when (pairs["T"]?.uppercase()) {
            null, "", "NOPASS" -> WifiQrPayload.SecurityType.OPEN
            "WPA", "WPA2", "WPA/WPA2" -> WifiQrPayload.SecurityType.WPA
            "WEP" -> WifiQrPayload.SecurityType.WEP
            else -> return null
        }
        val password = pairs["P"]?.takeIf { it.isNotEmpty() }
        if (security != WifiQrPayload.SecurityType.OPEN && password.isNullOrEmpty()) {
            return null
        }
        return WifiQrPayload(
            ssid = ssid,
            password = password,
            security = security,
            hidden = pairs["H"]?.equals("true", ignoreCase = true) == true,
        )
    }

    private fun splitEscaped(text: String, separator: Char): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var escaping = false
        text.forEach { char ->
            when {
                escaping -> {
                    current.append(char)
                    escaping = false
                }
                char == '\\' -> escaping = true
                char == separator -> {
                    result += current.toString()
                    current.setLength(0)
                }
                else -> current.append(char)
            }
        }
        if (escaping) {
            current.append('\\')
        }
        result += current.toString()
        return result
    }

    private fun findUnescaped(text: String, target: Char): Int {
        var escaping = false
        text.forEachIndexed { index, char ->
            when {
                escaping -> escaping = false
                char == '\\' -> escaping = true
                char == target -> return index
            }
        }
        return -1
    }

    private fun unescape(text: String): String {
        val result = StringBuilder()
        var escaping = false
        text.forEach { char ->
            when {
                escaping -> {
                    result.append(char)
                    escaping = false
                }
                char == '\\' -> escaping = true
                else -> result.append(char)
            }
        }
        if (escaping) {
            result.append('\\')
        }
        return result.toString()
    }
}
