package com.metrolist.android.playback

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import com.metrolist.android.ui.player.CanvasCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class CanvasCacheFileTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun repeatsAndRefreshedStreamUrlsReuseDownloadedCanvasBytes() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val database = StandaloneDatabaseProvider(context)
            val cache = CanvasCache(temporary.newFolder("cache"), database, 2)
            val key = CanvasCache.key("song", "video", "quality")
            val bytes = ByteArray(256 * 1024) { 42 }
            var opens = 0
            val upstream =
                DataSource.Factory {
                    object : DataSource by ByteArrayDataSource(bytes) {
                        private val source = ByteArrayDataSource(bytes)

                        override fun open(dataSpec: DataSpec): Long {
                            opens++
                            return source.open(dataSpec)
                        }

                        override fun read(
                            buffer: ByteArray,
                            offset: Int,
                            length: Int,
                        ) = source.read(buffer, offset, length)

                        override fun close() = source.close()
                    }
                }
            try {
                repeat(10) { repeat ->
                    val source = cache.factory(key, upstream, identity = "same-video-and-format").createDataSource()
                    try {
                        source.open(DataSpec.Builder().setUri("https://canvas.invalid/video?signature=$repeat").build())
                        assertArrayEquals(bytes, DataSourceUtil.readToEnd(source))
                    } finally {
                        source.close()
                    }
                }
                assertEquals(1, opens)
                assertNotNull(cache.find(key))
                cache.preload(key, upstream, "same-video-and-format", uri = "https://canvas.invalid/video")
                assertEquals(1, opens)
            } finally {
                cache.release()
                database.close()
            }
        }

    @Test
    fun largePreparedFileIsCachedAndReadableAfterSourceDeletion() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val database = StandaloneDatabaseProvider(context)
            val cache = CanvasCache(temporary.newFolder("cache"), database, 128)
            val file = temporary.newFile("clips.media")
            val length = 64L * 1024 * 1024
            RandomAccessFile(file, "rw").use { it.setLength(length) }
            try {
                val entry = cache.storeClips("canvas-cache:///test", file, "video/mp4", 12000)
                assertNotNull(entry)
                assertEquals(length, cache.sizeBytes())
                file.delete()
                val source = cache.factory(entry!!.url, null).createDataSource()
                var read = 0L
                try {
                    source.open(DataSpec.Builder().setUri(entry.url).build())
                    val buffer = ByteArray(8192)
                    while (true) {
                        val size = source.read(buffer, 0, buffer.size)
                        if (size < 0) break
                        read += size
                    }
                } finally {
                    source.close()
                }
                assertEquals(length, read)
            } finally {
                cache.release()
                database.close()
            }
        }

    @Test
    fun disabledAndUndersizedCacheLeaveSourceAvailableForCallerCleanup() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val database = StandaloneDatabaseProvider(context)
            val cache = CanvasCache(temporary.newFolder("cache"), database, 0)
            val file = temporary.newFile("clips.media")
            RandomAccessFile(file, "rw").use { it.setLength(2L * 1024 * 1024) }
            try {
                assertNull(cache.storeClips("canvas-cache:///test", file, "video/mp4", 12000))
                cache.setLimit(1)
                assertNull(cache.storeClips("canvas-cache:///test", file, "video/mp4", 12000))
                assertEquals(0L, cache.sizeBytes())
                assertEquals(2L * 1024 * 1024, file.length())
            } finally {
                cache.release()
                database.close()
            }
        }
}
