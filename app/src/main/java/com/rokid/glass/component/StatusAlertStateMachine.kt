package com.rokid.glass.component

/**
 * 纯逻辑状态机，用于决定组件当前应该重绘、续期还是隐藏。
 */
internal class StatusAlertStateMachine {

    private var currentModel: StatusAlertModel? = null

    fun render(model: StatusAlertModel?): RenderDecision {
        if (model == null) {
            return clear()
        }

        val previous = currentModel
        currentModel = model
        return when {
            previous == null -> RenderDecision.Show(model = model, rebind = true)
            previous == model -> RenderDecision.Show(model = model, rebind = false)
            else -> RenderDecision.Show(model = model, rebind = true)
        }
    }

    fun reset(): RenderDecision {
        return clear()
    }

    private fun clear(): RenderDecision {
        if (currentModel == null) {
            return RenderDecision.Noop
        }
        currentModel = null
        return RenderDecision.Hide
    }

    sealed class RenderDecision {
        data class Show(
            val model: StatusAlertModel,
            val rebind: Boolean,
        ) : RenderDecision()

        data object Hide : RenderDecision()

        data object Noop : RenderDecision()
    }
}
