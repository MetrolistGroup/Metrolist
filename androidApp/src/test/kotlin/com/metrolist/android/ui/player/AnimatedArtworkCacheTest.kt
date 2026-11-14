package com.metrolist.android.ui.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AnimatedArtworkCacheTest {
    @Test
    fun canvasKeyEncodingRetainsExistingDiskEntries() {
        assertEquals(
            "canvas-cache:///e68da079a9917bb20469a2f62b1e76045206ac4d8b4a59443b2a2bce67e8b131",
            CanvasCache.key("song", "SPOTIFY_CANVAS"),
        )
        assertEquals(
            "canvas-cache:///dafb4729f9af71a833ad7950021a67008726dad594bcd5cd9dbde66bebab8c14",
            CanvasCache.key("song", "APPLE_MUSIC", "1080"),
        )
    }

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun coversAndCanvasesShareOneLruBudgetAndClearAndDisableTogether() {
        val directory = File(context.cacheDir, "artwork-test-${System.nanoTime()}")
        val database = StandaloneDatabaseProvider(context)
        val cache = CanvasCache(directory, database, 2)
        val bytes = ByteArray(768 * 1024) { 7 }
        val canvasKey = CanvasCache.key("song", "spotify")
        val coverUrl = "https://artwork.invalid/cover.mp4"
        val upstream = DataSource.Factory { ByteArrayDataSource(bytes) }
        try {
            read(cache.factory(canvasKey, upstream), canvasKey)
            read(cache.streamingFactory(upstream), coverUrl)
            assertNotNull(cache.find(canvasKey))
            assertNotNull(cache.find(coverUrl))
            cache.setLimit(1)
            assertTrue(cache.sizeBytes() <= 1024 * 1024)
            assertNull(cache.find(canvasKey))
            assertNotNull(cache.find(coverUrl))
            cache.clear()
            assertEquals(0L, cache.sizeBytes())
            cache.setLimit(0)
            assertArrayEquals(bytes, read(cache.streamingFactory(upstream), coverUrl))
            assertArrayEquals(bytes, read(cache.factory(canvasKey, upstream), canvasKey))
            assertEquals(0L, cache.sizeBytes())
        } finally {
            cache.release()
            SimpleCache.delete(directory, database)
            database.close()
        }
    }

    @Test
    fun legacyCoverBytesAndMetadataMigrateWithoutReplacingCanvasEntries() {
        val root = File(context.cacheDir, "artwork-migration-${System.nanoTime()}").apply { mkdirs() }
        val directory = File(root, "canvas_cache")
        val legacyDirectory = File(root, "animated_cover_video_cache")
        val database = StandaloneDatabaseProvider(context)
        val cache = CanvasCache(directory, database, 2)
        val coverUrl = "https://artwork.invalid/cover.mp4"
        val canvasKey = CanvasCache.key("song", "spotify")
        val cover = ByteArray(256 * 1024) { 4 }
        val canvas = ByteArray(128 * 1024) { 9 }
        try {
            val legacy = SimpleCache(legacyDirectory, NoOpCacheEvictor(), database)
            try {
                read(
                    CacheDataSource
                        .Factory()
                        .setCache(legacy)
                        .setUpstreamDataSourceFactory { ByteArrayDataSource(cover) },
                    coverUrl,
                )
            } finally {
                legacy.release()
            }
            read(cache.factory(canvasKey, DataSource.Factory { ByteArrayDataSource(canvas) }), canvasKey)
            cache.migrateAnimatedCovers(legacyDirectory, database)
            assertFalse(legacyDirectory.exists())
            val forbidden =
                DataSource.Factory {
                    object : DataSource by ByteArrayDataSource(cover) {
                        override fun open(dataSpec: DataSpec): Long = error("Migrated artwork must not fetch the network")
                    }
                }
            assertArrayEquals(cover, read(cache.streamingFactory(forbidden), coverUrl))
            val canvasEntry = requireNotNull(cache.find(canvasKey))
            assertArrayEquals(canvas, read(cache.factory(canvasEntry.url, null), canvasEntry.url))
            cache.migrateAnimatedCovers(legacyDirectory, database)
            assertEquals((cover.size + canvas.size).toLong(), cache.sizeBytes())
        } finally {
            cache.release()
            SimpleCache.delete(directory, database)
            database.close()
            root.deleteRecursively()
        }
    }

    private fun read(
        factory: DataSource.Factory,
        uri: String,
    ): ByteArray {
        val source = factory.createDataSource()
        return try {
            source.open(DataSpec.Builder().setUri(uri).build())
            DataSourceUtil.readToEnd(source)
        } finally {
            source.close()
        }
    }
}
