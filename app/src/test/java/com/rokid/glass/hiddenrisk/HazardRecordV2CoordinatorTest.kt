package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardRecordV2CoordinatorTest {
    @Test
    fun `new request invalidates previous response`() {
        val coordinator = HazardRecordV2Coordinator()
        coordinator.begin(1L)
        coordinator.begin(2L)

        assertFalse(coordinator.accept(1L))
        assertTrue(coordinator.accept(2L))
    }

    @Test
    fun `cancel invalidates active response`() {
        val coordinator = HazardRecordV2Coordinator()
        coordinator.begin(1L)
        coordinator.cancel()

        assertFalse(coordinator.accept(1L))
    }
}
