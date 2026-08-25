package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepV2OverlayGeometryTest {

    @Test
    fun `bbox maps directly from frozen image to full screen`() {
        val mapped = DeepV2OverlayGeometry.map(
            bbox = DeepV2BoundingBox(30f, 300f, 1200f, 1430f),
            sourceWidth = 1512,
            sourceHeight = 2016,
            destinationWidth = 480,
            destinationHeight = 640,
        )

        requireNotNull(mapped)
        assertEquals(9.52f, mapped.left, 0.02f)
        assertEquals(95.24f, mapped.top, 0.02f)
        assertEquals(380.95f, mapped.right, 0.02f)
        assertEquals(453.97f, mapped.bottom, 0.02f)
    }

    @Test
    fun `mapped bbox is clipped to display bounds`() {
        val mapped = DeepV2OverlayGeometry.map(
            bbox = DeepV2BoundingBox(-10f, -20f, 1600f, 2100f),
            sourceWidth = 1512,
            sourceHeight = 2016,
            destinationWidth = 480,
            destinationHeight = 640,
        )

        assertEquals(RectFModel(0f, 0f, 480f, 640f), mapped)
    }

    @Test
    fun `zero area bbox is rejected`() {
        assertNull(
            DeepV2OverlayGeometry.map(
                bbox = DeepV2BoundingBox(100f, 100f, 100f, 200f),
                sourceWidth = 1512,
                sourceHeight = 2016,
                destinationWidth = 480,
                destinationHeight = 640,
            ),
        )
    }

    @Test
    fun `selected rect expands ten percent around center`() {
        val selected = DeepV2OverlayGeometry.expandAroundCenter(
            RectFModel(100f, 100f, 200f, 300f),
            scale = 1.10f,
        )

        assertEquals(RectFModel(95f, 90f, 205f, 310f), selected)
    }
}
