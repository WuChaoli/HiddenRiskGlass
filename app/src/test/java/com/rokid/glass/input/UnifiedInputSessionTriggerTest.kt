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

        val events = stateMachine.onIdleQualified(nowMillis = 60_000L)

        assertTrue(events.isEmpty())
        assertFalse(stateMachine.isPromptVisible())
    }

    @Test
    fun autoSleepStateMachine_enabledStateShowsPromptWhenIdleQualified() {
        val stateMachine = newAutoSleepStateMachine()
        stateMachine.setEnabled(true)

        val events = stateMachine.onIdleQualified(nowMillis = 60_000L)

        assertEquals(listOf(AutoSleepStateMachine.Event.PromptShown), events)
        assertTrue(stateMachine.isPromptVisible())
    }

    @Test
    fun autoSleepStateMachine_activityDuringPromptResumesAndClearsTimeout() {
        val stateMachine = newAutoSleepStateMachine()
        stateMachine.setEnabled(true)
        stateMachine.onIdleQualified(nowMillis = 60_000L)

        val events = stateMachine.notifyUserActivity(AutoSleepStateMachine.UserActivitySource.TOUCH)
        val timeoutEvents = stateMachine.tick(nowMillis = 75_000L)

        assertEquals(
            listOf(
                AutoSleepStateMachine.Event.ResumeRequested(
                    AutoSleepStateMachine.UserActivitySource.TOUCH,
                ),
            ),
            events,
        )
        assertTrue(timeoutEvents.isEmpty())
        assertFalse(stateMachine.isPromptVisible())
    }

    @Test
    fun autoSleepStateMachine_promptTimeoutReturnsToMenu() {
        val stateMachine = newAutoSleepStateMachine()
        stateMachine.setEnabled(true)
        stateMachine.onIdleQualified(nowMillis = 60_000L)

        val earlyEvents = stateMachine.tick(nowMillis = 74_999L)
        val timeoutEvents = stateMachine.tick(nowMillis = 75_000L)

        assertTrue(earlyEvents.isEmpty())
        assertEquals(listOf(AutoSleepStateMachine.Event.TimeoutReturnToMenu), timeoutEvents)
        assertFalse(stateMachine.isPromptVisible())
    }

    @Test
    fun autoSleepStateMachine_disablingWhilePromptVisibleClearsPrompt() {
        val stateMachine = newAutoSleepStateMachine()
        stateMachine.setEnabled(true)
        stateMachine.onIdleQualified(nowMillis = 60_000L)

        stateMachine.setEnabled(false)
        val timeoutEvents = stateMachine.tick(nowMillis = 75_000L)

        assertTrue(timeoutEvents.isEmpty())
        assertFalse(stateMachine.isPromptVisible())
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
            idleBeforePromptMs = 60_000L,
            promptTimeoutMs = 15_000L,
        )
    }
}
