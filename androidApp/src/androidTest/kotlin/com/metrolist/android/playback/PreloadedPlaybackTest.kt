package com.metrolist.android.playback

import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration.Companion.milliseconds

class PreloadedPlaybackTest {
    @Test
    fun pausedPreloadKeepsLatestSpeedAndPromotesWithoutResuming() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val audioFile = context.cacheDir.resolve("android-player-preload.wav")
            audioFile.writeBytes(silentWav(durationSeconds = 30))
            val songs = List(2, ::song)
            val resource = PlaybackResource(songs[0].id, audioFile.toURI().toString(), emptyMap(), null, null)
            val nextResource = resource.copy(videoId = songs[1].id)
            var player: ExoPlaybackPlayer? = null
            try {
                withContext(Dispatchers.Main) {
                    val active = ExoPlaybackPlayer(context).also { player = it }
                    active.setQueue(songs, startPlaying = false)
                    assertTrue(active.prepareAndPlay(songs[0], resource, resumePlayback = false))
                    val preparation =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            active.prepareNextTrack(nextResource, startPaused = true)
                        }
                    active.setPlaybackSpeed(1.25f)
                    val token = requireNotNull(withTimeout(10_000L.milliseconds) { preparation.await() })
                    val candidate = activeMedia3Player(active, "nextPlayer")
                    assertEquals(1.25f, candidate.playbackParameters.speed, 0f)

                    active.pause()
                    assertTrue(active.isPreloadedPlaybackActive(token))
                    active.setPlaybackSpeed(1.5f)
                    assertEquals(1.5f, candidate.playbackParameters.speed, 0f)
                    assertTrue(active.startNextPlayback())
                    assertFalse(candidate.playWhenReady)

                    assertTrue(active.swapToPreloadedQueueNext(token, nextResource))
                    assertEquals(songs[1], active.currentSong.value)
                    assertEquals(1.5f, activeMedia3Player(active).playbackParameters.speed, 0f)
                    assertFalse(active.wantsPlayback)
                }
            } finally {
                withContext(Dispatchers.Main) { player?.release() }
                audioFile.delete()
            }
        }

    @Test
    fun activePreloadedPlaybackPausesAndResumesWithThePrimary() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val audioFile = context.cacheDir.resolve("android-player-crossfade-pause.wav")
            audioFile.writeBytes(silentWav(durationSeconds = 30))
            val songs = List(2, ::song)
            val resource = PlaybackResource(songs[0].id, audioFile.toURI().toString(), emptyMap(), null, null)
            val nextResource = resource.copy(videoId = songs[1].id)
            var player: ExoPlaybackPlayer? = null
            try {
                withContext(Dispatchers.Main) {
                    val active = ExoPlaybackPlayer(context).also { player = it }
                    active.setQueue(songs, startPlaying = true)
                    assertTrue(active.prepareAndPlay(songs[0], resource))
                    requireNotNull(active.prepareNextTrack(nextResource, startPaused = true))
                    assertTrue(active.startNextPlayback())
                    val primary = activeMedia3Player(active)
                    val candidate = activeMedia3Player(active, "nextPlayer")
                    assertTrue(primary.playWhenReady)
                    assertTrue(candidate.playWhenReady)

                    active.pause()
                    assertFalse(primary.playWhenReady)
                    assertFalse(candidate.playWhenReady)

                    active.resume()
                    assertTrue(primary.playWhenReady)
                    assertTrue(candidate.playWhenReady)
                }
            } finally {
                withContext(Dispatchers.Main) { player?.release() }
                audioFile.delete()
            }
        }

    @Test
    fun preloadSurvivesChangesThatKeepTheSameNextTrack() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val audioFile = context.cacheDir.resolve("android-player-queue-preload.wav")
            audioFile.writeBytes(silentWav(durationSeconds = 30))
            val songs = List(3, ::song)
            val resource = PlaybackResource(songs[0].id, audioFile.toURI().toString(), emptyMap(), null, null)
            val nextResource = resource.copy(videoId = songs[1].id)
            var player: ExoPlaybackPlayer? = null
            try {
                withContext(Dispatchers.Main) {
                    val active = ExoPlaybackPlayer(context).also { player = it }
                    active.setQueue(songs, startPlaying = false)
                    assertTrue(active.prepareAndPlay(songs[0], resource, resumePlayback = false))
                    val token =
                        requireNotNull(
                            withTimeout(10_000L.milliseconds) { active.prepareNextTrack(nextResource, startPaused = true) },
                        )

                    active.updateQueueItems(mapOf(1 to songs[1].copy(title = "Updated")))
                    active.addToQueue(song(3))
                    active.seekTo(1_000L)
                    assertTrue(active.isPreloadedPlaybackActive(token))

                    active.moveQueueItem(1, 2)
                    assertFalse(active.isPreloadedPlaybackActive(token))
                }
            } finally {
                withContext(Dispatchers.Main) { player?.release() }
                audioFile.delete()
            }
        }

    private fun song(index: Int) =
        SongItem(
            id = "queue-$index",
            title = "Queue $index",
            thumbnail = null,
            artists = listOf(ArtistTiny(id = null, name = "Artist")),
            album = null,
        )

    private fun activeMedia3Player(
        player: ExoPlaybackPlayer,
        field: String = "exo",
    ): ExoPlayer =
        ExoPlaybackPlayer::class.java
            .getDeclaredField(field)
            .apply { isAccessible = true }
            .get(player) as ExoPlayer

    private fun silentWav(durationSeconds: Int = 1): ByteArray {
        val sampleRate = 8_000
        val pcm = ByteArray(sampleRate * 2 * durationSeconds)
        return ByteBuffer
            .allocate(44 + pcm.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray())
                putInt(36 + pcm.size)
                put("WAVEfmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(sampleRate)
                putInt(sampleRate * 2)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(pcm.size)
                put(pcm)
            }.array()
    }
}
