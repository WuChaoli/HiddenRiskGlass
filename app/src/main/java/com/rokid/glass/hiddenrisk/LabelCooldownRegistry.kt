package com.rokid.glass.hiddenrisk

internal class LabelCooldownRegistry(
    private val cooldownMs: Long,
) {
    private val cooldownUntilMs = linkedMapOf<String, Long>()

    fun isCooling(label: String, nowElapsedMs: Long): Boolean {
        val normalized = label.trim()
        if (normalized.isBlank()) return false
        return (cooldownUntilMs[normalized] ?: return false) > nowElapsedMs
    }

    fun coolingLabels(labels: List<String>, nowElapsedMs: Long): List<String> =
        labels.map(String::trim).filter(String::isNotBlank).distinct().filter {
            isCooling(it, nowElapsedMs)
        }

    fun mark(labels: List<String>, nowElapsedMs: Long) {
        prune(nowElapsedMs)
        val untilMs = nowElapsedMs + cooldownMs
        labels.map(String::trim).filter(String::isNotBlank).distinct().forEach {
            cooldownUntilMs[it] = untilMs
        }
    }

    fun clear() = cooldownUntilMs.clear()

    private fun prune(nowElapsedMs: Long) {
        cooldownUntilMs.entries.removeAll { it.value <= nowElapsedMs }
    }
}
