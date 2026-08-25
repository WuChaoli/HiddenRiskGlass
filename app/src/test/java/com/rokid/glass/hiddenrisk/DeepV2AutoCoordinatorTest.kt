package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepV2AutoCoordinatorTest {
    private val image = DeepV2ImagePayload(byteArrayOf(1), 1512, 2016)

    @Test
    fun `repeated qualifying auto responses start only one request`() {
        val coordinator = DeepV2AutoCoordinator()
        var builds = 0
        var starts = 0

        val first = coordinator.onAutoResponse(7L, true, { builds++; image }) { _, _ -> starts++ }
        val second = coordinator.onAutoResponse(7L, true, { builds++; image }) { _, _ -> starts++ }

        assertEquals(DeepV2AutoDecision.STARTED, first)
        assertEquals(DeepV2AutoDecision.ALREADY_ACTIVE, second)
        assertEquals(1, builds)
        assertEquals(1, starts)
    }

    @Test
    fun `failure releases request gate`() {
        val coordinator = DeepV2AutoCoordinator()
        var requestId = 0L
        coordinator.onAutoResponse(7L, true, { image }) { id, _ -> requestId = id }

        assertTrue(coordinator.onFailure(requestId, 7L))
        assertEquals(
            DeepV2AutoDecision.STARTED,
            coordinator.onAutoResponse(7L, true, { image }) { _, _ -> },
        )
    }

    @Test
    fun `stale success is ignored and active request remains`() {
        val coordinator = DeepV2AutoCoordinator()
        var requestId = 0L
        coordinator.onAutoResponse(7L, true, { image }) { id, _ -> requestId = id }

        assertNull(coordinator.onSuccess(requestId, 6L))
        assertTrue(coordinator.isActive)
        assertSame(image, coordinator.onSuccess(requestId, 7L))
        assertFalse(coordinator.isActive)
    }

    @Test
    fun `missing image releases gate without starting request`() {
        val coordinator = DeepV2AutoCoordinator()
        var starts = 0

        assertEquals(
            DeepV2AutoDecision.IMAGE_UNAVAILABLE,
            coordinator.onAutoResponse(7L, true, { null }) { _, _ -> starts++ },
        )
        assertEquals(0, starts)
        assertFalse(coordinator.isActive)
    }
}
