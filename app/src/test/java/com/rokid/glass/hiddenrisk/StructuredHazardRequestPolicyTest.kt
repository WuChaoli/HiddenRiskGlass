package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StructuredHazardRequestPolicyTest {
    @Test
    fun `manual with scene uses deep v2`() {
        assertEquals(
            StructuredHazardV2Endpoint.DEEP_V2,
            StructuredHazardRequestPolicy.route(StructuredHazardSource.MANUAL, "PLACE")?.endpoint,
        )
    }

    @Test
    fun `manual without scene uses gm v2`() {
        assertEquals(
            StructuredHazardV2Endpoint.GM_V2,
            StructuredHazardRequestPolicy.route(StructuredHazardSource.MANUAL, null)?.endpoint,
        )
    }

    @Test
    fun `scene source uses general deep and skips missing scene`() {
        assertEquals(
            StructuredHazardV2Endpoint.GENERAL_DEEP_V2,
            StructuredHazardRequestPolicy.route(StructuredHazardSource.SCENE, "PLACE")?.endpoint,
        )
        assertNull(StructuredHazardRequestPolicy.route(StructuredHazardSource.SCENE, null))
    }
}
