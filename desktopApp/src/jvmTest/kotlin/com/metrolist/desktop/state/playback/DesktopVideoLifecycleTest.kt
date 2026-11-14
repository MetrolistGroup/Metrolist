package com.metrolist.desktop.state.playback

import com.metrolist.desktop.state.app.UiState
import com.metrolist.shared.api.musicvideo.CounterpartPositionMap
import com.metrolist.shared.api.musicvideo.CounterpartSegment
import com.metrolist.shared.api.musicvideo.MusicVideoCounterpart
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_OMV
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicPlayer
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.PropertiesSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopVideoLifecycleTest {
    @Test
    fun videoNeedsItsOwnVisibleWindowAndAPresentedViewer() {
        assertFalse(shouldPresentDesktopVideo(true, false, false, false))
        assertTrue(shouldPresentDesktopVideo(true, true, false, false))
        assertFalse(shouldPresentDesktopVideo(true, true, false, true))
        assertTrue(shouldPresentDesktopVideo(true, false, true, false))
        assertTrue(shouldPresentDesktopVideo(true, false, true, true))
        assertTrue(shouldPresentDesktopVideo(true, true, true, true))
        for (mini in listOf(false, true)) {
            for (fullscreen in listOf(false, true)) {
                for (expanded in listOf(false, true)) {
                    assertFalse(shouldPresentDesktopVideo(false, mini, fullscreen, expanded))
                }
            }
        }
    }

    @Before
    fun isolateSettings() {
        Settings.replaceSharedForTest(
            Settings(PropertiesSettings(Properties())).apply {
                musicVideoPlaybackEnabled = true
            },
        )
    }

    @After
    fun restoreSettings() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun hidingCancelsVideoResolutionWithoutClearingSelectionOrReportingFailure() =
        runTest {
            val previousViewer = UiState.showVideoViewer
            val previousFullscreen = UiState.showVideoFullscreen
            UiState.showVideoViewer = true
            UiState.showVideoFullscreen = false
            val job = SupervisorJob()
            val player = MusicPlayer()
            val song = SongItem("video", "Video", null, emptyList(), null, musicVideoType = MUSIC_VIDEO_TYPE_OMV)
            val started = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)
            var visible = false
            var unavailable = 0
            var requests = 0
            val video =
                DesktopVideoLifecycle(
                    scope = CoroutineScope(job + StandardTestDispatcher(testScheduler)),
                    player = player,
                    queue = MusicQueue().apply { setQueue(listOf(song)) },
                    status = { DesktopVideoPlaybackStatus(song, 1L, { false }, false, 1f, true, videoVisible = visible) },
                    onUnavailable = { unavailable++ },
                    fetchSource = { _, _ ->
                        requests++
                        val cancelled = CompletableDeferred<Unit>()
                        started.send(cancelled)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    },
                )
            try {
                video.attachBackgroundResource(song, 1L)
                video.startVideo(force = true)
                video.updateVisibility()
                assertEquals(0, requests)
                visible = true
                video.updateVisibility()
                val first = started.receive()
                visible = false
                video.updateVisibility()
                first.await()
                assertFalse(video.isVideoActive)
                assertFalse(video.isVideoResolving)
                assertTrue(UiState.showVideoViewer)
                assertEquals(0, unavailable)
                visible = true
                video.updateVisibility()
                val second = started.receive()
                assertEquals(2, requests)
                visible = false
                video.updateVisibility()
                second.await()
                assertEquals(0, unavailable)
            } finally {
                job.cancelAndJoin()
                video.close()
                player.release()
                UiState.showVideoViewer = previousViewer
                UiState.showVideoFullscreen = previousFullscreen
            }
        }

    @Test
    fun hidingDuringOriginalAudioResolutionPreservesRestoreRequestAfterRevealAndClose() =
        runTest {
            val previousViewer = UiState.showVideoViewer
            val previousFullscreen = UiState.showVideoFullscreen
            UiState.showVideoViewer = true
            UiState.showVideoFullscreen = false
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val requests = Channel<String>(Channel.UNLIMITED)
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        requests.trySend(chain.request().url.toString())
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(403)
                            .message("Forbidden")
                            .body(byteArrayOf().toResponseBody())
                            .build()
                    }.build()
            val player = MusicPlayer(mediaHttpClient = client)
            val song = SongItem("audio", "Audio", null, emptyList(), null)
            val original =
                PlaybackResource(song.id, "https://media.example.test/audio", emptyMap(), null, null, contentLengthBytes = 1024L)
            val counterpart = original.copy(url = "https://media.example.test/video-audio", musicVideoId = "video", videoUrl = "video")
            val positionMap = CounterpartPositionMap(listOf(CounterpartSegment(0L, 12_000L, 180_000L)))
            startKoin {
                modules(
                    module {
                        single {
                            MusicVideoResolver(
                                counterpartLookup = { MusicVideoCounterpart(song.id, "video", MUSIC_VIDEO_TYPE_OMV, positionMap) },
                            )
                        }
                    },
                )
            }
            val job = SupervisorJob()
            val started = CompletableDeferred<Unit>()
            val resolved = CompletableDeferred<Unit>()
            var visible = true
            var unavailable = 0
            val video =
                DesktopVideoLifecycle(
                    scope = CoroutineScope(job + StandardTestDispatcher(testScheduler)),
                    player = player,
                    queue = MusicQueue().apply { setQueue(listOf(song)) },
                    status = { DesktopVideoPlaybackStatus(song, 1L, { false }, false, 1f, true, videoVisible = visible) },
                    onUnavailable = { unavailable++ },
                    fetchSource = { requestedSong, wantVideo ->
                        assertEquals(song, requestedSong)
                        assertFalse(wantVideo)
                        started.complete(Unit)
                        resolved.await()
                        original
                    },
                    retimeLyrics = {},
                )
            try {
                assertFalse(player.playPaused(song, counterpart))
                assertEquals(counterpart.url, requests.receive())
                video.setAvailability(true)
                video.setVideoResource(counterpart)
                video.retainOriginalAudioForCounterpart(song, counterpart)
                started.await()
                visible = false
                video.updateVisibility()
                resolved.complete(Unit)
                job.children.toList().joinAll()
                assertEquals(counterpart, player.currentPlaybackResource.value)
                assertFalse(video.isVideoActive)
                assertTrue(UiState.showVideoViewer)

                visible = true
                video.updateVisibility()
                UiState.showVideoViewer = false
                video.stopVideo()
                video.restoreOriginalAudioSource()
                job.children.toList().joinAll()

                assertEquals(original.url, requests.tryReceive().getOrNull())
                assertEquals(positionMap, video.mvPositionMap)
                assertEquals(0, unavailable)
            } finally {
                job.cancelAndJoin()
                video.close()
                player.release()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
                stopKoin()
                UiState.showVideoViewer = previousViewer
                UiState.showVideoFullscreen = previousFullscreen
                Dispatchers.resetMain()
            }
        }

    @Test
    fun persistedCounterpartPositionUsesTheQueuedAudioTimeline() =
        runTest {
            val player = MusicPlayer()
            val song = SongItem("audio", "Audio", null, emptyList(), null)
            val queue = MusicQueue().apply { setQueue(listOf(song)) }
            val video =
                DesktopVideoLifecycle(
                    this,
                    player,
                    queue,
                    status = { DesktopVideoPlaybackStatus(song, 0, { false }, false, 1f, true, videoVisible = true) },
                    onUnavailable = {},
                    fetchSource = { _, _ -> null },
                )
            try {
                val audio = PlaybackResource(song.id, "audio", emptyMap(), null, null)
                player.setCurrentSong(song, audio.copy(musicVideoId = "video"))
                video.setCounterpart("video", CounterpartPositionMap(listOf(CounterpartSegment(0L, 12_000L, 180_000L))))
                assertEquals(30_000L, video.originalSongPosition(42_000L))
                player.setCurrentSong(song, audio)
                assertEquals(30_000L, video.originalSongPosition(30_000L))
            } finally {
                video.close()
                player.release()
            }
        }

    @Test
    fun rapidSkipsAndStopCancelObsoleteSourceFetches() =
        runTest {
            val previousViewer = UiState.showVideoViewer
            UiState.showVideoViewer = true
            val job = SupervisorJob()
            val player = MusicPlayer()
            var song = SongItem("video-0", "Video", null, emptyList(), null, musicVideoType = MUSIC_VIDEO_TYPE_OMV)
            var generation = 1L
            val started = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED)
            var unavailable = 0
            val video =
                DesktopVideoLifecycle(
                    scope = CoroutineScope(job + StandardTestDispatcher(testScheduler)),
                    player = player,
                    queue = MusicQueue(),
                    status = { DesktopVideoPlaybackStatus(song, generation, { false }, false, 1f, false, videoVisible = true) },
                    onUnavailable = { unavailable++ },
                    fetchSource = { _, _ ->
                        val cancelled = CompletableDeferred<Unit>()
                        started.send(cancelled)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    },
                )
            try {
                run {
                    var previous: CompletableDeferred<Unit>? = null
                    repeat(20) { index ->
                        song = song.copy(id = "video-$index")
                        generation++
                        video.attachBackgroundResource(song, generation)
                        previous?.await()
                        previous = started.receive()
                    }
                    video.stopVideo()
                    previous!!.await()
                    assertEquals(0, unavailable)
                    video.attachBackgroundResource(song, generation)
                    val last = started.receive()
                    video.close()
                    last.await()
                }
            } finally {
                job.cancelAndJoin()
                video.close()
                player.release()
                UiState.showVideoViewer = previousViewer
            }
        }

    @Test
    fun podcastEpisodeResolvesVideoOnItsOwnIdWithoutCounterpartLookup() =
        runTest {
            val previousViewer = UiState.showVideoViewer
            val previousFullscreen = UiState.showVideoFullscreen
            UiState.showVideoViewer = true
            UiState.showVideoFullscreen = false
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val job = SupervisorJob()
            val player = MusicPlayer()
            val song =
                SongItem(
                    "episode",
                    "Episode",
                    null,
                    emptyList(),
                    null,
                    musicVideoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE",
                )
            var requests = 0
            var unavailable = 0
            val video =
                DesktopVideoLifecycle(
                    scope = CoroutineScope(job + StandardTestDispatcher(testScheduler)),
                    player = player,
                    queue = MusicQueue(),
                    status = { DesktopVideoPlaybackStatus(song, 1L, { false }, false, 1f, false, videoVisible = true) },
                    onUnavailable = { unavailable++ },
                    fetchSource = { _, wantVideo ->
                        check(wantVideo)
                        requests++
                        null
                    },
                )
            try {
                run {
                    video.attachBackgroundResource(song, 1L)
                    job.children.toList().joinAll()
                    assertEquals(1, requests)
                    assertEquals(1, unavailable)
                }
            } finally {
                job.cancelAndJoin()
                video.close()
                player.release()
                UiState.showVideoViewer = previousViewer
                UiState.showVideoFullscreen = previousFullscreen
                Dispatchers.resetMain()
            }
        }
}
