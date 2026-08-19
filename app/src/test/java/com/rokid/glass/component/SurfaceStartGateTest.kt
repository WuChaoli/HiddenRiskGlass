package com.rokid.glass.component

import org.junit.Assert.assertEquals
import org.junit.Test

class SurfaceStartGateTest {
    @Test
    fun `start waits until gl surface is ready`() {
        val gate = SurfaceStartGate()
        var starts = 0

        gate.runWhenReady { starts++ }
        assertEquals(0, starts)

        gate.markReady()
        assertEquals(1, starts)
    }

    @Test
    fun `start runs immediately after gl surface is ready`() {
        val gate = SurfaceStartGate()
        var starts = 0

        gate.markReady()
        gate.runWhenReady { starts++ }

        assertEquals(1, starts)
    }
}
