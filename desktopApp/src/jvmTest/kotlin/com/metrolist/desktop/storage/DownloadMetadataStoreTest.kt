package com.metrolist.desktop.storage

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class DownloadMetadataStoreTest {
    @Test
    fun failedReadBlocksMutationAndRecoveredCatalogRetainsUnavailableFiles() {
        val root = Files.createTempDirectory("download-catalog").toFile()
        val file = root.resolve("downloads.json")
        val entry = DownloadedFile("song", "Title", "Artist", null, "/unmounted/song.opus", null, 123, 1)
        try {
            file.writeText("broken JSON")
            val store = DownloadMetadataStore(file)
            assertFails { store.load() }
            assertFails { store.mutate { it.clear() } }
            assertEquals("broken JSON", file.readText())
            file.delete()
            file.mkdir()
            assertFails { store.mutate { it.clear() } }
            assertTrue(file.isDirectory)
            file.delete()
            DownloadMetadataStore(file).mutate { it += entry }
            assertEquals(listOf(entry), store.mutate { it += entry.copy(songId = "second") }.take(1))
            assertEquals(listOf("song", "second"), DownloadMetadataStore(file).load().map { it.songId })
        } finally {
            root.deleteRecursively()
        }
    }
}
