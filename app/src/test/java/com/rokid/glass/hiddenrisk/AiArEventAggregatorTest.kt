package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Test

class AiArEventAggregatorTest {
    @Test
    fun append_aggregatesChunksWithStableTaskId() {
        val aggregator = AiArEventAggregator()

        val firstChunk = aggregator.append("""{"task_id":"123","content":"隐患"}""")
        assertEquals("隐患", firstChunk)
        assertEquals("隐患", aggregator.fullText())

        val secondChunk = aggregator.append("""{"task_id":"123","content":"描述"}""")
        assertEquals("描述", secondChunk)

        assertEquals("123", aggregator.taskId())
        assertEquals("隐患描述", aggregator.fullText())
    }

    @Test(expected = IllegalStateException::class)
    fun append_rejectsTaskIdMismatch() {
        val aggregator = AiArEventAggregator()

        aggregator.append("""{"task_id":"123","content":"是"}""")
        aggregator.append("""{"task_id":"456","content":"否"}""")
    }
}
