package com.metrolist.android.playback

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.innertubex.sabr.SabrChunk
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.ffmpeg.SabrInputStream
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SabrCachePublicationTest {
    @Test
    fun publishesExactPlaybackBytesWithoutResolutionOrTransferAndPreservesRemovalGuards() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { maxSongCacheSize = 1 })
            val context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val manager = AudioCacheManager(context, database)
            val song = SongItem("song", "Song", null, emptyList(), null)
            val resource = sabrCacheTestResource().copy(expiresAt = Instant.fromEpochMilliseconds(0))
            val bytes = ByteArray(2048) { it.toByte() }
            val source = SabrInputStream(2048L, { _, _ -> flowOf(SabrChunk(bytes, 0L, true, null, null, null)) })
            try {
                source.readNBytes(bytes.size)
                manager.sabrPlaybackCache.start(song.id, resource, source, playing = false, newPlayback = true)
                manager.sabrPlaybackCache.release(source)
                source.close()
                val bootstrap = checkNotNull(resource.sabrBootstrap)
                for (mismatch in listOf(
                    resource.copy(videoId = "other"),
                    resource.copy(itag = 140),
                    resource.copy(mimeType = "audio/mp4"),
                    resource.copy(codecs = "other"),
                    resource.copy(sabrBootstrap = bootstrap.copy(audioFormat = bootstrap.audioFormat.copy(lastModified = 456L))),
                    resource.copy(sabrBootstrap = bootstrap.copy(audioTrackId = "other")),
                    resource.copy(sabrBootstrap = bootstrap.copy(contentLengthBytes = 4096L)),
                )) {
                    manager.cacheSong(song, mismatch)
                    assertFalse(manager.isCached(song.id))
                }
                manager.cacheSong(song, resource, playbackInstanceId = 1L, isCurrentSong = true)
                assertTrue(manager.isCached(song.id))
                val stored = checkNotNull(database.dao().cachedAudio(song.id))
                assertArrayEquals(bytes, File(stored.filePath).readBytes())
                assertTrue(manager.removeCache(song.id, playbackInstanceId = 1L))
                manager.cacheSong(song, resource, playbackInstanceId = 1L, isCurrentSong = true)
                assertFalse(manager.isCached(song.id))
                manager.clearCache()
                manager.cacheSong(song, resource)
                assertFalse(manager.isCached(song.id))
            } finally {
                source.close()
                manager.clearCache()
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }
}
