package com.metrolist.desktop.storage

import kotlinx.serialization.json.Json
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SongCacheEvictionTest {
    @Test
    fun batchRemovalWritesOnceAndRetainsFailedAndUnselectedEntries() {
        val root = Files.createTempDirectory("cache-batch-removal").toFile()
        try {
            val files = List(3) { root.resolve("$it.m4a").apply { writeBytes(ByteArray(2048)) } }
            val blocked =
                root.resolve("blocked.m4a").apply {
                    mkdir()
                    resolve("child").writeText("keep")
                }
            val entries =
                (files + blocked).mapIndexed { index, file ->
                    CachedSongEntry("$index", "Song", "Artist", file.path, fileSize = 2048, cachedAt = 0)
                }
            var writes = 0
            var saved = entries
            assertFailsWith<DirectoryNotEmptyException> {
                removeCachedSongs(entries, setOf("0", "1", "3"), { path -> root.resolve(path) }) {
                    writes++
                    saved = it
                }
            }
            assertEquals(1, writes)
            assertEquals(listOf("2", "3"), saved.map { it.songId })
            assertFalse(files[0].exists())
            assertFalse(files[1].exists())
            assertTrue(files[2].exists())
            assertTrue(blocked.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun accessUpdatesCoalesceWithoutRestoringRemovedOrReplacingUpdatedEntries() {
        val access = CacheAccessTimes()
        val old = CachedSongEntry("old", "Old", "Artist", "/old", fileSize = 2048, cachedAt = 1)
        val newer = old.copy(songId = "new", cachedAt = 2, lastAccessedAt = 2)
        access.record("old", 3)
        access.record("old", 4)
        access.record("removed", 5)
        val entries = access.applyTo(listOf(old.copy(title = "Updated"), newer))
        assertEquals(4, entries.first().lastAccessedAt)
        assertEquals("Updated", entries.first().title)
        assertEquals(listOf("new", "old"), cacheEntriesByLastAccess(entries).map { it.songId })
        val restored = Json.decodeFromString<List<CachedSongEntry>>(Json.encodeToString(entries))
        assertEquals(entries, restored)
        access.clear()
        assertTrue(access.isEmpty)
        assertEquals(listOf(newer), access.applyTo(listOf(newer)))
    }

    @Test
    fun replayedEntriesOutliveNewerUnusedEntriesAndLegacyIndexesRemainReadable() {
        val legacy =
            Json.decodeFromString<CachedSongEntry>(
                """{"songId":"old","title":"Old","artist":"Artist","filePath":"/old.m4a","fileSize":2048,"cachedAt":10}""",
            )
        assertEquals(10, legacy.lastAccessedAt)
        val newer = legacy.copy(songId = "new", cachedAt = 20, lastAccessedAt = 20)
        val replayed = legacy.copy(lastAccessedAt = 30)
        val restored = Json.decodeFromString<CachedSongEntry>(Json.encodeToString(replayed))
        assertEquals(listOf("new", "old"), cacheEntriesByLastAccess(listOf(restored, newer)).map { it.songId })
    }

    @Test
    fun startupRemovesUnindexedAudioArtworkAndTemporaryFilesOnly() {
        val root = Files.createTempDirectory("cache-reconciliation").toFile()
        try {
            val audio = root.resolve("saved.m4a").apply { writeBytes(ByteArray(2048)) }
            val art = root.resolve("saved-thumb.jpg").apply { writeBytes(ByteArray(512)) }
            val orphan = root.resolve("orphan.m4a").apply { writeText("interrupted") }
            val orphanArt = root.resolve("orphan-thumb.jpg").apply { writeText("interrupted") }
            val temporary = root.resolve("orphan.1.tmp").apply { writeText("partial") }
            val index = root.resolve("cache_index_v1.json").apply { writeText("[]") }
            val other = root.resolve("unrelated.txt").apply { writeText("keep") }
            val entry = CachedSongEntry("saved", "Saved", "Artist", audio.path, art.path, 2048, 0)
            val alias = root.resolveSibling("${root.name}-alias")
            Files.createSymbolicLink(alias.toPath(), root.toPath())
            try {
                reconcileCacheDirectory(alias, listOf(entry))
            } finally {
                Files.delete(alias.toPath())
            }
            assertTrue(audio.exists())
            assertTrue(art.exists())
            assertTrue(index.exists())
            assertTrue(other.exists())
            assertFalse(orphan.exists())
            assertFalse(orphanArt.exists())
            assertFalse(temporary.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reservesOnlyTheIncomingAudioSize() {
        val mib = 1024L * 1024
        val entries = List(4) { 50 * mib }

        assertEquals(0, cacheEvictionCount(entries, 200 * mib, 256 * mib, 5 * mib))
        assertEquals(1, cacheEvictionCount(entries, 200 * mib, 256 * mib, 60 * mib))
        assertEquals(3, cacheEvictionCount(entries, 200 * mib, 256 * mib, 200 * mib))
    }

    @Test
    fun evictsOneBatchUntilTheBudgetFitsIncludingEmptyEntries() {
        assertEquals(3, cacheEvictionCount(listOf(0, 30, 20, 50), 100, 60, 10))
        assertEquals(0, cacheEvictionCount(listOf(30, 70), 100, 100, 0))
        assertEquals(2, cacheEvictionCount(listOf(30, 70), 100, 0, 0))
        assertEquals(0, cacheEvictionCount(listOf(30, 70), 100, -1, 100))
        assertEquals(0, cacheEvictionCount(emptyList(), 0, 100, 50))
    }
}
