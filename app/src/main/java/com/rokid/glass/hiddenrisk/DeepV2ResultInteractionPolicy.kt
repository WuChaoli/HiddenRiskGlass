package com.rokid.glass.hiddenrisk

import com.rokid.glass.input.UnifiedInputSession

internal enum class DeepV2NavigationDirection {
    FORWARD,
    BACKWARD,
}

internal enum class DeepV2FocusTransition {
    FOCUS_THEN_SHOW_DETAIL,
    SWITCH_BOX_THEN_SHOW_DETAIL,
    DEFOCUS_THEN_SHOW_DETAIL,
    SHOW_DETAIL_IMMEDIATELY,
}

internal object DeepV2ResultInteractionPolicy {
    const val forwardTouchKey: Int = UnifiedInputSession.InputKey.BEHIND
    const val backwardTouchKey: Int = UnifiedInputSession.InputKey.FRONT

    fun directionForTouchKey(keyCode: Int): DeepV2NavigationDirection? = when (keyCode) {
        forwardTouchKey -> DeepV2NavigationDirection.FORWARD
        backwardTouchKey -> DeepV2NavigationDirection.BACKWARD
        else -> null
    }

    fun cardTitle(label: String): String = label.trim()

    fun shouldAnimateBoxChange(previousLabelId: String?, nextLabelId: String?): Boolean {
        return previousLabelId != null &&
            nextLabelId != null &&
            previousLabelId != nextLabelId
    }

    fun focusTransition(previousLabelId: String?, nextLabelId: String?): DeepV2FocusTransition = when {
        previousLabelId == null && nextLabelId != null -> DeepV2FocusTransition.FOCUS_THEN_SHOW_DETAIL
        previousLabelId != null && nextLabelId != null && previousLabelId != nextLabelId -> {
            DeepV2FocusTransition.SWITCH_BOX_THEN_SHOW_DETAIL
        }
        previousLabelId != null && nextLabelId == null -> DeepV2FocusTransition.DEFOCUS_THEN_SHOW_DETAIL
        else -> DeepV2FocusTransition.SHOW_DETAIL_IMMEDIATELY
    }
}
