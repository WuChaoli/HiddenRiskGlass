package com.rokid.glass.updater

import org.junit.Assert.assertEquals
import org.junit.Test

class AppUpdatePromptSelectionTest {

    @Test
    fun previousFromUpdate_doesNotWrapToCancel() {
        assertEquals(ACTION_UPDATE, moveUpdatePromptSelection(ACTION_UPDATE, -1, ACTION_CANCEL))
    }

    @Test
    fun next_movesThroughSkipBeforeCancel() {
        val skip = moveUpdatePromptSelection(ACTION_UPDATE, 1, ACTION_CANCEL)
        val cancel = moveUpdatePromptSelection(skip, 1, ACTION_CANCEL)

        assertEquals(ACTION_SKIP, skip)
        assertEquals(ACTION_CANCEL, cancel)
        assertEquals(ACTION_CANCEL, moveUpdatePromptSelection(cancel, 1, ACTION_CANCEL))
    }

    @Test
    fun previous_movesThroughSkipBeforeUpdate() {
        val skip = moveUpdatePromptSelection(ACTION_CANCEL, -1, ACTION_CANCEL)
        val update = moveUpdatePromptSelection(skip, -1, ACTION_CANCEL)

        assertEquals(ACTION_SKIP, skip)
        assertEquals(ACTION_UPDATE, update)
    }

    companion object {
        private const val ACTION_UPDATE = 0
        private const val ACTION_SKIP = 1
        private const val ACTION_CANCEL = 2
    }
}
