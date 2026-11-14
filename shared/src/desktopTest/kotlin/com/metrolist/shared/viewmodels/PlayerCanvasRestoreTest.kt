package com.metrolist.shared.viewmodels

import coil3.ImageLoader
import coil3.PlatformContext
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.shared.api.artwork.AnimatedArtworkService
import com.metrolist.shared.api.artwork.CanvasArtworkState
import com.metrolist.shared.api.artwork.CanvasContentSource
import com.metrolist.shared.api.artwork.selectAnimatedCoverUrl
import com.metrolist.shared.api.lyrics.LyricsFetcher
import com.metrolist.shared.api.lyrics.LyricsTranslationClient
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.db.getDesktopDatabaseBuilder
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsWithProvider
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.repository.LyricsRepository
import com.metrolist.shared.settings.AnimatedCoverResolution
import com.metrolist.shared.settings.MusicVideoQuality
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.YouTubeCanvasMode
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.playback.PlaybackControllerPlayerSession
import com.metrolist.shared.ui.playback.PlaybackUiState
import com.metrolist.shared.viewmodels.PlayerViewModel.MusicVideoCanvas
import com.metrolist.shared.viewmodels.PlayerViewModel.SongUiModel
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerCanvasRestoreTest {
    @Test
    fun returnRestartsSpotifyLookupCancelledAfterAppleArtworkArrives() {
        val spotify = CompletableDeferred<String?>()
        val platform = TestPlatform(spotify = { spotify.await() })
        withPlayer(platform) { vm, controller ->
            vm.setCanvasVisible(true)
            val partial = vm.uiState.first { it.currentSong?.appleMusicAnimatedCoverUrl != null }.currentSong!!
            assertEquals(CanvasArtworkState.LOADING, partial.canvasState)
            runCurrent()
            assertNotNull(vm.uiState.value.preloadedCanvases["first"])

            controller.select(1)
            vm.uiState.first { it.currentSong?.id == "second" }
            runCurrent()
            spotify.complete("spotify-first")
            controller.select(0)
            vm.uiState.first { it.currentSong?.id == "first" }
            runCurrent()
            val restored = assertNotNull(vm.uiState.value.currentSong)
            assertTrue(restored.spotifyAnimatedCoverUrl != null || CanvasContentSource.SPOTIFY_CANVAS in restored.pendingCanvasSources)
            val ready = vm.uiState.first { it.currentSong?.spotifyAnimatedCoverUrl != null }.currentSong!!
            assertEquals("spotify-first", selectedUrl(ready))
        }
    }

    @Test
    fun returnReusesCompletedSpotifyResultsIncludingUnavailable() {
        for (url in listOf(null, "spotify-first")) {
            var lookups = 0
            val platform = TestPlatform(spotify = { if (++lookups == 1) url else error("Completed lookup repeated") })
            withPlayer(platform) { vm, controller ->
                vm.setCanvasVisible(true)
                vm.uiState.first {
                    it.currentSong?.let { song ->
                        song.canvasState == (if (url == null) CanvasArtworkState.UNAVAILABLE else CanvasArtworkState.AVAILABLE) &&
                            song.pendingCanvasSources.isEmpty()
                    } == true
                }
                runCurrent()
                controller.select(1)
                vm.uiState.first { it.currentSong?.id == "second" }
                runCurrent()
                controller.select(0)
                vm.uiState.first { it.currentSong?.id == "first" }
                runCurrent()
                assertEquals(1, lookups)
                assertEquals(url ?: "apple-album", selectedUrl(assertNotNull(vm.uiState.value.currentSong)))
            }
        }
    }

    @Test
    fun skipRetriesEmptyNeighborPreload() {
        for (failPreload in listOf(false, true)) {
            val lookups = AtomicInteger()
            val platform =
                TestPlatform(
                    apple = null,
                    neighborSpotify = {
                        if (lookups.incrementAndGet() == 1) {
                            if (failPreload) error("Spotify preload failed") else null
                        } else {
                            "spotify-second"
                        }
                    },
                )
            withPlayer(platform) { vm, controller ->
                vm.setCanvasVisible(true)
                val preloaded =
                    vm.uiState
                        .first { "second" in it.preloadedCanvases }
                        .preloadedCanvases
                        .getValue("second")
                assertNull(preloaded.spotifyAnimatedCoverUrl)
                assertNull(preloaded.appleMusicAnimatedCoverUrl)
                assertEquals(CanvasArtworkState.UNAVAILABLE, preloaded.canvasState)
                assertTrue(preloaded.pendingCanvasSources.isEmpty())
                assertEquals(1, lookups.get())

                controller.select(1)
                vm.uiState.first { it.currentSong?.id == "second" }
                runCurrent()
                val current = assertNotNull(vm.uiState.value.currentSong)
                assertTrue(current.spotifyAnimatedCoverUrl != null || CanvasContentSource.SPOTIFY_CANVAS in current.pendingCanvasSources)
                val ready = vm.uiState.first { it.currentSong?.spotifyAnimatedCoverUrl != null }.currentSong!!
                assertEquals("spotify-second", selectedUrl(ready))
                assertEquals(2, lookups.get())
            }
        }
    }

    @Test
    fun returnRestoresYouTubeCanvasInBothModes() {
        for (mode in YouTubeCanvasMode.entries) {
            val canvas =
                MusicVideoCanvas(
                    PlaybackResource("first", "video-first", emptyMap(), null, null, videoUrl = "video-first"),
                    emptyList(),
                )
            val platform = TestPlatform(video = canvas)
            withPlayer(platform, mode) { vm, controller ->
                vm.setCanvasVisible(true)
                val first = vm.uiState.first { it.currentSong?.musicVideoCanvas != null }.currentSong!!
                assertEquals("video-first", selectedUrl(first))
                runCurrent()
                controller.select(1)
                vm.uiState.first { it.currentSong?.id == "second" }
                runCurrent()
                assertNull(
                    vm.uiState.value.currentSong
                        ?.musicVideoCanvas,
                )
                controller.select(0)
                vm.uiState.first { it.currentSong?.id == "first" }
                runCurrent()
                val restored = assertNotNull(vm.uiState.value.currentSong)
                assertEquals(canvas, restored.musicVideoCanvas)
                assertEquals("video-first", selectedUrl(restored))
                assertEquals(listOf(mode), platform.videoLookups.filter { it.first == "first" }.map { it.second })
            }
        }
    }

    private fun selectedUrl(song: SongUiModel) =
        selectAnimatedCoverUrl(
            canvasUrl = song.spotifyAnimatedCoverUrl,
            musicVideoUrl = song.musicVideoCanvas?.url,
            animatedCoverUrl = song.appleMusicAnimatedCoverUrl,
            canvasState = song.canvasState,
            canvasEnabled = Settings.shared.isCanvasEnabled,
            animatedCoversEnabled = Settings.shared.enableAnimatedCovers,
            pendingSources = song.pendingCanvasSources,
            contentOrder = CanvasContentSource.parseOrder(Settings.shared.canvasContentOrder),
            enabledSources = CanvasContentSource.parseEnabled(Settings.shared.canvasEnabledSources),
        )

    private fun withPlayer(
        platform: TestPlatform,
        mode: YouTubeCanvasMode = YouTubeCanvasMode.SHORT_CLIPS,
        block: suspend TestScope.(PlayerViewModel, TestController) -> Unit,
    ) = runTest {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        Settings.shared.hasCanvasToggle = true
        Settings.shared.playerCanvasEnabled = true
        Settings.shared.enableAnimatedCovers = true
        Settings.shared.musicVideoPlaybackEnabled = platform.video != null
        Settings.shared.youtubeCanvasMode = mode
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val client =
            HttpClient(
                MockEngine {
                    if (platform.apple == null && it.url.host == "itunes.apple.com") {
                        respond("""{"results":[]}""")
                    } else {
                        error("Unexpected HTTP request")
                    }
                },
            )
        val artwork = AnimatedArtworkService(client, dispatcher)
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE).build()
        val path = Files.createTempFile("canvas-restore", ".db")
        Files.delete(path)
        val database = getDesktopDatabaseBuilder(path.toFile()).setQueryCoroutineContext(dispatcher).build()
        try {
            val controller = TestController()
            val vm =
                PlayerViewModel(
                    PlaybackControllerPlayerSession(controller, scope),
                    loader,
                    PlatformContext.INSTANCE,
                    platform,
                    LyricsRepository(UnusedLyricsFetcher, LyricsTranslationClient(client), database.dao()),
                    artwork,
                    MusicVideoResolver(),
                    scope,
                )
            vm.uiState.first { it.currentSong?.id == "first" }
            runCurrent()
            withContext(dispatcher) { block(this@runTest, vm, controller) }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            artwork.dispose()
            loader.shutdown()
            client.close()
            database.close()
            Files.deleteIfExists(path)
            Settings.replaceSharedForTest(null)
        }
    }

    private object UnusedLyricsFetcher : LyricsFetcher {
        override suspend fun getLyrics(
            title: String,
            artist: String,
            duration: Long?,
            album: String?,
            videoId: String?,
            provider: LyricsProvider,
            isVideoContext: Boolean,
        ): LyricsWithProvider? = error("Unexpected lyrics lookup")
    }

    private class TestPlatform(
        val video: MusicVideoCanvas? = null,
        val spotify: suspend () -> String? = { null },
        val neighborSpotify: suspend () -> String? = { null },
        val apple: String? = "apple-album",
    ) : PlayerPlatformServices {
        override val supportsCanvasPreloading = true
        val videoLookups = ConcurrentLinkedQueue<Pair<String, YouTubeCanvasMode>>()

        override suspend fun cachedMusicVideoCanvas(
            songId: String,
            mode: YouTubeCanvasMode,
            quality: MusicVideoQuality,
        ): MusicVideoCanvas? {
            videoLookups += songId to mode
            return if (songId == "first") video else null
        }

        override suspend fun cachedAnimatedCover(
            songId: String,
            source: CanvasContentSource,
            resolution: AnimatedCoverResolution,
            tall: Boolean,
        ): String? = if (source == CanvasContentSource.APPLE_MUSIC) apple else null

        override suspend fun spotifyCanvas(
            title: String,
            artist: String,
            album: String?,
            durationMs: Long?,
            primaryArtist: String?,
        ): String? = if (title == "first") spotify() else neighborSpotify()

        override suspend fun extractArtworkColors(
            songId: String,
            thumbnailUrl: String,
        ): PlayerArtworkColors? = null

        override fun shareText(text: String) = Unit

        override fun musicVideoCanvasHints(song: SongItem) = ContentHints()
    }

    private class TestController : PlaybackController {
        private val songs =
            listOf("first", "second").map { id ->
                SongItem(id, id, null, listOf(ArtistTiny("artist", "Artist")), AlbumTiny("album", "Album"), isUploaded = true)
            }
        override val state = MutableStateFlow(PlaybackUiState(currentSong = songs[0], queue = songs, currentQueueIndex = 0))

        fun select(index: Int) {
            state.value = state.value.copy(currentSong = songs[index], currentQueueIndex = index)
        }

        override fun play(
            song: SongItem,
            queue: List<SongItem>,
            songsOnly: Boolean,
        ) = Unit

        override fun togglePlayPause() = Unit

        override fun seekTo(positionMs: Long) = Unit

        override fun skipToNext() = Unit

        override fun skipToPrevious() = Unit

        override fun setPlaybackSpeed(speed: Float) = Unit

        override fun startRadio(song: SongItem) = Unit

        override fun playNext(songs: List<SongItem>) = Unit

        override fun addToQueue(songs: List<SongItem>) = Unit
    }
}
