package com.metrolist.android.playback

import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class DownloadFileNamingTest {
    @Test
    fun collidingPublicNamesNeverOverwriteAnotherRecording() {
        val directory = Files.createTempDirectory("public-audio").toFile()
        try {
            val paths =
                listOf("abcdefgh001", "abcdefgh002", "abcdefgh003").map { id ->
                    val source = directory.resolve("$id.tmp").apply { writeText(id) }
                    moveAudioToDirectory(source, directory, "Artist - Title.mp3")
                }
            assertEquals(3, paths.toSet().size)
            assertEquals(listOf("abcdefgh001", "abcdefgh002", "abcdefgh003"), paths.map { File(it).readText() })
            val replacement = directory.resolve("replacement.tmp").apply { writeText("new") }
            moveAudioToDirectory(replacement, directory, "Artist - Title.mp3")
            assertEquals("abcdefgh001", File(paths.first()).readText())
            repeat(2) { index ->
                val source = directory.resolve("short-$index.tmp").apply { writeText(index.toString()) }
                assertEquals(index.toString(), File(moveAudioToDirectory(source, directory, "x.mp3")).readText())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun publicAudioRemuxPreservesMetadataAndKeepsOriginalOnFailure() {
        val directory = Files.createTempDirectory("audio-remux").toFile()
        val metadata = AudioExportMetadata("Title", "Artist", "Album")
        try {
            for (name in listOf("cache.tmp", "download.tmp", "download.migrate.tmp")) {
                val source = directory.resolve(name).apply { writeText("webm") }
                val (stored, mime) =
                    prepareStoredAudio(source, "audio/webm; codecs=opus", true, metadata) { input, output, tags ->
                        assertEquals("webm", input.readText())
                        assertEquals(metadata, tags)
                        output.writeBytes(opusTagsPacket(tags))
                        true
                    }
                assertEquals("audio/ogg", mime)
                assertFalse(source.exists())
                assertTrue(stored.readText().contains("TITLE=Title"))
            }
            val source = directory.resolve("failure.tmp").apply { writeText("original") }
            assertThrows(IllegalStateException::class.java) {
                prepareStoredAudio(source, "audio/webm", true, metadata) { _, output, _ ->
                    output.writeText("partial")
                    false
                }
            }
            assertEquals("original", source.readText())
            assertFalse(directory.resolve("failure.opus.tmp").exists())
            for (mime in listOf("audio/mp4", "audio/webm")) {
                assertEquals(
                    source to mime,
                    prepareStoredAudio(source, mime, false, metadata, exportAac = { _, _, _ -> error("Private AAC") }) { _, _, _ ->
                        error("Private WebM")
                    },
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun publicAacExportsUseM4aAndRemovePartialOutputsOnFailure() {
        val directory = Files.createTempDirectory("aac-export").toFile()
        val metadata = AudioExportMetadata("Title", "Artist", "Album")
        try {
            for (mime in listOf("audio/mp4; codecs=mp4a.40.2", "audio/x-m4a", "audio/aac", "audio/mp4a-latm")) {
                val source = directory.resolve("download.tmp").apply { writeText("aac") }
                val (stored, storedMime) =
                    prepareStoredAudio(source, mime, true, metadata, exportAac = { input, output, tags ->
                        assertEquals("aac", input.readText())
                        assertEquals(metadata, tags)
                        output.writeText("m4a")
                        true
                    })
                assertEquals("audio/mp4", storedMime)
                assertEquals("download.m4a.tmp", stored.name)
                assertFalse(source.exists())
                stored.delete()
            }
            for (throws in listOf(false, true)) {
                val source = directory.resolve("download.tmp").apply { writeText("original") }
                assertThrows(IllegalStateException::class.java) {
                    prepareStoredAudio(source, "audio/mp4", true, metadata, exportAac = { _, output, _ ->
                        output.writeText("partial")
                        if (throws) error("Muxer failed")
                        false
                    })
                }
                assertEquals("original", source.readText())
                assertFalse(directory.resolve("download.m4a.tmp").exists())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun requiredRemuxNeverReturnsWebmAndCleansPartialOutput() {
        val directory = Files.createTempDirectory("required-remux").toFile()
        val source = directory.resolve("cache.tmp").apply { writeText("original") }
        try {
            for (throws in listOf(false, true)) {
                val expectedException: Class<out Exception> = if (throws) IOException::class.java else IllegalStateException::class.java
                assertThrows(expectedException) {
                    prepareStoredAudio(
                        source,
                        "audio/webm",
                        true,
                        AudioExportMetadata("Title", "Artist", null),
                    ) { _, output, _ ->
                        output.writeText("partial")
                        if (throws) throw IOException("Extraction failed")
                        false
                    }
                }
                assertEquals("original", source.readText())
                assertEquals(listOf(source), directory.listFiles()!!.toList())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun sharedDownloadNamesAreReadableAndFilesystemSafe() {
        val song =
            SongItem(
                id = "video-id",
                title = "Title: Live?",
                thumbnail = null,
                artists = listOf(ArtistTiny(null, "Artist/Guest")),
                album = null,
            )

        assertEquals("Artist_Guest - Title_ Live_", downloadFileBaseName(song))
    }

    @Test
    fun opusTagsContainSongMetadata() {
        val packet = opusTagsPacket(AudioExportMetadata(title = "Title", artist = "Artist", album = "Album")).decodeToString()

        assertTrue(packet.startsWith("OpusTags"))
        assertTrue("TITLE=Title" in packet)
        assertTrue("ARTIST=Artist" in packet)
        assertTrue("ALBUM=Album" in packet)
    }
}
