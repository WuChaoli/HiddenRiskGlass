package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StructuredHazardV2EndpointTest {
    @Test
    fun `item with scene uses deep v2`() {
        assertEquals(
            StructuredHazardV2Route(StructuredHazardV2Endpoint.DEEP_V2, "SCENE-1"),
            StructuredHazardV2EndpointRouter.forItem(" SCENE-1 "),
        )
    }

    @Test
    fun `item without scene uses gm v2`() {
        assertEquals(
            StructuredHazardV2Route(StructuredHazardV2Endpoint.GM_V2, null),
            StructuredHazardV2EndpointRouter.forItem(" "),
        )
    }

    @Test
    fun `scene with place code uses general deep v2`() {
        assertEquals(
            StructuredHazardV2Route(StructuredHazardV2Endpoint.GENERAL_DEEP_V2, "SCENE-1"),
            StructuredHazardV2EndpointRouter.forScene("SCENE-1"),
        )
    }

    @Test
    fun `scene without place code is skipped`() {
        assertNull(StructuredHazardV2EndpointRouter.forScene(null))
    }
}
