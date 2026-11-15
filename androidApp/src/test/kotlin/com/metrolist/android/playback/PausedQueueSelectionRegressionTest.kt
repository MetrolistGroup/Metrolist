package com.metrolist.android.playback

import android.app.Application
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.model.SearchResultPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.QueuePlaybackRequest
import com.metrolist.shared.playback.shouldPlayAfterSongChange
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
class PausedQueueSelectionRegressionTest {
    private lateinit var player: ExoPlaybackPlayer
    private lateinit var database: MusicDatabase
    private lateinit var scope: CoroutineScope

    @get:Rule val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        Settings.shared.autoLoadMore = false
        Settings.shared.keepPausedOnSongChange = true
        Dispatchers.resetMain()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val context = RuntimeEnvironment.getApplication()
        player = ExoPlaybackPlayer(context)
        database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
    }

    @After
    fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
        if (::player.isInitialized) player.releaseAndAwaitScope()
        if (::database.isInitialized) database.close()
        Dispatchers.resetMain()
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun selectingAnotherSongWhilePausedLoadsNewAudioWithoutStartingPlayback() = verifyQueueSelection(startPlaying = false)

    @Test
    fun selectingAnotherSongWhilePlayingLoadsAndStartsNewAudio() = verifyQueueSelection(startPlaying = true)

    @Test
    fun remotePausedQueueSelectionLoadsNewAudioWithoutStartingPlayback() = verifyQueueSelection(startPlaying = false, fromRemote = true)

    private fun verifyQueueSelection(
        startPlaying: Boolean,
        fromRemote: Boolean = false,
    ) = runBlocking {
        val previous = SongItem("previous", "Previously playing", null, emptyList(), null, duration = 20L)
        val selected = previous.copy(id = "selected", title = "New selection", duration = 60L)
        player.setQueue(listOf(previous), preparePlayback = false)
        val previousResource = downloadedResource(previous.id)
        assertTrue(player.prepareAndPlay(previous, previousResource, resumePlayback = startPlaying))
        if (!startPlaying) player.pause()
        assertEquals(startPlaying, player.wantsPlayback)
        assertEquals(
            previousResource.url,
            underlyingPlayer()
                .currentMediaItem
                ?.localConfiguration
                ?.uri
                .toString(),
        )
        assertEquals(previous.id, player.currentPlaybackResource.value?.videoId)

        val requests = mutableListOf<QueuePlaybackRequest>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            player.queuePlaybackRequests.collect { requests += it }
        }
        val context = RuntimeEnvironment.getApplication()
        val coordinator =
            AndroidQueueCoordinator(
                context,
                player,
                scope,
                MutableStateFlow(Player.REPEAT_MODE_OFF),
                MutableStateFlow(false),
                {},
                {},
            )
        coordinator.transitions =
            AndroidQueueTransitions(
                player,
                scope,
                AudioCacheManager(context, database),
                MusicVideoResolver(search = { _, _ -> SearchResultPage(emptyList()) }),
                coordinator,
                { _, _ -> null },
                { _, _, _, _ -> },
                {},
                episodeResumePosition = { _, _ -> 0L },
            )

        val startPositionMs = 1_375L
        val selectionStartsPlayback =
            if (fromRemote) {
                startPlaying
            } else {
                shouldPlayAfterSongChange(
                    keepPaused = Settings.shared.keepPausedOnSongChange,
                    hasCurrentSong = player.currentSong.value != null,
                    playbackRequested = player.wantsPlayback,
                )
            }
        coordinator.playQueue(
            listOf(selected),
            startPlaying = selectionStartsPlayback,
            fromRemote = fromRemote,
            startPositionMs = startPositionMs,
        )
        assertEquals(selected.id, player.currentSong.value?.id)
        println(
            "selected=${player.currentSong.value?.id}, loaded=${player.currentPlaybackResource.value?.videoId}, " +
                "requested=${requests.map { it.song.id }}, wantsPlayback=${player.wantsPlayback}, " +
                "nativeUri=${underlyingPlayer().currentMediaItem?.localConfiguration?.uri}",
        )
        assertFalse(
            "Selecting a new song must detach the previous song's audio source",
            player.currentPlaybackResource.value?.videoId == previous.id,
        )
        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals(selected.id, request.song.id)
        assertEquals(startPlaying, request.resumePlayback)
        assertEquals(startPositionMs, request.positionMs)
        assertEquals(startPlaying, underlyingPlayer().playWhenReady)
        val selectedResource = downloadedResource(selected.id)
        assertTrue(
            player.prepareAndPlay(
                request.song,
                selectedResource,
                positionMs = request.positionMs,
                resumePlayback = request.resumePlayback,
            ),
        )
        player.finishPlaybackTransition(request.requestId)
        assertEquals(selected.id, player.currentPlaybackResource.value?.videoId)
        assertEquals(startPlaying, player.wantsPlayback)
        assertEquals(startPlaying, underlyingPlayer().playWhenReady)
        assertEquals(
            selectedResource.url,
            underlyingPlayer()
                .currentMediaItem
                ?.localConfiguration
                ?.uri
                .toString(),
        )
        assertEquals(startPositionMs, underlyingPlayer().currentPosition)
        if (!startPlaying) {
            player.resume()
            assertTrue(player.wantsPlayback)
            assertTrue(underlyingPlayer().playWhenReady)
            assertEquals(selected.id, player.currentPlaybackResource.value?.videoId)
            assertEquals(
                selectedResource.url,
                underlyingPlayer()
                    .currentMediaItem
                    ?.localConfiguration
                    ?.uri
                    .toString(),
            )
        }
    }

    private fun underlyingPlayer(): ExoPlayer =
        ExoPlaybackPlayer::class.java.getDeclaredField("exo").let {
            it.isAccessible = true
            it.get(player) as ExoPlayer
        }

    private fun downloadedResource(songId: String): PlaybackResource {
        val audio = temporaryFolder.newFile("$songId.wav")
        val pcmBytes = 48_000 * 20 * 2
        val wav = ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        wav
            .put("RIFF".toByteArray())
            .putInt(36 + pcmBytes)
            .put("WAVEfmt ".toByteArray())
            .putInt(16)
        wav
            .putShort(1)
            .putShort(1)
            .putInt(48_000)
            .putInt(96_000)
            .putShort(2)
            .putShort(16)
        wav.put("data".toByteArray()).putInt(pcmBytes)
        audio.writeBytes(wav.array())
        return PlaybackResource(songId, audio.toURI().toString(), emptyMap(), null, null)
    }
}
