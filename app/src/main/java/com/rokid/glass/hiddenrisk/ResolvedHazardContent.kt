package com.rokid.glass.hiddenrisk

/**
 * 可直接展示并可进入保存链路的隐患结果。
 */
data class ResolvedHazardContent(
    val source: HazardSource,
    val description: String,
    val advice: String,
    val hidLevel: String,
    val hidNum: String,
    val lawBasis: String,
    val displayTitle: String,
    val jpegBytes: ByteArray,
) {
    fun displayDescription(): String {
        return buildList {
            description.trim().takeIf { it.isNotBlank() }?.let { add("隐患描述：$it") }
            hidLevel.trim().takeIf { it.isNotBlank() }?.let { add("隐患等级：${levelLabel(it)}") }
            lawBasis.trim().takeIf { it.isNotBlank() }?.let { add("主要依据：$it") }
            hidNum.trim().takeIf { it.isNotBlank() }?.let { add("隐患编号：$it") }
        }.joinToString("\n")
    }

    fun displayAdvice(): String {
        val adviceText = advice.trim()
        return if (adviceText.isBlank()) "" else "整改建议：\n$adviceText"
    }

    companion object {
        fun levelCode(labelOrCode: String): String {
            return when (labelOrCode.trim()) {
                "1", "一般隐患" -> "1"
                "2", "重大隐患" -> "2"
                "3", "重点问题" -> "3"
                else -> ""
            }
        }

        fun levelLabel(codeOrLabel: String): String {
            return when (codeOrLabel.trim()) {
                "1" -> "一般隐患"
                "2" -> "重大隐患"
                "3" -> "重点问题"
                else -> codeOrLabel.trim()
            }
        }
    }
}

enum class HazardSource {
    LOCAL,
    ONLINE,
}
