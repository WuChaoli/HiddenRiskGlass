package com.rokid.glass.hiddenrisk

internal class DeepV2AutoRequestState {
    private data class Active(
        val requestId: Long,
        val epoch: Long,
        val image: DeepV2ImagePayload?,
    )

    private var nextRequestId = 1L
    private var active: Active? = null

    val isActive: Boolean
        get() = active != null

    fun begin(epoch: Long): Long? {
        if (active != null) return null
        val requestId = nextRequestId++
        active = Active(requestId, epoch, null)
        return requestId
    }

    fun attachImage(
        requestId: Long,
        epoch: Long,
        image: DeepV2ImagePayload,
    ): Boolean {
        val current = active ?: return false
        if (current.requestId != requestId || current.epoch != epoch) return false
        active = current.copy(image = image)
        return true
    }

    fun acceptTerminal(requestId: Long, epoch: Long): DeepV2ImagePayload? {
        val current = active ?: return null
        if (current.requestId != requestId || current.epoch != epoch) return null
        val image = current.image ?: return null
        active = null
        return image
    }

    fun fail(requestId: Long, epoch: Long): Boolean {
        val current = active ?: return false
        if (current.requestId != requestId || current.epoch != epoch) return false
        active = null
        return true
    }

    fun cancel() {
        active = null
    }
}
