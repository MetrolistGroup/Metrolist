package com.metrolist.shared.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogTextChunksTest {
    @Test
    fun boundsLayoutsAndPreservesTheFullReportIncludingLongLinesAndUnicode() {
        val text =
            "x".repeat(LOG_TEXT_CHUNK_CHARS - 1) + "\uD83D\uDE00\n" +
                "a".repeat(256 * 1024) + "\r\n" + "frame\n".repeat(1_000)
        val chunks = logTextChunks(text)

        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.size > 100)
        for (chunk in chunks) {
            assertTrue(chunk.length in 1..LOG_TEXT_CHUNK_CHARS)
            assertFalse(chunk.last().isHighSurrogate())
            assertFalse(chunk.first().isLowSurrogate())
        }
    }

    @Test
    fun prefersLineBoundariesAndHandlesEmptyText() {
        val line = "x".repeat(1_000) + "\n"
        assertEquals(listOf(line + line, line), logTextChunks(line.repeat(3)))
        assertEquals(emptyList(), logTextChunks(""))
    }
}
