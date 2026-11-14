package com.metrolist.android.ui.player

import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.platform.app.InstrumentationRegistry
import com.metrolist.shared.api.artwork.YouTubeCanvasClip
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class CanvasClipsTest {
    @Test
    fun disabledCachePreparesBoundedClipsAndFailedReadsLeaveNoPartialCanvas() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val root = File(context.cacheDir, "canvas-clips-failure-${System.nanoTime()}").apply { mkdirs() }
            val mediaDirectory = File(root, "media").apply { mkdirs() }
            val database = StandaloneDatabaseProvider(context)
            val cache = CanvasCache(File(root, "cache"), database, 0)
            val bytes =
                instrumentation.context.assets
                    .open("canvas-source.mp4")
                    .use { it.readBytes() }
            val key = CanvasCache.key("fixture", "youtube", "short-clips")
            try {
                val prepared =
                    prepareCanvasClips(
                        DataSource.Factory { ByteArrayDataSource(bytes) },
                        "https://canvas.invalid/video.mp4",
                        listOf(YouTubeCanvasClip(2000, 5000)),
                        mediaDirectory,
                        {},
                        cache,
                        key,
                    )
                assertEquals(3000L, prepared.durationMs)
                assertNull(prepared.cached)
                val temporaryFile = requireNotNull(prepared.temporaryFile)
                assertTrue(temporaryFile.length() > 0)
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(temporaryFile.absolutePath)
                    assertEquals(1, extractor.trackCount)
                    extractor.selectTrack(0)
                    assertEquals(0L, extractor.sampleTime)
                } finally {
                    extractor.release()
                }
                assertEquals(0L, cache.sizeBytes())
                assertNull(cache.find(key))
                assertTrue(temporaryFile.delete())
                assertTrue(mediaDirectory.listFiles().orEmpty().isEmpty())
                val seeks = mutableListOf<Long>()
                val bounded =
                    prepareCanvasClips(
                        DataSource.Factory { ByteArrayDataSource(bytes) },
                        "https://canvas.invalid/video.mp4",
                        listOf(YouTubeCanvasClip(90_000, 93_000)),
                        mediaDirectory,
                        seeks::add,
                        cache,
                        key,
                    )
                assertEquals(4, seeks.size)
                assertTrue(seeks.all { it in 0..29_000 })
                assertEquals(seeks, bounded.clips.map { it.startMs })
                assertEquals(12_000L, bounded.durationMs)
                assertTrue(mediaDirectory.listFiles().orEmpty().isEmpty())
                cache.setLimit(1)
                val brokenSource =
                    DataSource.Factory {
                        object : DataSource by ByteArrayDataSource(bytes) {
                            override fun read(
                                buffer: ByteArray,
                                offset: Int,
                                length: Int,
                            ): Int = throw IOException("Test source failed")
                        }
                    }
                val failure =
                    runCatching {
                        prepareCanvasClips(
                            brokenSource,
                            "https://canvas.invalid/video.mp4",
                            listOf(YouTubeCanvasClip(2000, 5000)),
                            mediaDirectory,
                            {},
                            cache,
                            key,
                        )
                    }.exceptionOrNull()
                assertNotNull(failure)
                assertNull(cache.find(key))
                assertEquals(0L, cache.sizeBytes())
                assertTrue(mediaDirectory.listFiles().orEmpty().isEmpty())
            } finally {
                cache.release()
                SimpleCache.delete(File(root, "cache"), database)
                database.close()
                root.deleteRecursively()
            }
        }

    @Test
    fun selectedClipsAreOfflineReadyWithoutCachingTheWholeVideo() =
        runBlocking {
            verifyOfflineClips("canvas-source.mp4")
            verifyOfflineClips("canvas-source.webm")
        }

    private suspend fun verifyOfflineClips(assetName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "canvas-clips-test-${System.nanoTime()}")
        val database = StandaloneDatabaseProvider(context)
        var cache = CanvasCache(directory, database, 2)
        val sourceBytes =
            instrumentation.context.assets
                .open(assetName)
                .use { it.readBytes() }
        val key = CanvasCache.key("fixture", "youtube", "short-clips")
        val seeks = mutableListOf<Long>()
        try {
            val prepared =
                prepareCanvasClips(
                    DataSource.Factory { ByteArrayDataSource(sourceBytes) },
                    "https://canvas.invalid/$assetName",
                    listOf(2L, 14L, 22L, 8L).map { YouTubeCanvasClip(it * 1000, (it + 3) * 1000) },
                    context.cacheDir,
                    seeks::add,
                    cache,
                    key,
                )
            assertEquals(listOf(2000L, 14000L, 22000L, 8000L), seeks)
            assertEquals(12000L, prepared.durationMs)
            assertNull(prepared.temporaryFile)
            val preparedSize = cache.sizeBytes()
            assertTrue(preparedSize in 1 until sourceBytes.size / 2)
            assertNotNull("Finished Canvas clips must be playable offline without the rest of the video", cache.find(key))
            assertEquals(cache.find(key), prepared.cached)
            cache.release()
            cache = CanvasCache(directory, database, 2)
            val entry = requireNotNull(cache.find(key))
            val dataSource = cache.factory(entry.url, null).createDataSource()
            val cached =
                try {
                    dataSource.open(DataSpec.Builder().setUri(entry.url).build())
                    DataSourceUtil.readToEnd(dataSource)
                } finally {
                    dataSource.close()
                }
            assertEquals(preparedSize, cached.size.toLong())
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(
                    object : MediaDataSource() {
                        override fun getSize() = cached.size.toLong()

                        override fun readAt(
                            position: Long,
                            buffer: ByteArray,
                            offset: Int,
                            size: Int,
                        ): Int {
                            if (position >= cached.size) return -1
                            val count = minOf(size, cached.size - position.toInt())
                            cached.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                            return count
                        }

                        override fun close() = Unit
                    },
                )
                assertEquals(1, extractor.trackCount)
                assertTrue(extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME)!!.startsWith("video/"))
                extractor.selectTrack(0)
                var previous = -100000L
                var frames = 0
                do {
                    assertEquals("Clip boundaries must not introduce timestamp gaps", 100000L, extractor.sampleTime - previous)
                    previous = extractor.sampleTime
                    frames++
                } while (extractor.advance())
                assertEquals(120, frames)
            } finally {
                extractor.release()
            }
        } finally {
            cache.release()
            SimpleCache.delete(directory, database)
            database.close()
        }
    }
}
