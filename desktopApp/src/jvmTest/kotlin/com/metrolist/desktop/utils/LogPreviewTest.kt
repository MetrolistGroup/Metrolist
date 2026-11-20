package com.metrolist.desktop.utils

import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LogPreviewTest {
    @Test
    fun missingFileAfterEnumerationDoesNotHideOtherLogTails() {
        val root = Files.createTempDirectory("metrolist-log-preview").toFile()
        try {
            val rotated = root.resolve("app.log").apply { writeText("rotated") }
            val archived =
                (1..4).map { index ->
                    root.resolve("app.log.$index").apply { writeText("log $index") }
                }
            val snapshot = listOf(rotated) + archived
            Files.delete(rotated.toPath())

            assertEquals(
                "--- app.log.1 ---\nlog 1\n\n---\n\n--- app.log.2 ---\nlog 2\n\n---\n\n--- app.log.3 ---\nlog 3",
                readLogPreview(snapshot),
            )
            assertEquals("", readLogPreview(listOf(rotated)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun tailStillKeepsOnlyTheLastCharactersIncludingUnicode() {
        val file = Files.createTempFile("metrolist-log-tail", ".log").toFile()
        try {
            file.writeText("older line\nę你好🙂")
            assertEquals("你好🙂", readLogTail(file, maxChars = 4))
            assertEquals("", readLogTail(file, maxChars = 0))
        } finally {
            Files.deleteIfExists(file.toPath())
        }
    }

    @Test
    fun existingUnreadablePathIsNotSilentlyTreatedAsGone() {
        val directory = Files.createTempDirectory("metrolist-invalid-log").toFile()
        try {
            assertFailsWith<IOException> { readLogPreview(listOf(directory)) }
        } finally {
            directory.deleteRecursively()
        }
    }
}
