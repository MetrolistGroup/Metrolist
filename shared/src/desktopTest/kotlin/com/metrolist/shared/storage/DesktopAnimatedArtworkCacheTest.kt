package com.metrolist.shared.storage

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAnimatedArtworkCacheTest {
    private fun withCache(block: (DesktopAnimatedArtworkCache, File) -> Unit) {
        val directory = Files.createTempDirectory("animated-cache-test").toFile()
        val cache = DesktopAnimatedArtworkCache(File(directory, "cache"), 4096)
        try {
            block(cache, File(directory, "download").apply { writeBytes(ByteArray(1024) { 42 }) })
        } finally {
            cache.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun replayTouchesLruAndCountsMediaOnceAcrossRestarts() =
        withCache { cache, source ->
            cache.store("first", source, cache.generation())!!.close()
            cache.store("second", source, cache.generation())!!.close()
            cache.open("first")!!.close()
            assertEquals(2048, cache.sizeBytes())
            cache.resize(1500)
            assertNull(cache.open("second"))
            cache.open("first")!!.use { assertContentEquals(source.readBytes(), it.data.toFile().readBytes()) }
            assertEquals(1024, cache.sizeBytes())
            cache.shutdown()
            val reopened = DesktopAnimatedArtworkCache(File(source.parentFile, "cache"), 1500)
            try {
                assertNotNull(reopened.open("first")).close()
                assertEquals(1024, reopened.sizeBytes())
            } finally {
                reopened.shutdown()
            }
        }

    @Test
    fun clearingKeepsEveryReaderAliveAndRejectsPendingPublication() =
        withCache { cache, source ->
            val started = cache.generation()
            val first = assertNotNull(cache.store("cover", source, started))
            val second = assertNotNull(cache.open("cover"))
            cache.clear()
            assertNull(cache.open("cover"))
            assertNull(cache.store("pending", source, started))
            assertContentEquals(source.readBytes(), first.data.toFile().readBytes())
            assertEquals(1024, cache.sizeBytes())
            first.close()
            assertTrue(second.data.toFile().exists())
            second.close()
            assertEquals(0, cache.sizeBytes())
            assertNotNull(cache.store("new", source, cache.generation())).close()
        }

    @Test
    fun disablingAndShrinkingDeferDeletionUntilPlaybackCloses() =
        withCache { cache, source ->
            val playing = assertNotNull(cache.store("cover", source, cache.generation()))
            val started = cache.generation()
            cache.resize(512)
            assertNull(cache.store("pending", source, started))
            assertContentEquals(source.readBytes(), playing.data.toFile().readBytes())
            cache.resize(0)
            assertNull(cache.open("cover"))
            assertNull(cache.store("disabled", source, cache.generation()))
            playing.close()
            assertEquals(0, cache.sizeBytes())
            cache.resize(512)
            assertNull(cache.store("oversized", source, cache.generation()))
            source.writeBytes(byteArrayOf())
            assertNull(cache.store("empty", source, cache.generation()))
            assertEquals(0, cache.sizeBytes())
        }

    @Test
    fun concurrentPlayersUseTheSamePublishedFile() =
        withCache { cache, source ->
            val first = assertNotNull(cache.store("cover", source, cache.generation()))
            val second = assertNotNull(cache.store("cover", source, cache.generation()))
            assertEquals(first.data, second.data)
            assertEquals(1024, cache.sizeBytes())
            first.close()
            assertContentEquals(source.readBytes(), second.data.toFile().readBytes())
            second.close()
        }
}
