package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DualEndpointSubmitCoordinatorTest {

    @Test
    fun record_waitsUntilBothEndpointsComplete() {
        var completed = false
        val coordinator = DualEndpointSubmitCoordinator(listOf("primary", "backup")) {
            completed = true
        }

        coordinator.record(
            label = "primary",
            outcome = RetryOutcome(success = true, attemptCount = 1),
        )

        assertTrue(!completed)
    }

    @Test
    fun record_preservesConfiguredLabelOrderInCompletionMap() {
        var completionOrder: List<String> = emptyList()
        val coordinator = DualEndpointSubmitCoordinator(listOf("primary", "backup")) { outcomes ->
            completionOrder = outcomes.keys.toList()
        }

        coordinator.record(
            label = "backup",
            outcome = RetryOutcome(success = true, attemptCount = 1),
        )
        coordinator.record(
            label = "primary",
            outcome = RetryOutcome(success = false, message = "failed", attemptCount = 4),
        )

        assertEquals(listOf("primary", "backup"), completionOrder)
    }
}
