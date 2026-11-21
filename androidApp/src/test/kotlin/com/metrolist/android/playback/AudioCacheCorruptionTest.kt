package com.metrolist.android.playback

import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_CACHE
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.runBlocking
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
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AudioCacheCorruptionTest {
    @Test
    fun onlyParserFailuresOfCachedAudioCountAsCorruptCache() {
        val cached = PlaybackResource("song", "file:///cache/song", emptyMap(), null, null, profileId = CACHED_AUDIO_PROFILE_ID)
        val streamed = cached.copy(url = "https://audio.example/song", profileId = "WEB_REMIX")
        val parse =
            PlaybackException(
                "Source error",
                ParserException.createForMalformedContainer("Skipping atom with length > 2147483647 (unsupported).", null),
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            )

        assertTrue(isCorruptCachedAudioFailure(cached, parse))
        assertFalse(isCorruptCachedAudioFailure(streamed, parse))
        assertFalse(isCorruptCachedAudioFailure(cached, IOException("timeout")))
        assertFalse(isCorruptCachedAudioFailure(null, parse))
    }

    @Test
    fun corruptCacheRemovalKeepsDownloads() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            val cacheFile = File(context.cacheDir, "corrupt-cache").apply { writeBytes(ByteArray(2048)) }
            val downloadFile = File(context.cacheDir, "corrupt-download").apply { writeBytes(ByteArray(2048)) }
            try {
                val manager = AudioCacheManager(context, database)
                for ((id, file, kind) in listOf(
                    Triple("cached", cacheFile, KIND_CACHE),
                    Triple("downloaded", downloadFile, KIND_DOWNLOAD),
                )) {
                    database.dao().upsert(
                        CachedAudioEntity(
                            id,
                            id,
                            "Artist",
                            filePath = file.path,
                            fileSize = 2048,
                            cachedAt = 1,
                            lastAccessedAt = 1,
                            cacheLocation = "INTERNAL",
                            kind = kind,
                        ),
                    )
                }

                assertTrue(manager.removeCorruptCache("cached"))
                assertNull(database.dao().cachedAudio("cached"))
                assertFalse(cacheFile.exists())
                assertFalse(manager.removeCorruptCache("downloaded"))
                assertNotNull(database.dao().cachedAudio("downloaded"))
                assertTrue(downloadFile.exists())
            } finally {
                database.close()
                cacheFile.delete()
                downloadFile.delete()
            }
        }
}
