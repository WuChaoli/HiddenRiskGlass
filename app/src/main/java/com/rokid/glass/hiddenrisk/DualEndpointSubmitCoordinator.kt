package com.rokid.glass.hiddenrisk

/**
 * 聚合同一次主备双端点提交结果。
 * 等两个端点都返回后，再统一判定整体结果，避免串行门控导致备链路不触发。
 */
internal class DualEndpointSubmitCoordinator(
    labels: List<String>,
    private val onComplete: (Map<String, RetryOutcome>) -> Unit,
) {
    private val expectedLabels = labels.toList()
    private val outcomes = linkedMapOf<String, RetryOutcome>()
    private var completed = false

    @Synchronized
    fun record(
        label: String,
        outcome: RetryOutcome,
    ) {
        if (completed || label !in expectedLabels || outcomes.containsKey(label)) {
            return
        }
        outcomes[label] = outcome
        if (outcomes.size != expectedLabels.size) {
            return
        }
        completed = true
        onComplete(expectedLabels.associateWith { key -> outcomes.getValue(key) })
    }
}
