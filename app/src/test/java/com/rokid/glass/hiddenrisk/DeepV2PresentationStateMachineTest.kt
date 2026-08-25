package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Test

class DeepV2PresentationStateMachineTest {

    @Test
    fun `forward cycles defocused targets pages others and defocused`() {
        val machine = DeepV2PresentationStateMachine(intArrayOf(2, 1, 2))

        assertEquals(DeepV2NavigationState.Defocused, machine.state)
        assertState(machine.forward(), 0, 0)
        assertState(machine.forward(), 0, 1)
        assertState(machine.forward(), 1, 0)
        assertState(machine.forward(), 2, 0)
        assertState(machine.forward(), 2, 1)
        assertEquals(DeepV2NavigationState.Defocused, machine.forward().state)
    }

    @Test
    fun `backward entering every target starts from first page`() {
        val machine = DeepV2PresentationStateMachine(intArrayOf(2, 1, 2))

        assertState(machine.backward(), 2, 0)
        assertState(machine.backward(), 1, 0)
        assertState(machine.backward(), 0, 0)
        assertEquals(DeepV2NavigationState.Defocused, machine.backward().state)
    }

    @Test
    fun `backward within focused target moves to previous page`() {
        val machine = DeepV2PresentationStateMachine(intArrayOf(3))
        machine.forward()
        machine.forward()
        machine.forward()

        assertState(machine.backward(), 0, 1)
        assertState(machine.backward(), 0, 0)
    }

    @Test
    fun `confirm while focused behaves as forward`() {
        val machine = DeepV2PresentationStateMachine(intArrayOf(1, 1))
        machine.forward()

        assertState(machine.confirm(), 1, 0)
    }

    @Test
    fun `confirm while defocused opens dialog with confirm selected`() {
        val machine = DeepV2PresentationStateMachine(intArrayOf(1))

        assertEquals(
            DeepV2NavigationState.SaveDialog(DeepV2SaveChoice.CONFIRM),
            machine.confirm().state,
        )
    }

    @Test
    fun `dialog selection executes submit or discard`() {
        val submitMachine = DeepV2PresentationStateMachine(intArrayOf(1))
        submitMachine.confirm()
        val submit = submitMachine.confirm()
        assertEquals(DeepV2NavigationState.Submitting, submit.state)
        assertEquals(DeepV2NavigationEffect.SubmitSave, submit.effect)

        val discardMachine = DeepV2PresentationStateMachine(intArrayOf(1))
        discardMachine.confirm()
        assertEquals(
            DeepV2NavigationState.SaveDialog(DeepV2SaveChoice.CANCEL),
            discardMachine.selectNextDialogChoice().state,
        )
        val discard = discardMachine.confirm()
        assertEquals(DeepV2NavigationState.Defocused, discard.state)
        assertEquals(DeepV2NavigationEffect.DiscardResult, discard.effect)
    }

    @Test
    fun `voice confirm and cancel directly execute dialog choices`() {
        val submitMachine = DeepV2PresentationStateMachine(intArrayOf(1))
        submitMachine.confirm()
        assertEquals(DeepV2NavigationEffect.SubmitSave, submitMachine.voiceConfirm().effect)

        val discardMachine = DeepV2PresentationStateMachine(intArrayOf(1))
        discardMachine.confirm()
        assertEquals(DeepV2NavigationEffect.DiscardResult, discardMachine.voiceCancel().effect)
    }

    @Test
    fun `empty target list remains defocused`() {
        val machine = DeepV2PresentationStateMachine(intArrayOf())

        assertEquals(DeepV2NavigationState.Defocused, machine.forward().state)
        assertEquals(DeepV2NavigationEffect.None, machine.confirm().effect)
        assertEquals(DeepV2NavigationState.Defocused, machine.state)
    }

    private fun assertState(transition: DeepV2Transition, targetIndex: Int, pageIndex: Int) {
        assertEquals(
            DeepV2NavigationState.Focused(targetIndex, pageIndex),
            transition.state,
        )
        assertEquals(DeepV2NavigationEffect.None, transition.effect)
    }
}
