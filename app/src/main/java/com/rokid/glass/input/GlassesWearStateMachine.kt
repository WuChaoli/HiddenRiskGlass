package com.rokid.glass.input

/**
 * 佩戴检测纯状态机。
 * WAKE 表示戴回后的动态恢复窗口，仅在页面确认检测输入就绪后回到 ACTIVE。
 */
class GlassesWearStateMachine {
    enum class State {
        ACTIVE,
        SLEEP,
        WAKE,
    }

    enum class TriggerReason {
        GLASSES_REMOVED,
        GLASSES_WORN,
        RECOVERY_READY,
        ENABLED,
    }

    data class Snapshot(
        val state: State,
        val enteredAtMs: Long,
        val triggerReason: TriggerReason,
    )

    private var enabled = false
    private var currentState = State.ACTIVE
    private var enteredAtMs = 0L
    private var triggerReason = TriggerReason.ENABLED

    fun setEnabled(enabled: Boolean, nowMillis: Long): Snapshot? {
        this.enabled = enabled
        return if (enabled) {
            transitionTo(State.ACTIVE, nowMillis, TriggerReason.ENABLED)
        } else {
            null
        }
    }

    fun onGlassesRemoved(nowMillis: Long): Snapshot? {
        if (!enabled) {
            return null
        }
        return transitionTo(State.SLEEP, nowMillis, TriggerReason.GLASSES_REMOVED)
    }

    fun onGlassesWorn(nowMillis: Long): Snapshot? {
        if (!enabled) {
            return null
        }
        return if (currentState == State.SLEEP) {
            transitionTo(State.WAKE, nowMillis, TriggerReason.GLASSES_WORN)
        } else {
            null
        }
    }

    fun onRecoveryReady(nowMillis: Long): Snapshot? {
        if (!enabled || currentState != State.WAKE) {
            return null
        }
        return transitionTo(State.ACTIVE, nowMillis, TriggerReason.RECOVERY_READY)
    }

    fun currentSnapshot(): Snapshot? {
        if (!enabled) {
            return null
        }
        return Snapshot(
            state = currentState,
            enteredAtMs = enteredAtMs,
            triggerReason = triggerReason,
        )
    }

    fun isInteractionBlocked(): Boolean {
        return currentSnapshot()?.state in setOf(State.SLEEP, State.WAKE)
    }

    private fun transitionTo(state: State, nowMillis: Long, reason: TriggerReason): Snapshot {
        currentState = state
        enteredAtMs = nowMillis
        triggerReason = reason
        return Snapshot(
            state = currentState,
            enteredAtMs = enteredAtMs,
            triggerReason = triggerReason,
        )
    }
}
