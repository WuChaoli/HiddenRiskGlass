package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Test

class SimulatedStreamTextChunkerTest {
    @Test
    fun prefixChunks_returnsBlankChunkForBlankText() {
        assertEquals(listOf(""), SimulatedStreamTextChunker.prefixChunks("", chunkSize = 12))
    }

    @Test
    fun prefixChunks_returnsFullTextWhenShorterThanChunk() {
        assertEquals(listOf("短文本"), SimulatedStreamTextChunker.prefixChunks("短文本", chunkSize = 12))
    }

    @Test
    fun prefixChunks_returnsOrderedPrefixesForLongText() {
        assertEquals(
            listOf("abcdefghijkl", "abcdefghijklmnopqrstuvwx", "abcdefghijklmnopqrstuvwxyz"),
            SimulatedStreamTextChunker.prefixChunks("abcdefghijklmnopqrstuvwxyz", chunkSize = 12),
        )
    }
}
