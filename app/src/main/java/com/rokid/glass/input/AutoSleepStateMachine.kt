package com.rokid.glass.input

/**
 * 自动睡眠纯状态机。
 * 统一输出显式状态与剩余时间，页面只消费状态快照，不再自行维护倒计时。
 */
class AutoSleepStateMachine(
    private val config: Config,
) {
    data class Config(
        val wakingDurationMs: Long,
        val sleepWarningDurationMs: Long,
    )

    enum class State {
        WAKING,
        SLEEP_WARNING,
        TO_SLEEP,
        SLEEPING,
        WAKE,
    }

    enum class UserActivitySource {
        TOUCH,
        VOICE,
        HEAD_MOTION,
    }

    enum class TriggerReason {
        IDLE,
        GLASSES_REMOVED,
        USER_ACTIVITY,
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

    fun onIdleQualified(nowMillis: Long): Snapshot? {
        if (!enabled || currentState != State.WAKING || config.wakingDurationMs <= 0L) {
            return null
        }
        return transitionTo(State.SLEEP_WARNING, nowMillis, TriggerReason.IDLE)
    }

    fun onGlassesRemoved(nowMillis: Long): Snapshot? {
        if (!enabled || currentState == State.SLEEP_WARNING && triggerReason == TriggerReason.GLASSES_REMOVED) {
            return currentSnapshot(nowMillis)
        }
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
            State.SLEEP_WARNING, State.TO_SLEEP, State.SLEEPING -> listOfNotNull(
                transitionTo(State.WAKE, nowMillis, TriggerReason.USER_ACTIVITY),
                transitionTo(State.WAKING, nowMillis, TriggerReason.USER_ACTIVITY),
            )
            else -> emptyList()
        }
    }

    fun onUserActivity(source: UserActivitySource, nowMillis: Long): List<Snapshot> {
        if (!enabled) {
            return emptyList()
        }
        return when (currentState) {
            State.WAKING -> listOfNotNull(transitionTo(State.WAKING, nowMillis, TriggerReason.USER_ACTIVITY))
            State.SLEEP_WARNING -> {
                if (triggerReason == TriggerReason.GLASSES_REMOVED) {
                    emptyList()
                } else {
                    listOfNotNull(
                        transitionTo(State.WAKE, nowMillis, TriggerReason.USER_ACTIVITY),
                        transitionTo(State.WAKING, nowMillis, TriggerReason.USER_ACTIVITY),
                    )
                }
            }
            else -> emptyList()
        }
    }

    fun tick(nowMillis: Long): List<Snapshot> {
        if (!enabled) {
            return emptyList()
        }
        return when (currentState) {
            State.WAKING -> {
                if (config.wakingDurationMs > 0L && nowMillis - enteredAtMs >= config.wakingDurationMs) {
                    listOfNotNull(transitionTo(State.SLEEP_WARNING, nowMillis, TriggerReason.IDLE))
                } else {
                    emptyList()
                }
            }
            State.SLEEP_WARNING -> {
                if (config.sleepWarningDurationMs > 0L && nowMillis - enteredAtMs >= config.sleepWarningDurationMs) {
                    listOfNotNull(transitionTo(State.TO_SLEEP, nowMillis, triggerReason))
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
            State.WAKING -> config.wakingDurationMs
            State.SLEEP_WARNING -> config.sleepWarningDurationMs
            else -> return null
        }
        return (duration - (nowMillis - enteredAtMs)).coerceAtLeast(0L)
    }
}
