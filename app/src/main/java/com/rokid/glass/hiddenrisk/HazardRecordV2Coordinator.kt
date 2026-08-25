package com.rokid.glass.hiddenrisk

internal class HazardRecordV2Coordinator {
    private var activeRequestId: Long? = null

    fun begin(requestId: Long) {
        activeRequestId = requestId
    }

    fun accept(requestId: Long): Boolean = activeRequestId == requestId

    fun complete(requestId: Long): Boolean {
        if (!accept(requestId)) return false
        activeRequestId = null
        return true
    }

    fun cancel() {
        activeRequestId = null
    }
}
