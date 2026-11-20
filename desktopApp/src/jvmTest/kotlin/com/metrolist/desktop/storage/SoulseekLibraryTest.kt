package com.metrolist.desktop.storage

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SoulseekLibraryTest {
    private lateinit var dir: File
    private lateinit var original: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("soulseek-library").toFile()
        original = SoulseekLibrary.storeFile
        SoulseekLibrary.storeFile = File(dir, "soulseek-files.json")
    }

    @AfterTest
    fun tearDown() {
        SoulseekLibrary.storeFile = original
        dir.deleteRecursively()
    }

    private fun version(
        name: String,
        format: String,
    ): SoulseekFile {
        val file = File(dir, name).apply { writeText("audio") }
        return SoulseekFile(file.absolutePath, format, name.substringAfterLast('.'), file.length())
    }

    @Test
    fun newestDownloadPlaysAndVersionsFollowFormatOrder() {
        val flac = version("song.flac", "lossless")
        val mp3 = version("song.mp3", "320")
        SoulseekLibrary.add("song", flac)
        SoulseekLibrary.add("song", mp3)

        assertEquals(listOf(mp3, flac), SoulseekLibrary.versions("song"))
        val resource = SoulseekLibrary.playbackResource("song")!!
        assertEquals(mp3.path, resource.url)
        assertEquals("local", resource.clientName)
    }

    @Test
    fun choosingStreamingOrDeletingTheFileFallsBackToTheStream() {
        val flac = version("song.flac", "lossless")
        SoulseekLibrary.add("song", flac)

        SoulseekLibrary.select("song", null)
        assertNull(SoulseekLibrary.playbackResource("song"))

        SoulseekLibrary.select("song", flac.path)
        File(flac.path).delete()
        assertEquals(emptyList(), SoulseekLibrary.versions("song"))
        assertNull(SoulseekLibrary.playbackResource("song"))
    }

    @Test
    fun choicesSurviveARestart() {
        val mp3 = version("song.mp3", "320")
        val hires = version("song-24.flac", "hires")
        SoulseekLibrary.add("song", mp3)
        SoulseekLibrary.add("song", hires)
        SoulseekLibrary.select("song", mp3.path)

        SoulseekLibrary.storeFile = File(dir, "soulseek-files.json") // reloads from disk
        assertEquals(mp3.path, SoulseekLibrary.selected("song")?.path)
        assertEquals(listOf(mp3, hires), SoulseekLibrary.versions("song"))
    }
}
