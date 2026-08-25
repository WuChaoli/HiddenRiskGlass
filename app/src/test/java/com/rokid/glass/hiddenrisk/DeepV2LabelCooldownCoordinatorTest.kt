package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepV2LabelCooldownCoordinatorTest {
    private val registry = LabelCooldownRegistry(cooldownMs = 15_000L)
    private val coordinator = DeepV2LabelCooldownCoordinator(registry)

    @Test
    fun `no hazard starts cooldown when response returns to auto`() {
        coordinator.onRequestStarted(listOf("燃气灶"))

        coordinator.onNoHazardReturnedToAuto(nowElapsedMs = 2_000L)

        assertTrue(registry.isCooling("燃气灶", 16_999L))
        assertFalse(registry.isCooling("燃气灶", 17_000L))
    }

    @Test
    fun `hazard does not cool while result is displayed and starts on return to auto`() {
        coordinator.onRequestStarted(listOf("燃气灶"))
        coordinator.onHazardReturned()

        assertFalse(registry.isCooling("燃气灶", 20_000L))

        coordinator.onReturnedToAuto(nowElapsedMs = 20_000L)

        assertTrue(registry.isCooling("燃气灶", 34_999L))
        assertFalse(registry.isCooling("燃气灶", 35_000L))
    }

    @Test
    fun `failure and cancellation do not start cooldown`() {
        coordinator.onRequestStarted(listOf("燃气灶"))
        coordinator.onRequestFailedOrCancelled()
        coordinator.onReturnedToAuto(nowElapsedMs = 10_000L)

        assertFalse(registry.isCooling("燃气灶", 10_001L))
    }
}
