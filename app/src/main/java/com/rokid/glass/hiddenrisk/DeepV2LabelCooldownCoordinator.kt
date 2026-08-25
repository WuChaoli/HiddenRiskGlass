package com.rokid.glass.hiddenrisk

internal class DeepV2LabelCooldownCoordinator(
    private val registry: LabelCooldownRegistry,
) {
    private var activeLabels: List<String> = emptyList()
    private var pendingLabels: List<String> = emptyList()

    fun onRequestStarted(labels: List<String>) {
        activeLabels = normalize(labels)
    }

    fun onNoHazardReturnedToAuto(nowElapsedMs: Long) {
        registry.mark(activeLabels, nowElapsedMs)
        activeLabels = emptyList()
    }

    fun onHazardReturned() {
        pendingLabels = activeLabels
        activeLabels = emptyList()
    }

    fun onReturnedToAuto(nowElapsedMs: Long) {
        registry.mark(pendingLabels, nowElapsedMs)
        pendingLabels = emptyList()
    }

    fun onRequestFailedOrCancelled() {
        activeLabels = emptyList()
    }

    fun clear() {
        activeLabels = emptyList()
        pendingLabels = emptyList()
        registry.clear()
    }

    private fun normalize(labels: List<String>): List<String> =
        labels.map(String::trim).filter(String::isNotBlank).distinct()
}
