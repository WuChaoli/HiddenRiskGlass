package com.rokid.glass.hiddenrisk

/**
 * 生成用于巡检链路后台交互的会话 ID。
 */
object InspectionBackendSessionId {

    fun create(snCode: String, prefix: String? = null): String {
        val sanitizedPrefix = prefix?.takeIf { it.isNotBlank() }?.plus("_").orEmpty()
        return "${sanitizedPrefix}${System.currentTimeMillis()}_$snCode"
    }
}
