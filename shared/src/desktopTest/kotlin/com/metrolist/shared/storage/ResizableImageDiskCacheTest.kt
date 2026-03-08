package com.metrolist.shared.storage

import coil3.disk.DiskCache
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResizableImageDiskCacheTest {
    private fun withCache(block: (ResizableImageDiskCache) -> Unit) {
        val directory = Files.createTempDirectory("image-budget-test").toFile()
        val cache = ResizableImageDiskCache(directory.absolutePath.toPath(), 4096)
        try {
            block(cache)
        } finally {
            cache.shutdown()
            directory.deleteRecursively()
        }
    }

    private fun DiskCache.write(
        key: String,
        bytes: Int,
    ) {
        val editor = assertNotNull(openEditor(key))
        fileSystem.write(editor.metadata) { writeUtf8("metadata") }
        fileSystem.write(editor.data) { write(ByteArray(bytes) { 42 }) }
        editor.commit()
    }

    @Test
    fun resizePreservesEntriesAndDefersReopeningUntilReadersClose() =
        withCache { cache ->
            cache.write("cover", 512)
            val snapshot = assertNotNull(cache.openSnapshot("cover"))
            cache.resize(8192)
            assertEquals(8192, cache.maxSize)
            assertNull(cache.openEditor("other"))
            assertEquals(512, cache.fileSystem.read(snapshot.data) { readByteArray() }.size)
            snapshot.close()
            snapshot.close()
            assertNotNull(cache.openSnapshot("cover")).close()
            cache.write("other", 1024)
            assertNotNull(cache.openSnapshot("other")).close()
        }

    @Test
    fun disablingWaitsForWritesAndThenClearsAndRejectsCaching() =
        withCache { cache ->
            val editor = assertNotNull(cache.openEditor("cover"))
            cache.fileSystem.write(editor.metadata) { writeUtf8("metadata") }
            cache.fileSystem.write(editor.data) { write(ByteArray(512)) }
            cache.resize(0)
            assertNull(cache.openEditor("other"))
            val snapshot = assertNotNull(editor.commitAndOpenSnapshot())
            assertEquals(512, cache.fileSystem.read(snapshot.data) { readByteArray() }.size)
            assertNull(cache.openSnapshot("cover"))
            snapshot.close()
            assertEquals(0, cache.size)
            assertNull(cache.openSnapshot("cover"))
            cache.resize(4096)
            cache.write("new", 256)
            assertNotNull(cache.openSnapshot("new")).close()
        }

    @Test
    fun snapshotEditorTransitionsKeepTheDirectoryLeased() =
        withCache { cache ->
            cache.write("cover", 512)
            val snapshot = assertNotNull(cache.openSnapshot("cover"))
            val editor = assertNotNull(snapshot.closeAndOpenEditor())
            cache.resize(8192)
            assertNull(cache.openEditor("other"))
            editor.abort()
            assertNotNull(cache.openSnapshot("cover")).close()
            val next = assertNotNull(cache.openEditor("new"))
            cache.fileSystem.write(next.metadata) { writeUtf8("metadata") }
            cache.fileSystem.write(next.data) { write(ByteArray(128)) }
            val nextSnapshot = assertNotNull(next.commitAndOpenSnapshot())
            cache.resize(0)
            val pendingEditor = assertNotNull(nextSnapshot.closeAndOpenEditor())
            assertNull(cache.openEditor("other"))
            pendingEditor.abort()
            assertEquals(0, cache.size)
        }

    @Test
    fun smallerBudgetEvictsEntriesAndClearUsesTheLiveJournal() =
        withCache { cache ->
            cache.write("first", 1024)
            cache.write("second", 1024)
            cache.resize(1500)
            assertTrue(cache.size <= 1500)
            assertNull(cache.openSnapshot("first"))
            assertNotNull(cache.openSnapshot("second")).close()
            cache.clear()
            assertEquals(0, cache.size)
            assertNull(cache.openSnapshot("second"))
            cache.write("third", 512)
            assertNotNull(cache.openSnapshot("third")).close()
        }

    @Test
    fun latestBudgetWaitsForEveryReaderAndSurvivesReopening() =
        withCache { cache ->
            cache.write("cover", 512)
            val first = assertNotNull(cache.openSnapshot("cover"))
            val second = assertNotNull(cache.openSnapshot("cover"))
            cache.resize(2048)
            cache.resize(8192)
            first.close()
            assertNull(cache.openEditor("other"))
            second.close()
            cache.write("other", 1024)
            val directory = cache.directory
            cache.shutdown()
            val reopened = ResizableImageDiskCache(directory, 8192)
            try {
                assertNotNull(reopened.openSnapshot("cover")).close()
                assertNotNull(reopened.openSnapshot("other")).close()
            } finally {
                reopened.shutdown()
            }
        }

    @Test
    fun disabledStartupClearsOldEntriesAndNegativeLimitsAreRejected() =
        withCache { cache ->
            cache.write("old", 512)
            val directory = cache.directory
            cache.shutdown()
            val disabled = ResizableImageDiskCache(directory, 0)
            try {
                assertEquals(0, disabled.size)
                assertNull(disabled.openSnapshot("old"))
                assertFailsWith<IllegalArgumentException> { disabled.resize(-1) }
                assertEquals(0, disabled.maxSize)
            } finally {
                disabled.shutdown()
            }
        }
}
