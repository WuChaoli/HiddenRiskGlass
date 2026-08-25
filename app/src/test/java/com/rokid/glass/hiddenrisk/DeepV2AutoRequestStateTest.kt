package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepV2AutoRequestStateTest {

    @Test
    fun `begin allows only one active request`() {
        val state = DeepV2AutoRequestState()

        assertNotNull(state.begin(epoch = 7L))
        assertNull(state.begin(epoch = 7L))
        assertTrue(state.isActive)
    }

    @Test
    fun `terminal success requires matching request epoch and frozen image`() {
        val state = DeepV2AutoRequestState()
        val requestId = requireNotNull(state.begin(epoch = 7L))
        val image = DeepV2ImagePayload(byteArrayOf(1), 1512, 2016)
        assertTrue(state.attachImage(requestId, 7L, image))

        assertNull(state.acceptTerminal(requestId, 6L))
        assertNull(state.acceptTerminal(requestId + 1L, 7L))
        assertSame(image, state.acceptTerminal(requestId, 7L))
        assertFalse(state.isActive)
    }

    @Test
    fun `failure releases gate for retry`() {
        val state = DeepV2AutoRequestState()
        val first = requireNotNull(state.begin(7L))

        assertTrue(state.fail(first, 7L))
        assertNotNull(state.begin(7L))
    }

    @Test
    fun `cancel rejects late image and terminal callback`() {
        val state = DeepV2AutoRequestState()
        val requestId = requireNotNull(state.begin(7L))
        state.cancel()

        assertFalse(
            state.attachImage(
                requestId,
                7L,
                DeepV2ImagePayload(byteArrayOf(1), 1512, 2016),
            ),
        )
        assertNull(state.acceptTerminal(requestId, 7L))
        assertFalse(state.isActive)
    }
}
