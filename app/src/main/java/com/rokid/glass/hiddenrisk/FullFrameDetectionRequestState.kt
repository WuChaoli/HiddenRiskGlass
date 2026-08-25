package com.rokid.glass.hiddenrisk

internal class FullFrameDetectionRequestState(
    private val cadenceMs: Long = 0L,
) {
    private var nextRequestId = 1L
    private var activeRequestId: Long? = null
    private var lastStartedAtMs: Long? = null

    fun begin(nowMs: Long): Long? {
        if (activeRequestId != null) return null
        val previousStart = lastStartedAtMs
        if (previousStart != null && nowMs - previousStart < cadenceMs) return null
        return nextRequestId++.also { requestId ->
            activeRequestId = requestId
            lastStartedAtMs = nowMs
        }
    }

    fun acceptSuccess(requestId: Long): Boolean = finish(requestId)

    fun acceptFailure(requestId: Long): Boolean = finish(requestId)

    fun cancel() {
        activeRequestId = null
    }

    private fun finish(requestId: Long): Boolean {
        if (activeRequestId != requestId) return false
        activeRequestId = null
        return true
    }
}
