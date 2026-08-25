package com.rokid.glass.hiddenrisk

internal sealed interface DeepV2NavigationState {
    data object Defocused : DeepV2NavigationState
    data class Focused(val targetIndex: Int, val pageIndex: Int) : DeepV2NavigationState
    data class SaveDialog(val selected: DeepV2SaveChoice) : DeepV2NavigationState
    data object Submitting : DeepV2NavigationState
}

internal enum class DeepV2SaveChoice {
    CONFIRM,
    CANCEL,
}

internal sealed interface DeepV2NavigationEffect {
    data object None : DeepV2NavigationEffect
    data object SubmitSave : DeepV2NavigationEffect
    data object DiscardResult : DeepV2NavigationEffect
}

internal data class DeepV2Transition(
    val state: DeepV2NavigationState,
    val effect: DeepV2NavigationEffect = DeepV2NavigationEffect.None,
)

internal class DeepV2PresentationStateMachine(
    pageCounts: IntArray,
) {
    private val pageCounts = pageCounts.map { count -> count.coerceAtLeast(1) }.toIntArray()

    var state: DeepV2NavigationState = DeepV2NavigationState.Defocused
        private set

    fun forward(): DeepV2Transition {
        val next = when (val current = state) {
            DeepV2NavigationState.Defocused -> {
                if (pageCounts.isEmpty()) current else DeepV2NavigationState.Focused(0, 0)
            }
            is DeepV2NavigationState.Focused -> {
                when {
                    current.pageIndex + 1 < pageCounts[current.targetIndex] -> {
                        current.copy(pageIndex = current.pageIndex + 1)
                    }
                    current.targetIndex + 1 < pageCounts.size -> {
                        DeepV2NavigationState.Focused(current.targetIndex + 1, 0)
                    }
                    else -> DeepV2NavigationState.Defocused
                }
            }
            is DeepV2NavigationState.SaveDialog,
            DeepV2NavigationState.Submitting,
            -> current
        }
        return moveTo(next)
    }

    fun backward(): DeepV2Transition {
        val next = when (val current = state) {
            DeepV2NavigationState.Defocused -> {
                if (pageCounts.isEmpty()) current else DeepV2NavigationState.Focused(pageCounts.lastIndex, 0)
            }
            is DeepV2NavigationState.Focused -> {
                when {
                    current.pageIndex > 0 -> current.copy(pageIndex = current.pageIndex - 1)
                    current.targetIndex > 0 -> DeepV2NavigationState.Focused(current.targetIndex - 1, 0)
                    else -> DeepV2NavigationState.Defocused
                }
            }
            is DeepV2NavigationState.SaveDialog,
            DeepV2NavigationState.Submitting,
            -> current
        }
        return moveTo(next)
    }

    fun confirm(): DeepV2Transition {
        return when (val current = state) {
            DeepV2NavigationState.Defocused -> {
                if (pageCounts.isEmpty()) currentTransition() else {
                    moveTo(DeepV2NavigationState.SaveDialog(DeepV2SaveChoice.CONFIRM))
                }
            }
            is DeepV2NavigationState.Focused -> forward()
            is DeepV2NavigationState.SaveDialog -> {
                when (current.selected) {
                    DeepV2SaveChoice.CONFIRM -> moveTo(
                        DeepV2NavigationState.Submitting,
                        DeepV2NavigationEffect.SubmitSave,
                    )
                    DeepV2SaveChoice.CANCEL -> moveTo(
                        DeepV2NavigationState.Defocused,
                        DeepV2NavigationEffect.DiscardResult,
                    )
                }
            }
            DeepV2NavigationState.Submitting -> currentTransition()
        }
    }

    fun selectPreviousDialogChoice(): DeepV2Transition = toggleDialogChoice()

    fun selectNextDialogChoice(): DeepV2Transition = toggleDialogChoice()

    fun voiceConfirm(): DeepV2Transition {
        return if (state is DeepV2NavigationState.SaveDialog) {
            moveTo(DeepV2NavigationState.Submitting, DeepV2NavigationEffect.SubmitSave)
        } else {
            confirm()
        }
    }

    fun voiceCancel(): DeepV2Transition {
        return if (state is DeepV2NavigationState.SaveDialog) {
            moveTo(DeepV2NavigationState.Defocused, DeepV2NavigationEffect.DiscardResult)
        } else {
            currentTransition()
        }
    }

    private fun toggleDialogChoice(): DeepV2Transition {
        val current = state as? DeepV2NavigationState.SaveDialog ?: return currentTransition()
        val selected = when (current.selected) {
            DeepV2SaveChoice.CONFIRM -> DeepV2SaveChoice.CANCEL
            DeepV2SaveChoice.CANCEL -> DeepV2SaveChoice.CONFIRM
        }
        return moveTo(DeepV2NavigationState.SaveDialog(selected))
    }

    private fun moveTo(
        next: DeepV2NavigationState,
        effect: DeepV2NavigationEffect = DeepV2NavigationEffect.None,
    ): DeepV2Transition {
        state = next
        return DeepV2Transition(next, effect)
    }

    private fun currentTransition(): DeepV2Transition = DeepV2Transition(state)
}
