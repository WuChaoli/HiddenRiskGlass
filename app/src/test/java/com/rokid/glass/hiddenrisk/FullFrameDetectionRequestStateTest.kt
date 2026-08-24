package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FullFrameDetectionRequestStateTest {

    @Test
    fun `only one auto request can be in flight`() {
        val state = FullFrameDetectionRequestState()

        val first = state.begin(100L)

        assertNotNull(first)
        assertNull(state.begin(700L))
    }

    @Test
    fun `stale response cannot replace current state`() {
        val state = FullFrameDetectionRequestState()
        val first = state.begin(100L)!!
        state.cancel()
        val second = state.begin(700L)!!

        assertFalse(state.acceptSuccess(first))
        assertTrue(state.acceptSuccess(second))
    }

    @Test
    fun `failure completes only matching request`() {
        val state = FullFrameDetectionRequestState()
        val request = state.begin(100L)!!

        assertFalse(state.acceptFailure(request + 1L))
        assertNull(state.begin(700L))
        assertTrue(state.acceptFailure(request))
        assertNotNull(state.begin(700L))
    }

    @Test
    fun `cancel invalidates prior callback`() {
        val state = FullFrameDetectionRequestState()
        val request = state.begin(100L)!!

        state.cancel()

        assertFalse(state.acceptFailure(request))
    }

    @Test
    fun `cadence blocks a new request until 500 milliseconds elapsed`() {
        val state = FullFrameDetectionRequestState()
        val request = state.begin(100L)!!
        assertTrue(state.acceptSuccess(request))

        assertNull(state.begin(599L))
        assertNotNull(state.begin(600L))
    }
}
