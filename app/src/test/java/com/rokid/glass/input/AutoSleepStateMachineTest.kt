package com.rokid.glass.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSleepStateMachineTest {

    private val config = AutoSleepStateMachine.Config(
        wakeDurationMs = 3_000L,
    )

    @Test
    fun `waking stays unchanged when time advances`() {
        val machine = AutoSleepStateMachine(config)
        machine.setEnabled(true, 0L)

        assertTrue(machine.tick(60_000L).isEmpty())
        assertEquals(AutoSleepStateMachine.State.WAKING, machine.currentSnapshot(60_000L)?.state)
    }

    @Test
    fun `warning stays visible while glasses remain removed`() {
        val machine = AutoSleepStateMachine(config)
        machine.setEnabled(true, 0L)
        machine.onGlassesRemoved(1L)

        assertTrue(machine.tick(15_001L).isEmpty())
        assertEquals(AutoSleepStateMachine.State.SLEEP_WARNING, machine.currentSnapshot(15_001L)?.state)
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
        assertEquals(1, snapshots.size)
        assertEquals(AutoSleepStateMachine.State.WAKE, snapshots[0].state)
        assertTrue(machine.tick(3_019L).isEmpty())

        val waking = machine.tick(3_020L).single()
        assertEquals(AutoSleepStateMachine.State.WAKING, waking.state)
    }

    @Test
    fun `wear events are ignored while disabled`() {
        val machine = AutoSleepStateMachine(config)

        assertEquals(null, machine.onGlassesRemoved(10L))
        assertTrue(machine.onGlassesWorn(20L).isEmpty())
        assertEquals(null, machine.currentSnapshot(20L))
    }
}
