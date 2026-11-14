package com.metrolist.desktop.utils

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.utils.CollectionExportFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CollectionExportTest {
    @Test
    fun loadFailureAndCancellationNeverReplaceThePreviousExport() =
        runBlocking {
            val directory = Files.createTempDirectory("playlist-export").toFile()
            try {
                val file = directory.resolve("playlist.csv").apply { writeText("previous export") }
                assertFailsWith<IOException> {
                    writeCollectionExport(file, CollectionExportFormat.CSV) { throw IOException("Offline") }
                }
                assertFailsWith<CancellationException> {
                    writeCollectionExport(file, CollectionExportFormat.CSV) { throw CancellationException() }
                }
                assertEquals("previous export", file.readText())
                assertEquals(listOf(file), directory.listFiles()?.toList())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun successfulLoadReplacesTheExportAndCleansTemporaryFiles() =
        runBlocking {
            val directory = Files.createTempDirectory("playlist-export").toFile()
            try {
                val file = directory.resolve("playlist.csv").apply { writeText("previous export") }
                writeCollectionExport(file, CollectionExportFormat.CSV) { listOf(SongItem("track", "Song title", null, emptyList(), null)) }
                assertTrue(file.readText().contains("Song title"))
                assertEquals(listOf(file), directory.listFiles()?.toList())
            } finally {
                directory.deleteRecursively()
            }
        }
}
