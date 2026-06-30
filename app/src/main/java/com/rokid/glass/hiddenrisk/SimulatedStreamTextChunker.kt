package com.rokid.glass.hiddenrisk

internal object SimulatedStreamTextChunker {
    fun prefixChunks(text: String, chunkSize: Int): List<String> {
        val safeChunkSize = chunkSize.coerceAtLeast(1)
        if (text.isEmpty()) {
            return listOf("")
        }
        return (safeChunkSize..text.length step safeChunkSize)
            .map { endIndex -> text.substring(0, endIndex.coerceAtMost(text.length)) }
            .toMutableList()
            .apply {
                if (lastOrNull() != text) {
                    add(text)
                }
            }
    }
}
