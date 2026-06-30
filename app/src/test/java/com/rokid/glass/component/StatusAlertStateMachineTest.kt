package com.rokid.glass.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusAlertStateMachineTest {

    private val warningModel = StatusAlertModel(
        status = AlertStatus.WARNING,
        titleText = "检测到疑似隐患",
        messageText = "检测到空气开关",
        action = AlertActionConfig(visible = true, text = "点击开始深度分析"),
        behavior = AlertBehavior(autoDismissMs = 2000L, showCountdownBar = true),
        style = AlertStyle(iconResId = 1),
    )

    @Test
    fun `first render requests rebind`() {
        val machine = StatusAlertStateMachine()

        val decision = machine.render(warningModel)

        assertTrue(decision is StatusAlertStateMachine.RenderDecision.Show)
        assertEquals(true, (decision as StatusAlertStateMachine.RenderDecision.Show).rebind)
    }

    @Test
    fun `same model extends timer without rebind`() {
        val machine = StatusAlertStateMachine()
        machine.render(warningModel)

        val decision = machine.render(warningModel)

        assertTrue(decision is StatusAlertStateMachine.RenderDecision.Show)
        assertEquals(false, (decision as StatusAlertStateMachine.RenderDecision.Show).rebind)
    }

    @Test
    fun `different model requests rebind`() {
        val machine = StatusAlertStateMachine()
        machine.render(warningModel)

        val decision = machine.render(
            warningModel.copy(messageText = "检测到燃气灶")
        )

        assertTrue(decision is StatusAlertStateMachine.RenderDecision.Show)
        assertEquals(true, (decision as StatusAlertStateMachine.RenderDecision.Show).rebind)
    }

    @Test
    fun `render null hides current alert`() {
        val machine = StatusAlertStateMachine()
        machine.render(warningModel)

        val decision = machine.render(null)

        assertEquals(StatusAlertStateMachine.RenderDecision.Hide, decision)
    }

    @Test
    fun `reset on empty machine is noop`() {
        val machine = StatusAlertStateMachine()

        val decision = machine.reset()

        assertEquals(StatusAlertStateMachine.RenderDecision.Noop, decision)
    }
}
