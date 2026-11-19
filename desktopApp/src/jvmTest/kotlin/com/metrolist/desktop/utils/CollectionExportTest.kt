package com.metrolist.desktop.utils

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.utils.CollectionExportFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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
    fun cancellationDuringRenderingKeepsThePreviousExportAndCleansTheTemporaryFile() =
        runBlocking {
            val directory = Files.createTempDirectory("playlist-export").toFile()
            try {
                val file = directory.resolve("playlist.csv").apply { writeText("previous export") }
                val export =
                    launch {
                        val job = coroutineContext.job
                        val songs =
                            object : AbstractList<SongItem>() {
                                override val size = 1

                                override fun get(index: Int): SongItem {
                                    check(index == 0)
                                    job.cancel()
                                    return SongItem("track", "Song title", null, emptyList(), null)
                                }
                            }
                        writeCollectionExport(file, CollectionExportFormat.CSV) { songs }
                    }

                export.join()
                assertTrue(export.isCancelled)
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
