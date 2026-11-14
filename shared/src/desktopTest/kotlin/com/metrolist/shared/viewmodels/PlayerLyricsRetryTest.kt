package com.metrolist.shared.viewmodels

import coil3.ImageLoader
import coil3.PlatformContext
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.api.artwork.AnimatedArtworkService
import com.metrolist.shared.api.lastfm.LastFM
import com.metrolist.shared.api.lyrics.LyricsFetcher
import com.metrolist.shared.api.lyrics.LyricsTranslationClient
import com.metrolist.shared.api.lyrics.LyricsTranslationFailure
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.api.lyrics.MlxBuilder
import com.metrolist.shared.api.lyrics.MlxWord
import com.metrolist.shared.api.lyrics.YouTubeLyricsApi
import com.metrolist.shared.api.lyrics.YouTubeTranscriptCue
import com.metrolist.shared.api.lyrics.lyricsTimingKind
import com.metrolist.shared.api.musicvideo.CounterpartPositionMap
import com.metrolist.shared.api.musicvideo.CounterpartSegment
import com.metrolist.shared.api.musicvideo.MusicVideoCounterpart
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.LyricsEntity
import com.metrolist.shared.db.getDesktopDatabaseBuilder
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsProviderResult
import com.metrolist.shared.model.LyricsTimingKind
import com.metrolist.shared.model.LyricsWithProvider
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.playback.PlaybackSeekSource
import com.metrolist.shared.playback.artistShelfQueue
import com.metrolist.shared.repository.LOCAL_LYRICS_PROVIDER
import com.metrolist.shared.repository.LyricsRepository
import com.metrolist.shared.repository.USER_LYRICS_PROVIDER
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.playback.PlaybackControllerPlayerSession
import com.metrolist.shared.ui.playback.PlaybackUiState
import com.metrolist.shared.ui.player.PlayerTrackArtwork
import com.metrolist.shared.ui.player.PlayerTrackPresentation
import com.metrolist.shared.ui.player.withPlayerTrackArtwork
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerLyricsRetryTest {
    @Test
    fun failedManualTranslationClearsProgressAndAllowsAnotherAttempt() {
        var requests = 0
        HttpClient(
            MockEngine {
                requests++
                respond("unauthorized", HttpStatusCode.Unauthorized)
            },
        ).use { client ->
            withLyricsPlayer(translationClient = LyricsTranslationClient(client)) { vm, controller, _, _ ->
                val song = requireNotNull(controller.state.value.currentSong)
                vm.loadLyrics(song.id, song.title)
                val original = vm.uiState.first { it.lyrics.isNotEmpty() && !it.isLyricsLoading }.lyrics
                Settings.shared.lyricsAiTranslationEnabled = true
                Settings.shared.lyricsAiActivationMode = "Manual"
                Settings.shared.lyricsAiProvider = "OpenRouter"
                Settings.shared.lyricsAiApiKey = "test-key"
                Settings.shared.lyricsAiTargetLanguage = "fr"
                repeat(2) {
                    vm.setManualLyricsTranslation(true)
                    assertEquals(LyricsTranslationFailure.AUTHENTICATION, vm.lyricsTranslationFailures.first())
                    val cleared = vm.uiState.first { !it.manualAiTranslationEnabled && !it.lyricsTranslationProgress.isTranslating }
                    assertEquals(original, cleared.lyrics)
                    assertFalse(cleared.isLyricsLoading)
                }
                assertEquals(2, requests)
            }
        }
    }

    @Test
    fun refetchBypassesCachedLyricsAndKeepsThemWhenProvidersReturnNothing() {
        val requested = CompletableDeferred<Unit>()
        val response = CompletableDeferred<LyricsWithProvider?>()
        withLyricsPlayer(audioResponse = {
            requested.complete(Unit)
            response.await()
        }) { vm, controller, database, requests ->
            val song = requireNotNull(controller.state.value.currentSong)
            val cached = LyricsEntity(song.id, "[00:01]Cached lyrics", "LRCLIB", offsetMs = 350)
            database.dao().saveLyrics(cached)
            vm.loadLyrics(song.id, song.title)
            val before = vm.uiState.first { it.rawLyrics == cached.lyrics && !it.isLyricsLoading }
            assertTrue(requests.isEmpty())

            vm.loadLyrics(song.id, song.title, skipCache = true)
            requested.await()
            assertTrue(vm.uiState.value.isLyricsLoading)
            assertEquals(before.lyrics, vm.uiState.value.lyrics)
            response.complete(null)
            val after = vm.uiState.first { !it.isLyricsLoading }
            assertTrue(requests.isNotEmpty())
            assertEquals(before.lyrics, after.lyrics)
            assertEquals(before.rawLyrics, after.rawLyrics)
            assertEquals(before.lyricsProvider, after.lyricsProvider)
            assertEquals(before.lyricsOffsetMs, after.lyricsOffsetMs)
            assertEquals(cached, database.dao().cachedLyrics(song.id))
        }
    }

    @Test
    fun refetchReplacesCachedLyricsOnlyAfterTheNewResultArrives() {
        val requested = CompletableDeferred<Unit>()
        val response = CompletableDeferred<LyricsWithProvider>()
        withLyricsPlayer(audioResponse = {
            requested.complete(Unit)
            response.await()
        }) { vm, controller, database, _ ->
            val song = requireNotNull(controller.state.value.currentSong)
            database.dao().saveLyrics(LyricsEntity(song.id, "[00:01]Cached lyrics", "LRCLIB"))
            vm.loadLyrics(song.id, song.title)
            val before = vm.uiState.first { it.rawLyrics != null && !it.isLyricsLoading }
            vm.loadLyrics(song.id, song.title, skipCache = true)
            requested.await()
            assertTrue(vm.uiState.value.isLyricsLoading)
            assertEquals(before.lyrics, vm.uiState.value.lyrics)
            response.complete(LyricsWithProvider("[00:02]Fresh lyrics", "LRCLIB"))
            val after = vm.uiState.first { !it.isLyricsLoading }
            assertEquals("Fresh lyrics", after.lyrics.single().text)
            assertEquals(after.rawLyrics, database.dao().cachedLyrics(song.id)?.lyrics)
        }
    }

    @Test
    fun artistShelfPlaybackBeforeOpeningThePlayerKeepsMetadataWhileArtworkLoads() =
        withLyricsPlayer { vm, controller, _, _ ->
            Settings.shared.enableAnimatedCovers = true
            val song =
                requireNotNull(controller.state.value.currentSong).copy(
                    id = "vY2nH6eZ_FY",
                    title = "Cold Reactor",
                    artists = listOf(ArtistTiny("artist", "Everything Everything")),
                    thumbnail = "https://example.com/cold-reactor.jpg",
                    duration = 231,
                )
            val next = song.copy(id = "next", title = "Distant Past", thumbnail = null)
            val queue = artistShelfQueue(song, mapOf("Top tracks" to listOf(song, next)))
            controller.play(song, queue)
            val state = vm.uiState.first { it.currentSong?.id == song.id && it.queueItems.size == queue.size }
            assertEquals(song.title, state.currentSong?.title)
            assertEquals(song.thumbnail, state.currentSong?.thumbnail)
            assertEquals(queue.map { it.id }, state.queueItems.map { it.id })
            assertEquals(0, state.currentQueueIndex)

            val requests = mutableListOf<String>()
            val failedClient =
                HttpClient(
                    MockEngine { request ->
                        requests += request.url.encodedPath
                        respond(
                            "{}",
                            if (request.url.host == "www.last.fm") HttpStatusCode(600, "Failed") else HttpStatusCode.BadRequest,
                        )
                    },
                ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
            try {
                val repository = YouTubeRepository(InnerTube(failedClient))
                assertNull(repository.getSongAlbum(song.id))
                assertNull(repository.getSongMetadata(song.id))
                assertTrue(LastFM.getArtistPhotos(failedClient, song.artists.single().name, renderPage = null).isEmpty())
                assertTrue(requests.any { it.endsWith("/next") }, requests.toString())
                assertTrue(requests.any { it.endsWith("/get_queue") }, requests.toString())
                assertTrue(requests.any { it.endsWith("/+images") }, requests.toString())
                assertEquals(song, controller.state.value.currentSong)
                assertEquals(
                    song.title,
                    vm.uiState.value.currentSong
                        ?.title,
                )
            } finally {
                failedClient.close()
            }

            coroutineScope {
                val requested = CompletableDeferred<Unit>()
                val artwork = CompletableDeferred<PlayerTrackArtwork>()
                var presentation = PlayerTrackPresentation()
                val collection =
                    vm.uiState
                        .map { it.currentSong }
                        .distinctUntilChanged()
                        .withPlayerTrackArtwork {
                            requested.complete(Unit)
                            artwork.await()
                        }.onEach { presentation = it }
                        .launchIn(this)
                try {
                    requested.await()
                    delay(1)
                    assertEquals(song.id, presentation.song?.id)
                    assertEquals(song.title, presentation.song?.title)
                    assertEquals(song.artists, presentation.song?.artists)
                    assertEquals(song.thumbnail, presentation.artwork.url)
                    artwork.complete(PlayerTrackArtwork(song.thumbnail))
                    delay(1)
                    assertEquals(song.id, presentation.song?.id)
                } finally {
                    collection.cancelAndJoin()
                }
            }
        }

    @Test
    fun manualTranslationKeepsTheChosenSourceAndItsVideoTimeline() {
        lateinit var repository: LyricsRepository
        withLyricsPlayer(onRepository = { repository = it }) { vm, controller, database, requests ->
            val song = requireNotNull(controller.state.value.currentSong)
            val source = "[00:02]Chosen lyrics"
            vm.selectLyricsProviderResult(
                song.id,
                LyricsProviderResult(LyricsProvider.PAXSENIX, "Chosen", source, LyricsTimingKind.LINE_SYNCED),
            )
            vm.uiState.first { it.lyricsUserSelected && it.rawLyrics == source }
            controller.state.value = controller.state.value.copy(isVideoModeActive = true)
            val retimed = vm.uiState.first { it.mvLyricsRetimed }
            Settings.shared.lyricsAiTranslationEnabled = true
            Settings.shared.lyricsAiActivationMode = "Manual"
            Settings.shared.lyricsAiTargetLanguage = "fr"
            val cached = requireNotNull(database.dao().cachedLyrics(song.id))
            database.dao().saveLyrics(
                cached.copy(
                    translatedLyrics = "Paroles choisies",
                    translationLanguage = "fr",
                    translationMode = Settings.shared.lyricsAiTranslationMode,
                    translationSourceHash = "Chosen lyrics".hashCode().toString(),
                    translationConfigKey = repository.buildTranslationConfigKey() + "|passages-v1|true",
                ),
            )
            val providerRequests = requests.toList()

            vm.setManualLyricsTranslation(true)
            val translated = vm.uiState.first { it.lyrics.singleOrNull()?.translatedText == "Paroles choisies" }
            assertEquals(source, translated.rawLyrics)
            assertEquals("Chosen", translated.lyricsProvider)
            assertTrue(translated.lyricsUserSelected)
            assertTrue(translated.mvLyricsRetimed)
            assertTrue(translated.manualAiTranslationEnabled)
            assertEquals(retimed.lyrics.map { it.time }, translated.lyrics.map { it.time })
            assertEquals(providerRequests, requests.toList())
            assertEquals(source, database.dao().cachedLyrics(song.id)?.lyrics)
        }
    }

    @Test
    fun aTranslationCompletionCannotReplaceANewerSourceSelection() {
        val preparing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withLyricsPlayer(prepareJapaneseDictionary = {
            preparing.complete(Unit)
            withContext(NonCancellable) { release.await() }
            false
        }) { vm, controller, _, _ ->
            try {
                val song = requireNotNull(controller.state.value.currentSong)
                vm.selectLyricsProviderResult(
                    song.id,
                    LyricsProviderResult(LyricsProvider.PAXSENIX, "First", "あい", LyricsTimingKind.UNSYNCED),
                )
                vm.uiState.first { it.rawLyrics == "あい" }
                Settings.shared.lyricsRomanizationEnabled = true
                Settings.shared.lyricsRomanizeLanguages = "Japanese:true"
                vm.setManualLyricsTranslation(true)
                preparing.await()
                vm.selectLyricsProviderResult(
                    song.id,
                    LyricsProviderResult(LyricsProvider.PAXSENIX, "Second", "New selection", LyricsTimingKind.UNSYNCED),
                )
                release.complete(Unit)
                val selected = vm.uiState.first { it.rawLyrics == "New selection" }
                assertEquals("Second", selected.lyricsProvider)
                assertEquals("New selection", selected.lyrics.single().text)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun playerLyricsFetchUncachedLinesWithoutOpeningFullLyricsAndReuseTheRequest() =
        withLyricsPlayer(audioResponse = { LyricsWithProvider("[00:02]Fetched lyrics", "LRCLIB") }) { vm, controller, _, requests ->
            val song = requireNotNull(controller.state.value.currentSong)
            assertFalse(vm.uiState.value.isLyricsVisible)
            assertFalse(vm.uiState.value.isSubtitleActive)
            vm.loadLyrics(song.id, song.title)
            val fetched = vm.uiState.first { it.rawLyricsProvider == "LRCLIB" && !it.isLyricsLoading }
            assertEquals("Fetched lyrics", fetched.lyrics.single().text)
            vm.loadLyrics(song.id, song.title)
            assertEquals(listOf("song:LRCLIB"), requests.toList())
            assertFalse(vm.uiState.value.isLyricsVisible)
        }

    @Test
    fun cacheOnlyPlayerLineKeepsSavedLyricsUntilTheLyricsViewRequestsProviders() =
        withLyricsPlayer(audioResponse = { LyricsWithProvider("[00:02]Fetched lyrics", "LRCLIB") }) { vm, controller, database, requests ->
            val song = requireNotNull(controller.state.value.currentSong)
            database.dao().saveLyrics(LyricsEntity(song.id, "[00:01]Cached lyrics", "PAXSENIX", offsetMs = 350))
            vm.loadLyrics(song.id, song.title, cacheOnly = true)
            val cached = vm.uiState.first { it.rawLyricsProvider == "PAXSENIX" }
            assertEquals("Cached lyrics", cached.lyrics.single().text)
            assertEquals(350, cached.lyricsOffsetMs)
            assertTrue(requests.isEmpty())

            vm.loadLyrics(song.id, song.title)
            val fetched = vm.uiState.first { it.rawLyricsProvider == "LRCLIB" }
            assertEquals("Fetched lyrics", fetched.lyrics.single().text)
            assertEquals(listOf("song:LRCLIB"), requests.toList())
        }

    @Test
    fun lyricsPublishBeforeDictionaryPreparationAndKeepTheirTimelineAfterEnrichment() {
        val preparing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withLyricsPlayer(
            audioResponse = { LyricsWithProvider("[00:01]あい\n[00:02]うえ", "LRCLIB") },
            prepareJapaneseDictionary = {
                preparing.complete(Unit)
                release.await()
                false
            },
        ) { vm, controller, _, _ ->
            Settings.shared.lyricsRomanizationEnabled = true
            Settings.shared.lyricsRomanizeLanguages = "Japanese:true"
            val song = requireNotNull(controller.state.value.currentSong)
            vm.loadLyrics(song.id, song.title)
            preparing.await()
            val plain = vm.uiState.value
            assertEquals(listOf("あい", "うえ"), plain.lyrics.map { it.text })
            assertTrue(plain.lyrics.all { it.romanizedText == null })
            assertFalse(plain.isLyricsLoading)
            vm.setLyricsOffset(song.id, 350)
            release.complete(Unit)
            val enriched = vm.uiState.first { it.lyrics.firstOrNull()?.romanizedText == "ai" }
            assertEquals(plain.lyrics.map { it.time }, enriched.lyrics.map { it.time })
            assertEquals(1, LyricsUtils.findCurrentLineIndex(enriched.lyrics, 2_500))
            assertEquals(plain.rawLyrics, enriched.rawLyrics)
            assertEquals(plain.lyricsProvider, enriched.lyricsProvider)
            assertEquals(350, enriched.lyricsOffsetMs)
            assertFalse(enriched.isLyricsLoading)
        }
    }

    @Test
    fun cachedTranslationStaysVisibleWhilePreferredCandidatePreparesItsDictionary() {
        val preparationCalls = AtomicInteger()
        val preparingAgain = CompletableDeferred<Unit>()
        val releaseProvider = CompletableDeferred<Unit>()
        val preparing = CompletableDeferred<Unit>()
        val releaseDictionary = CompletableDeferred<Unit>()
        val fallback = MlxBuilder().line(1_000, "Original lyrics").build() + "\n[translatedLyrics]\n[00:01.000]Traduction"
        val replacement = "[00:03]あい"
        withLyricsPlayer(
            audioResponse = {
                releaseProvider.await()
                LyricsWithProvider(replacement, "LRCLIB")
            },
            prepareJapaneseDictionary = {
                if (preparationCalls.incrementAndGet() == 2) preparingAgain.complete(Unit)
                preparing.complete(Unit)
                releaseDictionary.await()
                false
            },
        ) { vm, controller, database, _ ->
            try {
                Settings.shared.lyricsRomanizationEnabled = true
                Settings.shared.lyricsRomanizeLanguages = "Japanese:true"
                val song = requireNotNull(controller.state.value.currentSong)
                database.dao().saveLyrics(LyricsEntity(song.id, fallback, "PAXSENIX"))
                vm.loadLyrics(song.id, song.title)
                val translated =
                    vm.uiState.first { it.lyrics.singleOrNull()?.translatedText == "Traduction" }
                releaseProvider.complete(Unit)
                preparing.await()
                assertEquals(translated.lyrics, vm.uiState.value.lyrics)
                assertEquals(fallback, vm.uiState.value.rawLyrics)
                assertEquals("PAXSENIX", vm.uiState.value.lyricsProvider)
                assertFalse(vm.uiState.value.isLyricsLoading)

                vm.loadLyrics(song.id, song.title, skipCache = true)
                preparingAgain.await()
                assertEquals(translated.lyrics, vm.uiState.value.lyrics)
                assertTrue(vm.uiState.value.isLyricsLoading)

                releaseDictionary.complete(Unit)
                val preferred = vm.uiState.first { it.rawLyrics == replacement }
                assertEquals("あい", preferred.lyrics.single().text)
                assertEquals("ai", preferred.lyrics.single().romanizedText)
                assertEquals("LRCLIB", preferred.lyricsProvider)
            } finally {
                releaseProvider.complete(Unit)
                releaseDictionary.complete(Unit)
            }
        }
    }

    @Test
    fun retimedCachedLyricsStayVisibleWhilePreferredCandidatePreparesItsDictionary() {
        val requested = CompletableDeferred<Unit>()
        val releaseProvider = CompletableDeferred<Unit>()
        val preparing = CompletableDeferred<Unit>()
        val releaseDictionary = CompletableDeferred<Unit>()
        val replacement = "[00:03]あい"
        withLyricsPlayer(
            audioResponse = {
                requested.complete(Unit)
                releaseProvider.await()
                LyricsWithProvider(replacement, "LRCLIB")
            },
            prepareJapaneseDictionary = {
                preparing.complete(Unit)
                releaseDictionary.await()
                false
            },
        ) { vm, controller, database, _ ->
            try {
                Settings.shared.lyricsRomanizationEnabled = true
                Settings.shared.lyricsRomanizeLanguages = "Japanese:true"
                val song = requireNotNull(controller.state.value.currentSong)
                database.dao().saveLyrics(LyricsEntity(song.id, "[00:01]Cached lyrics", "PAXSENIX"))
                vm.loadLyrics(song.id, song.title)
                requested.await()
                controller.state.value = controller.state.value.copy(isVideoModeActive = true)
                val cachedVideo = vm.uiState.first { it.mvLyricsRetimed }
                assertEquals(6_000L, cachedVideo.lyrics.single().time)
                assertEquals("PAXSENIX", cachedVideo.lyricsProvider)

                releaseProvider.complete(Unit)
                preparing.await()
                assertEquals(cachedVideo.lyrics, vm.uiState.value.lyrics)
                assertEquals(cachedVideo.rawLyrics, vm.uiState.value.rawLyrics)
                assertEquals(cachedVideo.lyricsProvider, vm.uiState.value.lyricsProvider)
                assertTrue(vm.uiState.value.mvLyricsRetimed)
                assertFalse(vm.uiState.value.isLyricsLoading)

                releaseDictionary.complete(Unit)
                val preferredVideo =
                    vm.uiState.first { it.rawLyrics == replacement && it.mvLyricsRetimed }
                assertEquals(8_000L, preferredVideo.lyrics.single().time)
                assertEquals("ai", preferredVideo.lyrics.single().romanizedText)
                assertEquals("LRCLIB", preferredVideo.lyricsProvider)
            } finally {
                releaseProvider.complete(Unit)
                releaseDictionary.complete(Unit)
            }
        }
    }

    @Test
    fun dictionaryCompletionPreservesVideoLyricsDuringBothAudioEnrichmentPasses() {
        val preparing = List(4) { CompletableDeferred<Unit>() }
        val release = List(4) { CompletableDeferred<Unit>() }
        val calls = AtomicInteger()
        withLyricsPlayer(
            audioResponse = { LyricsWithProvider("[00:01]あい", "LRCLIB") },
            videoResponse = { LyricsWithProvider("[00:20]うえ", "SimpMusic", isUserSelected = true) },
            prepareJapaneseDictionary = {
                val index = calls.getAndIncrement()
                preparing.getOrNull(index)?.complete(Unit)
                release.getOrNull(index)?.await()
                false
            },
        ) { vm, controller, _, _ ->
            try {
                Settings.shared.lyricsRomanizationEnabled = true
                Settings.shared.lyricsRomanizeLanguages = "Japanese:true"
                val song = requireNotNull(controller.state.value.currentSong)
                vm.loadLyrics(song.id, song.title)
                preparing[0].await()
                assertEquals(
                    1_000L,
                    vm.uiState.value.lyrics
                        .single()
                        .time,
                )
                controller.state.value = controller.state.value.copy(isVideoModeActive = true)
                release[1].complete(Unit)
                vm.uiState.first { it.mvLyricsRetimed }
                val video = vm.uiState.value
                assertEquals(20_000L, video.lyrics.single().time)
                assertEquals("SimpMusic", video.lyricsProvider)

                for ((completed, next) in listOf(0 to 2, 2 to 3)) {
                    release[completed].complete(Unit)
                    preparing[next].await()
                    assertEquals(video.lyrics, vm.uiState.value.lyrics)
                    assertEquals(video.lyricsProvider, vm.uiState.value.lyricsProvider)
                    assertEquals(video.rawLyrics, vm.uiState.value.rawLyrics)
                    assertTrue(vm.uiState.value.mvLyricsRetimed)
                }
            } finally {
                release.forEach { it.complete(Unit) }
            }
        }
    }

    @Test
    fun lateDictionaryPreparationCannotReplaceAManualSelection() {
        val preparing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withLyricsPlayer(
            audioResponse = { LyricsWithProvider("[00:01]あい", "LRCLIB") },
            prepareJapaneseDictionary = {
                preparing.complete(Unit)
                withContext(NonCancellable) { release.await() }
                false
            },
        ) { vm, controller, _, _ ->
            try {
                Settings.shared.lyricsRomanizationEnabled = true
                Settings.shared.lyricsRomanizeLanguages = "Japanese:true"
                val song = requireNotNull(controller.state.value.currentSong)
                vm.loadLyrics(song.id, song.title)
                preparing.await()
                assertEquals(
                    "あい",
                    vm.uiState.value.lyrics
                        .single()
                        .text,
                )
                vm.selectLyricsProviderResult(
                    song.id,
                    LyricsProviderResult(LyricsProvider.LYPRIO, "Chosen", "My chosen lyrics", LyricsTimingKind.UNSYNCED),
                )
                release.complete(Unit)
                vm.uiState.first { it.lyricsUserSelected && it.rawLyrics == "My chosen lyrics" }
                assertEquals(
                    "My chosen lyrics",
                    vm.uiState.value.lyrics
                        .single()
                        .text,
                )
                assertEquals("Chosen", vm.uiState.value.lyricsProvider)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun cachedLyricsBecomeReadyWhileOfflineSongwriterLookupIsPending() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            Settings.shared.enableAnimatedCovers = false
            Settings.shared.lyricsRomanizationEnabled = true
            Settings.shared.lyricsRomanizeLanguages = "Korean:true"
            Settings.shared.lyricsAiTranslationEnabled = true
            Settings.shared.lyricsAiTargetLanguage = "fr"
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val client = HttpClient(MockEngine { error("Unexpected network request") })
            val imageLoader = ImageLoader.Builder(PlatformContext.INSTANCE).build()
            val path = Files.createTempFile("metrolist-offline-lyrics", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).setQueryCoroutineContext(dispatcher).build()
            val songwriterRequested = CompletableDeferred<Unit>()
            val api =
                object : YouTubeLyricsApi {
                    override suspend fun browse(browseId: String): JsonObject {
                        songwriterRequested.complete(Unit)
                        awaitCancellation()
                    }

                    override suspend fun watchNext(videoId: String): JsonObject = error("Unexpected request")

                    override suspend fun player(videoId: String): JsonObject = error("Unexpected request")

                    override suspend fun transcript(videoId: String): List<YouTubeTranscriptCue> = error("Unexpected request")

                    override suspend fun getText(url: String): String = error("Unexpected request")
                }
            val fetcher =
                object : LyricsFetcher {
                    override suspend fun getLyrics(
                        title: String,
                        artist: String,
                        duration: Long?,
                        album: String?,
                        videoId: String?,
                        provider: LyricsProvider,
                        isVideoContext: Boolean,
                    ): LyricsWithProvider = error("Cached lyrics must not require a provider request")
                }
            try {
                val song = SongItem("song", "Song", null, emptyList(), AlbumTiny("album", "Album"))
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client), database.dao(), api)
                database.dao().saveLyrics(
                    LyricsEntity(
                        song.id,
                        "[00:01.00]안녕",
                        "LRCLIB",
                        translatedLyrics = "Bonjour",
                        translationLanguage = "fr",
                        translationMode = Settings.shared.lyricsAiTranslationMode,
                        translationSourceHash = "안녕".hashCode().toString(),
                        translationConfigKey = repository.buildTranslationConfigKey() + "|passages-v1|false",
                        isUserSelected = true,
                    ),
                )
                val vm =
                    withContext(dispatcher) {
                        PlayerViewModel(
                            PlaybackControllerPlayerSession(TestController(song), scope),
                            imageLoader,
                            PlatformContext.INSTANCE,
                            TestPlatform,
                            repository,
                            AnimatedArtworkService(client),
                            MusicVideoResolver(),
                            scope,
                        )
                    }
                vm.uiState.first { it.currentSong?.id == song.id }
                withContext(dispatcher) { vm.loadLyrics(song.id, song.title) }
                songwriterRequested.await()
                withContext(dispatcher) {
                    assertEquals(
                        "안녕",
                        vm.uiState.value.lyrics
                            .single()
                            .text,
                    )
                    assertEquals(
                        "annyeong",
                        vm.uiState.value.lyrics
                            .single()
                            .romanizedText,
                    )
                    assertEquals(
                        "Bonjour",
                        vm.uiState.value.lyrics
                            .single()
                            .translatedText,
                    )
                    assertFalse(vm.uiState.value.isLyricsLoading)
                }
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
                imageLoader.shutdown()
                client.close()
                database.close()
                Files.deleteIfExists(path)
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun failedRequestCanRetryAndSuccessfulRequestStaysDeduplicated() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            Settings.shared.enableAnimatedCovers = false
            Settings.shared.lyricsRomanizationEnabled = false
            Settings.shared.lyricsAiTranslationEnabled = false
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val client = HttpClient(MockEngine { error("Unexpected network request") })
            val imageLoader = ImageLoader.Builder(PlatformContext.INSTANCE).build()
            val calls = AtomicInteger()
            val available = AtomicBoolean(false)
            val fetched = CompletableDeferred<Unit>()
            val fetcher =
                object : LyricsFetcher {
                    override suspend fun getLyrics(
                        title: String,
                        artist: String,
                        duration: Long?,
                        album: String?,
                        videoId: String?,
                        provider: LyricsProvider,
                        isVideoContext: Boolean,
                    ): LyricsWithProvider {
                        calls.incrementAndGet()
                        fetched.complete(Unit)
                        check(available.get()) { "Offline" }
                        return LyricsWithProvider(
                            MlxBuilder()
                                .meta(provider.name)
                                .line(1_000, 2_000, words = listOf(MlxWord("Recovered lyrics", 1_000, 2_000)))
                                .build(),
                            provider.name,
                        )
                    }
                }
            try {
                val song = SongItem("song", "Song", null, emptyList(), AlbumTiny("album", "Album"), customTitle = "Edited song")
                val controller = TestController(song)
                val vm =
                    withContext(dispatcher) {
                        PlayerViewModel(
                            PlaybackControllerPlayerSession(controller, scope),
                            imageLoader,
                            PlatformContext.INSTANCE,
                            TestPlatform,
                            LyricsRepository(fetcher, LyricsTranslationClient(client)),
                            AnimatedArtworkService(client),
                            MusicVideoResolver(),
                            scope,
                        )
                    }
                vm.uiState.first { it.currentSong?.id == song.id && it.queueItems.isNotEmpty() }
                assertEquals(
                    "Edited song",
                    vm.uiState.value.queueItems
                        .single()
                        .title,
                )
                withContext(UnconfinedTestDispatcher(testScheduler)) {
                    vm.loadLyrics(song.id, song.title)
                    vm.uiState.first { it.isLyricsLoading }
                    fetched.await()
                    val failed = vm.uiState.first { !it.isLyricsLoading }
                    assertTrue(failed.lyrics.isEmpty())
                    available.set(true)
                    vm.loadLyrics(song.id, song.title)
                }
                vm.uiState.first { !it.isLyricsLoading && it.lyrics.isNotEmpty() }
                val successfulCalls = calls.get()
                withContext(dispatcher) { vm.loadLyrics(song.id, song.title) }
                assertEquals(successfulCalls, calls.get())
                assertEquals(
                    "Recovered lyrics",
                    vm.uiState.value.lyrics
                        .single()
                        .text,
                )
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
                imageLoader.shutdown()
                client.close()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun selectedPlainAndTimedSourcesSurviveVideoTogglesAndTrackReload() =
        withLyricsPlayer { vm, controller, _, requests ->
            val song = requireNotNull(controller.state.value.currentSong)
            vm.loadLyrics(song.id, song.title)
            vm.uiState.first { it.rawLyrics != null && !it.isLyricsLoading }
            controller.state.value = controller.state.value.copy(isVideoModeActive = true)
            vm.uiState.first { it.mvLyricsRetimed }
            assertEquals("LRCLIB", vm.uiState.value.lyricsProvider)
            val plain = LyricsProviderResult(LyricsProvider.LYPRIO, "Lyprio/AUDIOMACK", "Chosen plain lyrics", LyricsTimingKind.UNSYNCED)
            vm.selectLyricsProviderResult(song.id, plain)
            vm.uiState.first { it.lyricsUserSelected && it.rawLyrics == plain.lyrics }
            delay(150)
            assertEquals(plain.providerName, vm.uiState.value.lyricsProvider)
            assertEquals(LyricsTimingKind.UNSYNCED, lyricsTimingKind(vm.uiState.value.lyrics))
            assertFalse(vm.uiState.value.mvLyricsRetimed)
            val videoLookups = requests.count { it.startsWith("video:") }

            for (video in listOf(false, true)) {
                controller.state.value = controller.state.value.copy(isVideoModeActive = video)
                vm.uiState.first { it.isVideoModeActive == video }
            }
            val other = song.copy(id = "other")
            controller.state.value = controller.state.value.copy(currentSong = other)
            vm.uiState.first { it.currentSong?.id == other.id }
            controller.state.value = controller.state.value.copy(currentSong = song)
            vm.uiState.first { it.currentSong?.id == song.id }
            vm.loadLyrics(song.id, song.title)
            vm.uiState.first { it.lyricsUserSelected && !it.isLyricsLoading }
            delay(150)
            assertEquals(plain.providerName, vm.uiState.value.lyricsProvider)
            assertEquals(plain.lyrics, vm.uiState.value.rawLyrics)
            assertEquals(LyricsTimingKind.UNSYNCED, lyricsTimingKind(vm.uiState.value.lyrics))
            assertEquals(videoLookups, requests.count { it.startsWith("video:") })

            val timed =
                plain.copy(
                    providerName = "Lyprio/KKBOX",
                    lyrics = "[00:01.00]Chosen timed lyrics",
                    timingKind = LyricsTimingKind.LINE_SYNCED,
                )
            vm.selectLyricsProviderResult(song.id, timed)
            vm.uiState.first { it.rawLyrics == timed.lyrics && it.mvLyricsRetimed }
            assertEquals(
                6_000L,
                vm.uiState.value.lyrics
                    .single()
                    .time,
            )
            assertEquals(timed.providerName, vm.uiState.value.lyricsProvider)
            controller.state.value = controller.state.value.copy(isVideoModeActive = false)
            vm.uiState.first { !it.mvLyricsRetimed }
            assertEquals(
                1_000L,
                vm.uiState.value.lyrics
                    .single()
                    .time,
            )
            assertEquals(timed.lyrics, vm.uiState.value.rawLyrics)
            controller.state.value = controller.state.value.copy(isVideoModeActive = true)
            vm.uiState.first { it.mvLyricsRetimed }
            assertEquals(
                6_000L,
                vm.uiState.value.lyrics
                    .single()
                    .time,
            )
            assertEquals(timed.providerName, vm.uiState.value.lyricsProvider)
            val wordLyrics = MlxBuilder().meta("Chosen").line(1_000, 2_000, words = listOf(MlxWord("Words", 1_000, 2_000))).build()
            vm.selectLyricsProviderResult(song.id, timed.copy(lyrics = wordLyrics, timingKind = LyricsTimingKind.WORD_SYNCED))
            vm.uiState.first { it.rawLyrics == wordLyrics && it.mvLyricsRetimed }
            assertEquals(
                6.0,
                vm.uiState.value.lyrics
                    .single()
                    .words
                    ?.single()
                    ?.startTime,
            )
            assertEquals(timed.providerName, vm.uiState.value.lyricsProvider)
            assertEquals(wordLyrics, vm.uiState.value.rawLyrics)
        }

    @Test
    fun richerVideoFallbackKeepsPreferredAudioProviderAndRawSource() =
        withLyricsPlayer { vm, controller, _, _ ->
            val song = requireNotNull(controller.state.value.currentSong)
            vm.loadLyrics(song.id, song.title)
            vm.uiState.first { it.lyricsProvider == "LRCLIB" }
            val raw = vm.uiState.value.rawLyrics
            controller.state.value = controller.state.value.copy(isVideoModeActive = true)
            vm.uiState.first { it.mvLyricsRetimed }
            assertEquals("LRCLIB", vm.uiState.value.lyricsProvider)
            assertEquals(LyricsTimingKind.LINE_SYNCED, lyricsTimingKind(vm.uiState.value.lyrics))
            assertEquals(raw, vm.uiState.value.rawLyrics)
            assertFalse(vm.uiState.value.lyricsUserSelected)
            controller.state.value = controller.state.value.copy(isVideoModeActive = false)
            vm.uiState.first { !it.mvLyricsRetimed && it.lyricsProvider == "LRCLIB" }
            assertEquals(
                1_000L,
                vm.uiState.value.lyrics
                    .single()
                    .time,
            )
        }

    @Test
    fun lowerQualityAndLowerPriorityVideoSourcesKeepTheRetimedAudioSource() {
        for (videoLyrics in listOf("Automatic video lyrics", "[00:20]Automatic video lyrics")) {
            withLyricsPlayer(videoResponse = { LyricsWithProvider(videoLyrics, "SimpMusic") }) { vm, controller, _, _ ->
                val song = requireNotNull(controller.state.value.currentSong)
                vm.loadLyrics(song.id, song.title)
                vm.uiState.first { it.lyricsProvider == "LRCLIB" }
                val raw = vm.uiState.value.rawLyrics
                controller.state.value = controller.state.value.copy(isVideoModeActive = true)
                vm.uiState.first { it.mvLyricsRetimed }
                assertEquals("LRCLIB", vm.uiState.value.lyricsProvider)
                assertEquals(raw, vm.uiState.value.rawLyrics)
                assertEquals(
                    "Automatic audio lyrics",
                    vm.uiState.value.lyrics
                        .single()
                        .text,
                )
                assertEquals(
                    6_000L,
                    vm.uiState.value.lyrics
                        .single()
                        .time,
                )
                assertFalse(vm.uiState.value.lyricsUserSelected)
                controller.state.value = controller.state.value.copy(isVideoModeActive = false)
                vm.uiState.first { !it.mvLyricsRetimed }
                assertEquals("LRCLIB", vm.uiState.value.lyricsProvider)
                assertEquals(raw, vm.uiState.value.rawLyrics)
                assertEquals(
                    1_000L,
                    vm.uiState.value.lyrics
                        .single()
                        .time,
                )
            }
        }
    }

    @Test
    fun localEditsAndIgnoreRetimingKeepTheirSourceInVideoMode() =
        withLyricsPlayer { vm, controller, database, requests ->
            val song = requireNotNull(controller.state.value.currentSong)
            controller.state.value = controller.state.value.copy(isVideoModeActive = true)
            vm.uiState.first { it.isVideoModeActive }
            vm.saveCustomLyrics(song.id, "My plain edit")
            vm.uiState.first { it.rawLyrics == "My plain edit" }
            delay(150)
            assertEquals(USER_LYRICS_PROVIDER, vm.uiState.value.lyricsProvider)
            assertEquals(LyricsTimingKind.UNSYNCED, lyricsTimingKind(vm.uiState.value.lyrics))
            database.dao().saveUserLyrics(song.id, null, null)
            val ignored = MlxBuilder().meta("Chosen", ignoreRetiming = true).line(1_000, "Keep this timing").build()
            vm.selectLyricsProviderResult(
                song.id,
                LyricsProviderResult(LyricsProvider.LYPRIO, "Chosen", ignored, LyricsTimingKind.LINE_SYNCED),
            )
            vm.uiState.first { it.rawLyrics == ignored }
            delay(150)
            assertEquals(
                1_000L,
                vm.uiState.value.lyrics
                    .single()
                    .time,
            )
            assertEquals("Chosen", vm.uiState.value.lyricsProvider)
            assertTrue(requests.isEmpty())
        }

    @Test
    fun videoCompletionCannotReplaceLyricsAfterSwitchingBackToAudio() {
        val requested = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withLyricsPlayer(
            videoResponse = {
                requested.complete(Unit)
                withContext(NonCancellable) { release.await() }
                LyricsWithProvider("[00:20]Stale video lyrics", "SimpMusic")
            },
        ) { vm, controller, _, _ ->
            try {
                val song = requireNotNull(controller.state.value.currentSong)
                vm.loadLyrics(song.id, song.title)
                vm.uiState.first { it.lyricsProvider == "LRCLIB" }
                controller.state.value = controller.state.value.copy(isVideoModeActive = true)
                requested.await()
                controller.state.value = controller.state.value.copy(isVideoModeActive = false)
                vm.uiState.first { !it.isVideoModeActive }
                release.complete(Unit)
                delay(150)
                assertEquals("LRCLIB", vm.uiState.value.lyricsProvider)
                assertFalse(vm.uiState.value.mvLyricsRetimed)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun explicitSelectionCancelsAnOlderVideoLookup() {
        val requested = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withLyricsPlayer(
            videoResponse = {
                requested.complete(Unit)
                withContext(NonCancellable) { release.await() }
                LyricsWithProvider("[00:20]Stale video lyrics", "SimpMusic")
            },
        ) { vm, controller, _, _ ->
            try {
                val song = requireNotNull(controller.state.value.currentSong)
                vm.loadLyrics(song.id, song.title)
                vm.uiState.first { it.lyricsProvider == "LRCLIB" }
                controller.state.value = controller.state.value.copy(isVideoModeActive = true)
                requested.await()
                vm.selectLyricsProviderResult(
                    song.id,
                    LyricsProviderResult(LyricsProvider.LYPRIO, "Chosen", "My source", LyricsTimingKind.UNSYNCED),
                )
                release.complete(Unit)
                vm.uiState.first { it.lyricsUserSelected && it.rawLyrics == "My source" }
                delay(150)
                assertEquals("Chosen", vm.uiState.value.lyricsProvider)
                assertEquals(LyricsTimingKind.UNSYNCED, lyricsTimingKind(vm.uiState.value.lyrics))
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun selectedNativeVideoLyricsKeepTheirTimelineAcrossViewerChangesAndReload() =
        withLyricsPlayer { vm, controller, database, requests ->
            val audio = requireNotNull(controller.state.value.currentSong)
            val video = audio.copy(id = "video", musicVideoType = "MUSIC_VIDEO_TYPE_OMV")
            controller.state.value = controller.state.value.copy(currentSong = video, isVideoModeActive = true)
            vm.uiState.first { it.currentSong?.id == video.id && it.counterpartTrackId == audio.id && it.mvPositionMap != null }
            assertEquals(25_000L, requireNotNull(vm.uiState.value.mvPositionMap).mapPosition(20_000L))
            val wordLyrics =
                MlxBuilder()
                    .meta(
                        "YouTube",
                    ).line(20_000, 21_000, words = listOf(MlxWord("Video words", 20_000, 21_000)))
                    .build()
            for (result in listOf(
                LyricsProviderResult(
                    LyricsProvider.YOUTUBESUBTITLE,
                    "YouTube subtitles",
                    "[00:20]Video line",
                    LyricsTimingKind.LINE_SYNCED,
                ),
                LyricsProviderResult(LyricsProvider.YOUTUBESUBTITLE, "YouTube subtitles", wordLyrics, LyricsTimingKind.WORD_SYNCED),
            )) {
                vm.selectLyricsProviderResult(video.id, result)
                vm.uiState.first { it.lyricsUserSelected && it.rawLyrics == result.lyrics }
                delay(150)
                assertEquals(
                    20_000L,
                    vm.uiState.value.lyrics
                        .single()
                        .time,
                )
                assertEquals(result.providerName, vm.uiState.value.lyricsProvider)
                assertFalse(vm.uiState.value.mvLyricsRetimed)
            }
            for (viewer in listOf(false, true, false)) {
                controller.state.value = controller.state.value.copy(isVideoModeActive = viewer)
                vm.uiState.first { it.isVideoModeActive == viewer }
                delay(150)
                assertEquals(
                    20_000L,
                    vm.uiState.value.lyrics
                        .single()
                        .time,
                )
                assertEquals(
                    20.0,
                    vm.uiState.value.lyrics
                        .single()
                        .words
                        ?.single()
                        ?.startTime,
                )
                assertFalse(vm.uiState.value.mvLyricsRetimed)
            }
            controller.state.value = controller.state.value.copy(currentSong = audio)
            vm.uiState.first { it.currentSong?.id == audio.id }
            controller.state.value = controller.state.value.copy(currentSong = video)
            vm.uiState.first { it.currentSong?.id == video.id && it.counterpartTrackId == audio.id }
            vm.loadLyrics(video.id, video.title)
            vm.uiState.first { it.lyricsUserSelected && !it.isLyricsLoading }
            delay(150)
            assertEquals(
                20_000L,
                vm.uiState.value.lyrics
                    .single()
                    .time,
            )
            assertEquals(
                20.0,
                vm.uiState.value.lyrics
                    .single()
                    .words
                    ?.single()
                    ?.startTime,
            )
            assertEquals(wordLyrics, vm.uiState.value.rawLyrics)
            assertEquals(wordLyrics, database.dao().cachedLyrics(video.id)?.lyrics)
            assertEquals("YouTube subtitles", vm.uiState.value.lyricsProvider)
            assertTrue(requests.isEmpty())
        }

    @Test
    fun nativeVideoOffsetUsesItsOwnTimelineEvenWhenViewerIsClosed() =
        withLyricsPlayer { vm, controller, database, _ ->
            val audio = requireNotNull(controller.state.value.currentSong)
            database.dao().setLyricsOffset(audio.id, 450)
            database.dao().setLyricsOffset("video", 150)
            val video = audio.copy(id = "video", musicVideoType = "MUSIC_VIDEO_TYPE_OMV")
            controller.state.value = controller.state.value.copy(currentSong = video, isVideoModeActive = false)
            vm.uiState.first { it.currentSong?.id == video.id && it.counterpartTrackId == audio.id }
            vm.loadLyrics(video.id, video.title)
            vm.uiState.first { it.rawLyrics != null && !it.isLyricsLoading }
            assertEquals(150, vm.uiState.value.lyricsOffsetMs)
            vm.setLyricsOffset(video.id, 300)
            database.dao().lyrics(video.id).first { it?.offsetMs == 300 }
            assertEquals(450, database.dao().lyricsOffset(audio.id))
            controller.state.value = controller.state.value.copy(currentSong = audio)
            vm.uiState.first { it.currentSong?.id == audio.id }
            controller.state.value = controller.state.value.copy(currentSong = video)
            vm.uiState.first { it.currentSong?.id == video.id && it.counterpartTrackId == audio.id }
            vm.loadLyrics(video.id, video.title)
            vm.uiState.first { it.rawLyrics != null && !it.isLyricsLoading }
            assertEquals(300, vm.uiState.value.lyricsOffsetMs)
        }

    @Test
    fun changingProviderOrderReloadsTheSameSong() {
        val words =
            MlxBuilder()
                .meta("Words")
                .line(1_000, 2_000, words = listOf(MlxWord("Better words", 1_000, 2_000)))
                .build()
        withLyricsPlayer(
            audioResponse = { provider ->
                if (provider == LyricsProvider.LRCLIB) {
                    LyricsWithProvider("[00:01]안녕", "LRCLIB")
                } else {
                    LyricsWithProvider(words, "PAXSENIX")
                }
            },
        ) { vm, controller, database, _ ->
            Settings.shared.lyricsRomanizationEnabled = true
            Settings.shared.lyricsRomanizeLanguages = "Korean:true"
            val song = requireNotNull(controller.state.value.currentSong)
            vm.loadLyrics(song.id, song.title)
            vm.uiState.first { it.rawLyrics != null && !it.isLyricsLoading }
            assertEquals(
                "안녕",
                vm.uiState.value.lyrics
                    .single()
                    .text,
            )
            assertEquals(
                "annyeong",
                vm.uiState.value.lyrics
                    .single()
                    .romanizedText,
            )
            assertFalse(vm.uiState.value.isLyricsLoading)
            vm.setLyricsOffset(song.id, 450)
            database.dao().lyrics(song.id).first { it?.offsetMs == 450 }
            Settings.shared.lyricsProviderOrder = "PAXSENIX,LRCLIB"
            vm.loadLyrics(song.id, song.title)
            vm.uiState.first { it.rawLyrics == words }
            database.dao().lyrics(song.id).first { it?.lyrics == words }
            assertEquals(LyricsTimingKind.WORD_SYNCED, lyricsTimingKind(vm.uiState.value.lyrics))
            assertEquals(450, vm.uiState.value.lyricsOffsetMs)
        }
    }

    @Test
    fun manualSelectionCancelsPendingAutomaticSearch() {
        val requested = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withLyricsPlayer(
            audioResponse = { provider ->
                if (provider == LyricsProvider.LRCLIB) {
                    null
                } else {
                    requested.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    LyricsWithProvider(
                        MlxBuilder()
                            .meta("Late")
                            .line(1_000, 2_000, words = listOf(MlxWord("Late automatic lyrics", 1_000, 2_000)))
                            .build(),
                        "PAXSENIX",
                    )
                }
            },
        ) { vm, controller, database, _ ->
            try {
                val song = requireNotNull(controller.state.value.currentSong)
                vm.loadLyrics(song.id, song.title)
                requested.await()
                vm.selectLyricsProviderResult(
                    song.id,
                    LyricsProviderResult(LyricsProvider.LYPRIO, "Chosen", "My chosen lyrics", LyricsTimingKind.UNSYNCED),
                )
                release.complete(Unit)
                vm.uiState.first { it.lyricsUserSelected && it.rawLyrics == "My chosen lyrics" }
                assertEquals("My chosen lyrics", database.dao().cachedLyrics(song.id)?.lyrics)
                assertEquals("Chosen", vm.uiState.value.lyricsProvider)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun changingTracksCancelsPendingAutomaticSearch() {
        val requested = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        withLyricsPlayer(
            audioResponse = { provider ->
                if (provider == LyricsProvider.LRCLIB) {
                    null
                } else {
                    requested.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    LyricsWithProvider("[00:02]Stale lyrics", "PAXSENIX")
                }
            },
        ) { vm, controller, _, _ ->
            try {
                val song = requireNotNull(controller.state.value.currentSong)
                vm.loadLyrics(song.id, song.title)
                requested.await()
                controller.state.value = controller.state.value.copy(currentSong = song.copy(id = "other"))
                vm.uiState.first { it.currentSong?.id == "other" }
                release.complete(Unit)
                vm.selectLyricsProviderResult(
                    "other",
                    LyricsProviderResult(LyricsProvider.LYPRIO, "Chosen", "Other song lyrics", LyricsTimingKind.UNSYNCED),
                )
                vm.uiState.first { it.rawLyrics == "Other song lyrics" }
                assertEquals("Chosen", vm.uiState.value.lyricsProvider)
            } finally {
                release.complete(Unit)
            }
        }
    }

    @Test
    fun customProviderSearchLoadsAllGroupsWithoutTrackMetadataPoisoning() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            Settings.shared.lyricsEnabledProviders = "NONE"
            Settings.shared.enableAnimatedCovers = false
            Settings.shared.lyricsRomanizationEnabled = false
            Settings.shared.lyricsAiTranslationEnabled = false
            val dispatcher = StandardTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val client = HttpClient(MockEngine { error("Unexpected network request") })
            val imageLoader = ImageLoader.Builder(PlatformContext.INSTANCE).build()
            val path = Files.createTempFile("lyrics-provider-search", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).setQueryCoroutineContext(dispatcher).build()
            val slowRelease = CompletableDeferred<Unit>()
            val seenCalls = ConcurrentLinkedQueue<String>()
            val fetcher =
                object : LyricsFetcher {
                    override suspend fun getLyrics(
                        title: String,
                        artist: String,
                        duration: Long?,
                        album: String?,
                        videoId: String?,
                        provider: LyricsProvider,
                        isVideoContext: Boolean,
                    ): LyricsWithProvider? {
                        seenCalls.add("$provider|$title|$artist|$videoId|$duration|$album")
                        return when (provider) {
                            LyricsProvider.BETTERLYRICS -> {
                                slowRelease.await()
                                LyricsWithProvider(MlxBuilder().meta("BetterLyrics").line(1_000, "Better").build(), "BetterLyrics")
                            }

                            LyricsProvider.LRCLIB -> {
                                LyricsWithProvider(MlxBuilder().meta("LrcLib").line(1_000, "LrcLib").build(), "LrcLib")
                            }

                            else -> {
                                null
                            }
                        }
                    }
                }
            try {
                val song = SongItem("song", "Wrong Upload Title", null, emptyList(), AlbumTiny("album", "Wrong Album"), duration = 3600)
                val controller = TestController(song)
                val vm =
                    withContext(dispatcher) {
                        PlayerViewModel(
                            PlaybackControllerPlayerSession(controller, scope),
                            imageLoader,
                            PlatformContext.INSTANCE,
                            TestPlatform,
                            LyricsRepository(
                                fetcher,
                                LyricsTranslationClient(client),
                                database.dao(),
                                providerResultsDispatcher = null,
                            ),
                            AnimatedArtworkService(client),
                            MusicVideoResolver(counterpartLookup = { null }, songLookup = { song }),
                            scope,
                        )
                    }
                vm.uiState.first { it.currentSong?.id == song.id }
                withContext(dispatcher) {
                    vm.loadLyricsProviderResults(
                        songId = song.id,
                        title = "Real Song",
                        artist = "",
                        duration = 3_600_000L,
                        album = "Wrong Album",
                        providerOrder = "BETTERLYRICS,LRCLIB",
                        isCustomQuery = true,
                    )
                    val search = requireNotNull(vm.uiState.value.lyricsProviderSearch)
                    assertTrue(search.groups.value.all { it.loading && !it.hasResults })
                    val streaming = search.groups.first { groups -> groups.any { it.results?.isNotEmpty() == true } }
                    assertEquals(listOf(LyricsProvider.LRCLIB), streaming.filter { it.hasResults }.map { it.provider })
                    assertEquals(listOf(LyricsProvider.BETTERLYRICS, LyricsProvider.LRCLIB), streaming.drop(1).take(2).map { it.provider })
                    assertTrue(streaming.first { it.provider == LyricsProvider.BETTERLYRICS }.loading)
                    assertEquals("LrcLib", streaming[2].results?.single()?.providerName)

                    slowRelease.complete(Unit)
                    val completed = search.groups.first { groups -> groups.none { it.loading } }
                    assertEquals(listOf("BetterLyrics", "LrcLib"), completed.drop(1).take(2).map { it.results?.single()?.providerName })
                    val selected = requireNotNull(completed[2].results).single()
                    vm.selectLyricsProviderResult(song.id, selected)
                    vm.uiState.first { it.rawLyrics == selected.lyrics }
                    assertTrue(vm.uiState.value.lyricsUserSelected)
                    val saved = requireNotNull(database.dao().cachedLyrics(song.id))
                    assertTrue(saved.isUserSelected)
                    assertEquals(selected.lyrics, saved.lyrics)
                    assertTrue(seenCalls.all { it.endsWith("|Real Song||null|null|null") })
                }
            } finally {
                slowRelease.complete(Unit)
                scope.coroutineContext.job.cancelAndJoin()
                imageLoader.shutdown()
                client.close()
                database.close()
                Files.deleteIfExists(path)
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun lyricsSeeksKeepTheirTimestampOffsetAndSourceOnAudio() =
        withLyricsPlayer { vm, controller, database, _ ->
            val raw = "[00:29.006]First\n[00:51.363]Second\n[01:14.474]Third\n[01:34.947]Fourth"
            database.dao().saveLyrics(LyricsEntity("song", raw, "LRCLIB", offsetMs = 500))
            vm.loadLyrics("song", "Song")
            vm.uiState.first { it.rawLyrics == raw && !it.isLyricsLoading }
            vm.setLyricsOffset("song", 500)
            val state = vm.uiState.value
            assertFalse(state.isVideoModeActive)
            assertFalse(state.mvLyricsRetimed)
            PlaybackEventLog.clear()
            for (index in listOf(0, 1, 2, 0, 3)) {
                val entry = state.lyrics[index]
                vm.seekToPosition(
                    LyricsUtils.lyricsSeekPositionForOffset(entry.time, state.lyricsOffsetMs),
                    PlaybackSeekSource(
                        "lyrics_tap",
                        mapOf(
                            "lineIndex" to index.toString(),
                            "lineStartMs" to entry.time.toString(),
                            "offsetMs" to state.lyricsOffsetMs.toString(),
                            "lyricsRetimed" to state.mvLyricsRetimed.toString(),
                        ),
                    ),
                )
            }
            assertEquals(listOf(28_506L, 50_863L, 73_974L, 28_506L, 94_447L), controller.seekPositions)
            val exported = PlaybackEventLog.exportText()
            assertTrue(exported.contains("seekSource=lyrics_tap"))
            assertTrue(exported.contains("lineStartMs=51363"))
            assertTrue(exported.contains("offsetMs=500"))
            assertTrue(exported.contains("lyricsRetimed=false"))
        }

    @Test
    fun seekBarUsesTheDisplayedDurationAndRejectsStaleGestures() =
        withLyricsPlayer { vm, controller, _, _ ->
            controller.state.value = controller.state.value.copy(durationMs = 120_000L, positionMs = 10_000L)
            vm.uiState.first { it.duration == 120_000L }
            assertTrue(vm.seekTo(0.75f, 120_000L, "song"))
            assertEquals(listOf(90_000L), controller.seekPositions)

            controller.state.value = controller.state.value.copy(durationMs = 180_000L)
            vm.uiState.first { it.duration == 180_000L }
            assertFalse(vm.seekTo(0.75f, 120_000L, "song"))
            assertFalse(vm.seekTo(0.25f, 180_000L, "previous-song"))
            assertFalse(vm.seekTo(Float.NaN, 180_000L, "song"))
            assertEquals(listOf(90_000L), controller.seekPositions)

            assertTrue(vm.seekTo(0.25f, 180_000L, "song"))
            assertEquals(listOf(90_000L, 45_000L), controller.seekPositions)
            controller.state.value = controller.state.value.copy(durationMs = 0L)
            vm.uiState.first { it.duration == 0L }
            assertFalse(vm.seekTo(0.75f, 0L, "song"))
            assertEquals(listOf(90_000L, 45_000L), controller.seekPositions)
        }

    private fun withLyricsPlayer(
        onRepository: (LyricsRepository) -> Unit = {},
        translationClient: LyricsTranslationClient? = null,
        audioResponse: (suspend (LyricsProvider) -> LyricsWithProvider?)? = null,
        prepareJapaneseDictionary: suspend () -> Boolean = { error("Unexpected dictionary preparation") },
        videoResponse: suspend () -> LyricsWithProvider = {
            val lyrics =
                MlxBuilder()
                    .meta("SimpMusic")
                    .line(20_000, 21_000, words = listOf(MlxWord("Automatic video lyrics", 20_000, 21_000)))
                    .build()
            LyricsWithProvider(lyrics, "SimpMusic")
        },
        block: suspend (PlayerViewModel, TestController, MusicDatabase, ConcurrentLinkedQueue<String>) -> Unit,
    ) = runTest {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        Settings.shared.enableAnimatedCovers = false
        Settings.shared.lyricsRomanizationEnabled = false
        Settings.shared.lyricsAiTranslationEnabled = false
        if (audioResponse != null) {
            Settings.shared.lyricsProviderOrder = "LRCLIB,PAXSENIX"
            Settings.shared.lyricsEnabledProviders = "LRCLIB,PAXSENIX"
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val client = HttpClient(MockEngine { error("Unexpected network request") })
        val imageLoader = ImageLoader.Builder(PlatformContext.INSTANCE).build()
        val path = Files.createTempFile("lyrics-player-selection", ".db")
        Files.delete(path)
        val database = getDesktopDatabaseBuilder(path.toFile()).setQueryCoroutineContext(dispatcher).build()
        val requests = ConcurrentLinkedQueue<String>()
        val fetcher =
            object : LyricsFetcher {
                override suspend fun getLyrics(
                    title: String,
                    artist: String,
                    duration: Long?,
                    album: String?,
                    videoId: String?,
                    provider: LyricsProvider,
                    isVideoContext: Boolean,
                ): LyricsWithProvider? {
                    requests.add("$videoId:$provider")
                    if (videoId == "song" && audioResponse != null) return audioResponse(provider)
                    return if (videoId == "video" && provider == LyricsProvider.SIMPMUSIC) videoResponse() else null
                }
            }
        try {
            val song = SongItem("song", "Song", null, emptyList(), AlbumTiny("album", "Album"), musicVideoType = "MUSIC_VIDEO_TYPE_ATV")
            if (audioResponse == null) database.dao().saveLyrics(LyricsEntity(song.id, "[00:01]Automatic audio lyrics", "LRCLIB"))
            val controller = TestController(song)
            val vm =
                withContext(dispatcher) {
                    PlayerViewModel(
                        PlaybackControllerPlayerSession(controller, scope),
                        imageLoader,
                        PlatformContext.INSTANCE,
                        TestPlatform,
                        LyricsRepository(
                            fetcher,
                            translationClient ?: LyricsTranslationClient(client),
                            database.dao(),
                            prepareJapaneseDictionary = prepareJapaneseDictionary,
                        ).also(onRepository),
                        AnimatedArtworkService(client),
                        MusicVideoResolver(
                            counterpartLookup = {
                                MusicVideoCounterpart(
                                    it.id,
                                    if (it.isVideoSong) song.id else "video",
                                    if (it.isVideoSong) "MUSIC_VIDEO_TYPE_ATV" else "MUSIC_VIDEO_TYPE_OMV",
                                    CounterpartPositionMap(
                                        listOf(
                                            if (it.isVideoSong) {
                                                CounterpartSegment(
                                                    5_000,
                                                    0,
                                                    120_000,
                                                )
                                            } else {
                                                CounterpartSegment(0, 5_000, 120_000)
                                            },
                                        ),
                                    ),
                                )
                            },
                            songLookup = { song },
                        ),
                        scope,
                    )
                }
            vm.uiState.first { it.currentSong?.id == song.id }
            withContext(dispatcher) { block(vm, controller, database, requests) }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            imageLoader.shutdown()
            client.close()
            database.close()
            Files.deleteIfExists(path)
            Settings.replaceSharedForTest(null)
        }
    }

    private class TestController(
        song: SongItem,
    ) : PlaybackController {
        override val state = MutableStateFlow(PlaybackUiState(currentSong = song, queue = listOf(song)))

        override fun play(
            song: SongItem,
            queue: List<SongItem>,
            songsOnly: Boolean,
        ) {
            state.value = PlaybackUiState(currentSong = song, queue = queue, currentQueueIndex = queue.indexOf(song), isPlaying = true)
        }

        override fun togglePlayPause() = Unit

        val seekPositions = mutableListOf<Long>()

        override fun seekTo(positionMs: Long) {
            seekPositions.add(positionMs)
            state.value = state.value.copy(positionMs = positionMs)
        }

        override fun skipToNext() = Unit

        override fun skipToPrevious() = Unit

        override fun setPlaybackSpeed(speed: Float) = Unit

        override fun startRadio(song: SongItem) = Unit

        override fun playNext(songs: List<SongItem>) = Unit

        override fun addToQueue(songs: List<SongItem>) = Unit
    }

    private object TestPlatform : PlayerPlatformServices {
        override suspend fun extractArtworkColors(
            songId: String,
            thumbnailUrl: String,
        ): PlayerArtworkColors? = null

        override fun shareText(text: String) = Unit

        override suspend fun spotifyCanvas(
            title: String,
            artist: String,
            album: String?,
            durationMs: Long?,
            primaryArtist: String?,
        ): String? = null

        override fun musicVideoCanvasHints(song: SongItem): ContentHints = ContentHints()
    }
}
