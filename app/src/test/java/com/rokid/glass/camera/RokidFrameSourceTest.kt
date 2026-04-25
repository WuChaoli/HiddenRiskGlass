package com.rokid.glass.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class RokidFrameSourceTest {

    @Test
    fun `shared frame stream zoom stays at two x`() {
        assertEquals(2.0f, RokidFrameSource.SHARED_FRAME_STREAM_ZOOM_RATIO, 0.0f)
    }

    @Test
    fun `shared two x zoom maps to sdk level two`() {
        assertEquals(2, RokidFrameSource.sdkZoomLevelFor(RokidFrameSource.SHARED_FRAME_STREAM_ZOOM_RATIO))
    }

    @Test
    fun `sdk zoom level thresholds stay aligned with current mapping`() {
        assertEquals(1, RokidFrameSource.sdkZoomLevelFor(1.0f))
        assertEquals(2, RokidFrameSource.sdkZoomLevelFor(1.9f))
        assertEquals(3, RokidFrameSource.sdkZoomLevelFor(2.5f))
    }
}
