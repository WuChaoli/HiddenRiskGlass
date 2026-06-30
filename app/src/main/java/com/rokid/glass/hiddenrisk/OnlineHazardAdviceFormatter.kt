package com.rokid.glass.hiddenrisk

/**
 * 在线整改建议页展示格式。
 */
internal object OnlineHazardAdviceFormatter {

    private const val PREFIX = "基于上述隐患，建议您重点关注以下问题："

    fun format(adviceText: String): String {
        val normalizedAdvice = adviceText.trim()
        if (normalizedAdvice.isBlank()) {
            return ""
        }
        return "$PREFIX\n$normalizedAdvice"
    }
}
