package com.rokid.glass.input

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassesWearStateMachineTest {

    @Test
    fun `enabling monitoring begins in active state`() {
        val machine = GlassesWearStateMachine()

        val snapshot = machine.setEnabled(true, 0L)

        assertEquals(GlassesWearStateMachine.State.ACTIVE, snapshot?.state)
    }

    @Test
    fun `sleep remains until glasses are worn`() {
        val machine = GlassesWearStateMachine()
        machine.setEnabled(true, 0L)
        machine.onGlassesRemoved(1L)

        assertEquals(GlassesWearStateMachine.State.SLEEP, machine.currentSnapshot()?.state)
    }

    @Test
    fun `glasses removed enters sleep immediately`() {
        val machine = GlassesWearStateMachine()
        machine.setEnabled(true, 0L)

        val snapshot = machine.onGlassesRemoved(10L)
        assertEquals(GlassesWearStateMachine.State.SLEEP, snapshot?.state)
        assertEquals(GlassesWearStateMachine.TriggerReason.GLASSES_REMOVED, snapshot?.triggerReason)
    }

    @Test
    fun `glasses worn waits in wake until recovery is ready`() {
        val machine = GlassesWearStateMachine()
        machine.setEnabled(true, 0L)
        machine.onGlassesRemoved(10L)

        val wake = machine.onGlassesWorn(20L)
        assertEquals(GlassesWearStateMachine.State.WAKE, wake?.state)
        assertEquals(GlassesWearStateMachine.State.WAKE, machine.currentSnapshot()?.state)

        val active = machine.onRecoveryReady(60_001L)
        assertEquals(GlassesWearStateMachine.State.ACTIVE, active?.state)
    }

    @Test
    fun `removing glasses during wake returns to sleep`() {
        val machine = GlassesWearStateMachine()
        machine.setEnabled(true, 0L)
        machine.onGlassesRemoved(10L)
        machine.onGlassesWorn(20L)

        val snapshot = machine.onGlassesRemoved(30L)

        assertEquals(GlassesWearStateMachine.State.SLEEP, snapshot?.state)
    }

    @Test
    fun `wear events are ignored while disabled`() {
        val machine = GlassesWearStateMachine()

        assertEquals(null, machine.onGlassesRemoved(10L))
        assertEquals(null, machine.onGlassesWorn(20L))
        assertEquals(null, machine.onRecoveryReady(30L))
        assertEquals(null, machine.currentSnapshot())
    }
}
