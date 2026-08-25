package com.rokid.glass.hiddenrisk

internal enum class DeepV2AutoDecision {
    NOT_QUALIFIED,
    ALREADY_ACTIVE,
    IMAGE_UNAVAILABLE,
    STARTED,
}

internal class DeepV2AutoCoordinator(
    private val requestState: DeepV2AutoRequestState = DeepV2AutoRequestState(),
) {
    val isActive: Boolean
        get() = requestState.isActive

    fun onAutoResponse(
        epoch: Long,
        shouldTrigger: Boolean,
        buildImage: () -> DeepV2ImagePayload?,
        startRequest: (requestId: Long, image: DeepV2ImagePayload) -> Unit,
    ): DeepV2AutoDecision {
        if (!shouldTrigger) return DeepV2AutoDecision.NOT_QUALIFIED
        val requestId = requestState.begin(epoch) ?: return DeepV2AutoDecision.ALREADY_ACTIVE
        val image = buildImage()
        if (image == null) {
            requestState.fail(requestId, epoch)
            return DeepV2AutoDecision.IMAGE_UNAVAILABLE
        }
        if (!requestState.attachImage(requestId, epoch, image)) {
            return DeepV2AutoDecision.ALREADY_ACTIVE
        }
        startRequest(requestId, image)
        return DeepV2AutoDecision.STARTED
    }

    fun onSuccess(requestId: Long, epoch: Long): DeepV2ImagePayload? =
        requestState.acceptTerminal(requestId, epoch)

    fun onFailure(requestId: Long, epoch: Long): Boolean = requestState.fail(requestId, epoch)

    fun cancel() = requestState.cancel()
}
