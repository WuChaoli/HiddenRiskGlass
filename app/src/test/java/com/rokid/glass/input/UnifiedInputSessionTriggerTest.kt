package com.rokid.glass.input

import com.rokid.glass.hiddenrisk.HeadGestureManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedInputSessionTriggerTest {

    @Test
    fun buildConfirmTriggers_withoutHeadGesture_containsClickAndConfirmOnly() {
        val triggers = UnifiedInputSession.buildConfirmTriggers(enableHeadGesture = false)

        assertEquals(listOf("确认"), voiceCommands(triggers))
        assertEquals(listOf(UnifiedInputSession.InputKey.CLICK), touchKeys(triggers))
        assertTrue(headGestures(triggers).isEmpty())
    }

    @Test
    fun buildCancelTriggers_withHeadGesture_containsCancelBaseline() {
        val triggers = UnifiedInputSession.buildCancelTriggers(enableHeadGesture = true)

        assertEquals(listOf("取消"), voiceCommands(triggers))
        assertEquals(
            listOf(
                UnifiedInputSession.InputKey.BACK,
                UnifiedInputSession.InputKey.DOUBLE_CLICK,
            ),
            touchKeys(triggers),
        )
        assertEquals(listOf(HeadGestureManager.HeadGestureType.SHAKE), headGestures(triggers))
    }

    @Test
    fun confirmAndCancelTriggers_doNotContainLegacyBusinessCommands() {
        val commands = (
            voiceCommands(UnifiedInputSession.buildConfirmTriggers(enableHeadGesture = true)) +
                voiceCommands(UnifiedInputSession.buildCancelTriggers(enableHeadGesture = true))
            ).toSet()

        assertTrue(commands.contains("确认"))
        assertTrue(commands.contains("取消"))
        assertFalse(commands.contains("保存"))
        assertFalse(commands.contains("继续"))
        assertFalse(commands.contains("结束"))
        assertFalse(commands.contains("退出"))
    }

    @Test
    fun autoSleepStateMachine_disabledStateDoesNotShowPrompt() {
        val stateMachine = newAutoSleepStateMachine()

        val snapshot = stateMachine.onGlassesRemoved(nowMillis = 60_000L)

        assertEquals(null, snapshot)
        assertFalse(stateMachine.isPromptVisible(60_000L))
    }

    @Test
    fun autoSleepStateMachine_enabledStateShowsPromptWhenGlassesRemoved() {
        val stateMachine = newAutoSleepStateMachine()
        stateMachine.setEnabled(true, 0L)

        val snapshot = stateMachine.onGlassesRemoved(nowMillis = 60_000L)

        assertEquals(AutoSleepStateMachine.State.SLEEP_WARNING, snapshot?.state)
        assertTrue(stateMachine.isPromptVisible(60_000L))
    }

    @Test
    fun autoSleepStateMachine_glassesWornResumesAfterWakeDelay() {
        val stateMachine = newAutoSleepStateMachine()
        stateMachine.setEnabled(true, 0L)
        stateMachine.onGlassesRemoved(nowMillis = 60_000L)

        val events = stateMachine.onGlassesWorn(61_000L)
        val earlyEvents = stateMachine.tick(nowMillis = 63_999L)
        val wakeCompleteEvents = stateMachine.tick(nowMillis = 64_000L)

        assertEquals(1, events.size)
        assertEquals(AutoSleepStateMachine.State.WAKE, events[0].state)
        assertTrue(earlyEvents.isEmpty())
        assertEquals(AutoSleepStateMachine.State.WAKING, wakeCompleteEvents.single().state)
        assertFalse(stateMachine.isPromptVisible(64_000L))
    }

    @Test
    fun autoSleepStateMachine_promptDoesNotTimeoutWhileGlassesRemainRemoved() {
        val stateMachine = newAutoSleepStateMachine()
        stateMachine.setEnabled(true, 0L)
        stateMachine.onGlassesRemoved(nowMillis = 60_000L)

        val timeoutEvents = stateMachine.tick(nowMillis = 75_000L)

        assertTrue(timeoutEvents.isEmpty())
        assertTrue(stateMachine.isPromptVisible(75_000L))
    }

    @Test
    fun autoSleepStateMachine_disablingWhilePromptVisibleClearsPrompt() {
        val stateMachine = newAutoSleepStateMachine()
        stateMachine.setEnabled(true, 0L)
        stateMachine.onGlassesRemoved(nowMillis = 60_000L)

        stateMachine.setEnabled(false, 75_000L)
        val timeoutEvents = stateMachine.tick(nowMillis = 75_000L)

        assertTrue(timeoutEvents.isEmpty())
        assertFalse(stateMachine.isPromptVisible(75_000L))
    }

    private fun voiceCommands(triggers: List<UnifiedInputSession.InputTrigger>): List<String> {
        return triggers
            .filterIsInstance<UnifiedInputSession.InputTrigger.Voice>()
            .map { it.command }
    }

    private fun touchKeys(triggers: List<UnifiedInputSession.InputTrigger>): List<Int> {
        return triggers
            .filterIsInstance<UnifiedInputSession.InputTrigger.Touch>()
            .map { it.key }
    }

    private fun headGestures(
        triggers: List<UnifiedInputSession.InputTrigger>,
    ): List<HeadGestureManager.HeadGestureType> {
        return triggers
            .filterIsInstance<UnifiedInputSession.InputTrigger.HeadGesture>()
            .map { it.type }
    }

    private fun newAutoSleepStateMachine(): AutoSleepStateMachine {
        return AutoSleepStateMachine(
            config = AutoSleepStateMachine.Config(
                wakeDurationMs = 3_000L,
            ),
        )
    }
}
