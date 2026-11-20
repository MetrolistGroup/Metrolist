package com.metrolist.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class MigratedDownloadNameTest {
    @Test
    fun migratedNamesPreserveUnicodeAndTheLegacySanitizer() {
        val prefix = "a".repeat(119)
        val clipped = downloadFileBaseName("", "$prefix🎵tail", "safe-id")
        assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(clipped))
        assertEquals(prefix, clipped)
        assertEquals("Artist_   -  Title_ live", downloadFileBaseName(" Artist?  ", " Title: live.", "safe-id"))
        assertEquals("safe-id", downloadFileBaseName("", "...", "safe-id"))
    }

    @Test
    fun collidingLongMigratedNamesKeepBothFilesAndTheirAudioExtension() {
        val root = Files.createTempDirectory("migration-names").toFile()
        try {
            val artist = "界".repeat(85)
            val title = "曲".repeat(15)
            val targetNames =
                listOf("first", "second").map { id ->
                    val source = root.resolve("$id.copy").apply { writeText(id) }
                    val baseName = downloadFileBaseName(artist, title, id)
                    File(moveAudioToDirectory(source, root.resolve("shared"), "$baseName.audio"))
                }
            assertEquals("界".repeat(83) + ".audio", targetNames.first().name)
            assertNotEquals(targetNames[0], targetNames[1])
            assertEquals(listOf("first", "second"), targetNames.map(File::readText))
            assertEquals(listOf("audio", "audio"), targetNames.map(File::extension))
            assertTrue(targetNames.all { it.name.encodeToByteArray().size <= 255 })
        } finally {
            root.deleteRecursively()
        }
    }
}
