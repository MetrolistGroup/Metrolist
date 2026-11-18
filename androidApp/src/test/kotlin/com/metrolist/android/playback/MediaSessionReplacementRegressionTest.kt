package com.metrolist.android.playback

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.QueuePlaybackRequest
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class MediaSessionReplacementRegressionTest {
    private lateinit var player: ExoPlaybackPlayer
    private lateinit var scope: CoroutineScope
    private var controller: MediaController? = null
    private var session: MediaSession? = null

    @get:Rule val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        Settings.shared.autoLoadMore = false
        Dispatchers.resetMain()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        player = ExoPlaybackPlayer(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        controller?.release()
        session?.release()
        if (::scope.isInitialized) scope.cancel()
        if (::player.isInitialized) player.releaseAndAwaitScope()
        Dispatchers.resetMain()
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun replacingPausedCurrentWithFollowingItemKeepsPlaybackPaused() =
        verifyReplacement(listOf("current", "following"), currentIndex = 0, fromIndex = 0)

    @Test
    fun replacingPausedCurrentBetweenNeighborsKeepsPlaybackPaused() =
        verifyReplacement(listOf("previous", "current", "following"), currentIndex = 1, fromIndex = 1)

    @Test
    fun replacingPlayingCurrentKeepsPlaybackRequested() =
        verifyReplacement(listOf("current", "following"), currentIndex = 0, fromIndex = 0, startPlaying = true)

    @Test
    fun replacingOnlyPausedItemKeepsPlaybackPaused() = verifyReplacement(listOf("current"), currentIndex = 0, fromIndex = 0)

    @Test
    fun replacingUpcomingItemLeavesPausedCurrentAudioUntouched() =
        verifyReplacement(listOf("current", "following"), currentIndex = 0, fromIndex = 1)

    @Test
    fun deletingPausedCurrentKeepsFollowingItemPaused() =
        verifyReplacement(listOf("current", "following"), currentIndex = 0, fromIndex = 0, replacementIds = emptyList())

    private fun verifyReplacement(
        initialIds: List<String>,
        currentIndex: Int,
        fromIndex: Int,
        startPlaying: Boolean = false,
        replacementIds: List<String> = listOf("replacement"),
    ) = runBlocking {
        val songs = (initialIds + replacementIds).associateWith(::song)
        val current = songs.getValue(initialIds[currentIndex])
        player.setQueue(initialIds.map(songs::getValue), startIndex = currentIndex, preparePlayback = false)
        val originalResource = localResource(current.id)
        assertTrue(player.prepareAndPlay(current, originalResource, resumePlayback = startPlaying))
        assertEquals(startPlaying, underlyingPlayer().playWhenReady)

        val requests = mutableListOf<QueuePlaybackRequest>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            player.queuePlaybackRequests.collect { requests += it }
        }
        val adapter =
            MediaSessionPlayer(
                musicPlayer = player,
                repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { false },
                isPlaybackLockedFlow = MutableStateFlow(false),
                mediaItemResolver = { songs[it.mediaId] },
                scope = scope,
            )
        assertTrue(adapter.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        val context = RuntimeEnvironment.getApplication()
        val connectedSession =
            MediaSession
                .Builder(context, adapter)
                .setCallback(
                    object : MediaSession.Callback {
                        override fun onConnect(
                            session: MediaSession,
                            controller: MediaSession.ControllerInfo,
                        ): MediaSession.ConnectionResult =
                            MediaSession.ConnectionResult.accept(
                                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,
                                automotivePlayerCommands(),
                            )
                    },
                ).build()
        session = connectedSession
        val controllerFuture = MediaController.Builder(context, connectedSession.token).buildAsync()
        awaitMain { controllerFuture.isDone }
        val connectedController = controllerFuture.get(1, TimeUnit.SECONDS)
        controller = connectedController
        assertTrue(connectedController.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        val replacementResources = replacementIds.associateWith(::localResource)
        connectedController.replaceMediaItems(
            fromIndex,
            fromIndex + 1,
            replacementIds.map {
                MediaItem
                    .Builder()
                    .setMediaId(it)
                    .setUri(replacementResources.getValue(it).url)
                    .build()
            },
        )

        val expectedIds =
            initialIds.toMutableList().apply {
                removeAt(fromIndex)
                addAll(fromIndex, replacementIds)
            }
        val expectedCurrent =
            if (fromIndex == currentIndex) expectedIds[fromIndex.coerceAtMost(expectedIds.lastIndex)] else current.id
        awaitMain { player.songQueue.value.map(SongItem::id) == expectedIds }
        assertEquals(expectedIds, player.songQueue.value.map(SongItem::id))
        assertEquals(expectedCurrent, player.currentSong.value?.id)
        if (fromIndex != currentIndex) {
            assertTrue("Replacing an upcoming item must not request a new current source", requests.isEmpty())
            assertEquals(originalResource, player.currentPlaybackResource.value)
            assertEquals(
                originalResource.url,
                underlyingPlayer()
                    .currentMediaItem
                    ?.localConfiguration
                    ?.uri
                    .toString(),
            )
        } else {
            assertTrue("Replacing or removing the current item must request its successor", requests.isNotEmpty())
            val request = requests.last()
            assertEquals(expectedCurrent, request.song.id)
            val replacementResource = replacementResources[request.song.id] ?: localResource(request.song.id)
            assertTrue(
                player.prepareAndPlay(
                    request.song,
                    replacementResource,
                    positionMs = request.positionMs,
                    resumePlayback = request.resumePlayback,
                    playWhenReadyProvider = { player.wantsPlayback },
                ),
            )
            player.finishPlaybackTransition(request.requestId)
            assertEquals(expectedCurrent, player.currentPlaybackResource.value?.videoId)
            assertEquals(
                replacementResource.url,
                underlyingPlayer()
                    .currentMediaItem
                    ?.localConfiguration
                    ?.uri
                    .toString(),
            )
        }
        println(
            "queue=${player.songQueue.value.map(SongItem::id)}, selected=${player.currentSong.value?.id}, " +
                "requests=${requests.map { it.song.id to it.resumePlayback }}, " +
                "nativePlayWhenReady=${underlyingPlayer().playWhenReady}, expectedPlayWhenReady=$startPlaying",
        )
        assertEquals("Replacing media items must preserve the existing playback intent", startPlaying, underlyingPlayer().playWhenReady)
        assertEquals(startPlaying, player.wantsPlayback)
        assertEquals(startPlaying, adapter.playWhenReady)
        if (fromIndex == currentIndex) assertEquals(startPlaying, requests.last().resumePlayback)
    }

    private fun song(id: String) = SongItem(id, id, null, emptyList(), null, duration = 20L)

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        assertTrue("Media3 controller command did not complete", condition())
    }

    private fun underlyingPlayer(): ExoPlayer =
        ExoPlaybackPlayer::class.java.getDeclaredField("exo").let {
            it.isAccessible = true
            it.get(player) as ExoPlayer
        }

    private fun localResource(songId: String): PlaybackResource {
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
