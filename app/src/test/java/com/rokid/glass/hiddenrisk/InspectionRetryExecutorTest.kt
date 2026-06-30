package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InspectionRetryExecutorTest {

    @Test
    fun delayBeforeNextAttempt_usesIncrementalBackoffForThreeRetries() {
        assertEquals(1000L, InspectionRequestRetryPolicy.delayBeforeNextAttempt(1))
        assertEquals(2000L, InspectionRequestRetryPolicy.delayBeforeNextAttempt(2))
        assertEquals(3000L, InspectionRequestRetryPolicy.delayBeforeNextAttempt(3))
    }

    @Test
    fun delayBeforeNextAttempt_returnsNullWhenRetriesExhausted() {
        assertNull(InspectionRequestRetryPolicy.delayBeforeNextAttempt(0))
        assertNull(InspectionRequestRetryPolicy.delayBeforeNextAttempt(4))
        assertNull(InspectionRequestRetryPolicy.delayBeforeNextAttempt(5))
    }
}
