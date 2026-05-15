package com.rokid.glass.input

/**
 * 自动睡眠纯状态机。
 * 统一输出显式状态；睡眠监测只由眼镜摘下/重新佩戴广播驱动。
 */
class AutoSleepStateMachine(
    private val config: Config,
) {
    data class Config(
        val wakeDurationMs: Long = 3_000L,
    )

    enum class State {
        WAKING,
        SLEEP_WARNING,
        TO_SLEEP,
        SLEEPING,
        WAKE,
    }

    enum class TriggerReason {
        GLASSES_REMOVED,
        MANUAL,
    }

    data class Snapshot(
        val state: State,
        val enteredAtMs: Long,
        val remainingMs: Long?,
        val config: Config,
        val triggerReason: TriggerReason,
    )

    private var enabled = false
    private var currentState = State.WAKING
    private var enteredAtMs = 0L
    private var triggerReason = TriggerReason.MANUAL

    fun setEnabled(enabled: Boolean, nowMillis: Long): Snapshot? {
        this.enabled = enabled
        return if (enabled) {
            transitionTo(State.WAKING, nowMillis, TriggerReason.MANUAL)
        } else {
            null
        }
    }

    fun onGlassesRemoved(nowMillis: Long): Snapshot? {
        if (!enabled) {
            return null
        }
        return transitionTo(State.SLEEP_WARNING, nowMillis, TriggerReason.GLASSES_REMOVED)
    }

    fun onGlassesWorn(nowMillis: Long): List<Snapshot> {
        if (!enabled) {
            return emptyList()
        }
        return when (currentState) {
            State.SLEEP_WARNING, State.SLEEPING -> listOfNotNull(
                transitionTo(State.WAKE, nowMillis, TriggerReason.MANUAL),
            )
            else -> emptyList()
        }
    }

    fun tick(nowMillis: Long): List<Snapshot> {
        if (!enabled) {
            return emptyList()
        }
        return when (currentState) {
            State.WAKE -> {
                if (config.wakeDurationMs > 0L && nowMillis - enteredAtMs >= config.wakeDurationMs) {
                    listOfNotNull(transitionTo(State.WAKING, nowMillis, TriggerReason.MANUAL))
                } else {
                    emptyList()
                }
            }
            else -> emptyList()
        }
    }

    fun markSleepHandled(nowMillis: Long): Snapshot? {
        if (!enabled || currentState != State.TO_SLEEP) {
            return null
        }
        return transitionTo(State.SLEEPING, nowMillis, triggerReason)
    }

    fun currentSnapshot(nowMillis: Long): Snapshot? {
        if (!enabled) {
            return null
        }
        return Snapshot(
            state = currentState,
            enteredAtMs = enteredAtMs,
            remainingMs = remainingMs(nowMillis, currentState, enteredAtMs),
            config = config,
            triggerReason = triggerReason,
        )
    }

    fun isPromptVisible(nowMillis: Long): Boolean {
        return currentSnapshot(nowMillis)?.state == State.SLEEP_WARNING
    }

    private fun transitionTo(state: State, nowMillis: Long, reason: TriggerReason): Snapshot {
        currentState = state
        enteredAtMs = nowMillis
        triggerReason = reason
        return Snapshot(
            state = currentState,
            enteredAtMs = enteredAtMs,
            remainingMs = remainingMs(nowMillis, currentState, enteredAtMs),
            config = config,
            triggerReason = triggerReason,
        )
    }

    private fun remainingMs(nowMillis: Long, state: State, enteredAtMs: Long): Long? {
        val duration = when (state) {
            State.WAKE -> config.wakeDurationMs
            else -> return null
        }
        return (duration - (nowMillis - enteredAtMs)).coerceAtLeast(0L)
    }
}
