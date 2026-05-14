package com.rokid.glass.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSleepStateMachineTest {

    private val config = AutoSleepStateMachine.Config(
        wakingDurationMs = 60_000L,
        sleepWarningDurationMs = 15_000L,
    )

    @Test
    fun `waking transitions to warning after duration`() {
        val machine = AutoSleepStateMachine(config)
        machine.setEnabled(true, 0L)

        assertTrue(machine.tick(59_999L).isEmpty())
        val snapshot = machine.tick(60_000L).single()
        assertEquals(AutoSleepStateMachine.State.SLEEP_WARNING, snapshot.state)
    }

    @Test
    fun `warning transitions to tosleep after duration`() {
        val machine = AutoSleepStateMachine(config)
        machine.setEnabled(true, 0L)
        machine.onGlassesRemoved(1L)

        assertTrue(machine.tick(15_000L).isEmpty())
        val snapshot = machine.tick(15_001L).single()
        assertEquals(AutoSleepStateMachine.State.TO_SLEEP, snapshot.state)
    }

    @Test
    fun `glasses removed enters warning immediately`() {
        val machine = AutoSleepStateMachine(config)
        machine.setEnabled(true, 0L)

        val snapshot = machine.onGlassesRemoved(10L)
        assertEquals(AutoSleepStateMachine.State.SLEEP_WARNING, snapshot?.state)
        assertEquals(AutoSleepStateMachine.TriggerReason.GLASSES_REMOVED, snapshot?.triggerReason)
    }

    @Test
    fun `glasses worn recovers through wake to waking`() {
        val machine = AutoSleepStateMachine(config)
        machine.setEnabled(true, 0L)
        machine.onGlassesRemoved(10L)

        val snapshots = machine.onGlassesWorn(20L)
        assertEquals(2, snapshots.size)
        assertEquals(AutoSleepStateMachine.State.WAKE, snapshots[0].state)
        assertEquals(AutoSleepStateMachine.State.WAKING, snapshots[1].state)
    }

    @Test
    fun `user activity does not dismiss glasses removed warning`() {
        val machine = AutoSleepStateMachine(config)
        machine.setEnabled(true, 0L)
        machine.onGlassesRemoved(10L)

        val snapshots = machine.onUserActivity(AutoSleepStateMachine.UserActivitySource.TOUCH, 20L)
        assertTrue(snapshots.isEmpty())
        assertEquals(AutoSleepStateMachine.State.SLEEP_WARNING, machine.currentSnapshot(20L)?.state)
    }
}
