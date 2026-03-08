package com.metrolist.desktop.state.playback

import com.metrolist.shared.api.lyrics.LyricsFetcher
import com.metrolist.shared.api.lyrics.LyricsTranslationClient
import com.metrolist.shared.api.lyrics.LyricsTranslationFailure
import com.metrolist.shared.api.lyrics.MlxBuilder
import com.metrolist.shared.api.lyrics.MlxWord
import com.metrolist.shared.api.musicvideo.CounterpartPositionMap
import com.metrolist.shared.api.musicvideo.CounterpartSegment
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsWithProvider
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_OMV
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.repository.LyricsRepository
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.PropertiesSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopLyricsStateTest {
    @Test
    fun translationFailureClearsProgressAndRetimingDoesNotRestartIt() {
        var requests = 0
        HttpClient(
            MockEngine {
                requests++
                respond("rate limited", HttpStatusCode.TooManyRequests)
            },
        ).use { client ->
            withLyricsState(translationClient = LyricsTranslationClient(client)) {
                state.fetchLyrics(song)
                awaitJobs()
                val original = state.currentLyricsEntries
                Settings.shared.lyricsAiTranslationEnabled = true
                Settings.shared.lyricsAiActivationMode = "Automatic"
                Settings.shared.lyricsAiProvider = "OpenRouter"
                Settings.shared.lyricsAiApiKey = "test-key"
                Settings.shared.lyricsAiTargetLanguage = "fr"
                state.setManualLyricsTranslation(true)
                awaitJobs()
                assertEquals(LyricsTranslationFailure.RATE_LIMIT, state.lyricsTranslationFailures.first())
                assertFalse(state.translationProgress.isTranslating)
                assertFalse(state.manualAiTranslationEnabled)
                assertFalse(state.isLyricsLoading)
                assertEquals(original, state.currentLyricsEntries)
                assertEquals(1, requests)

                videoActive = true
                release.complete(Unit)
                state.retimeCurrentLyricsIfPossible()
                awaitJobs()
                assertTrue(state.mvLyricsRetimed)
                assertEquals(1, requests)

                state.setManualLyricsTranslation(true)
                awaitJobs()
                assertEquals(LyricsTranslationFailure.RATE_LIMIT, state.lyricsTranslationFailures.first())
                assertFalse(state.translationProgress.isTranslating)
                assertFalse(state.manualAiTranslationEnabled)
                assertEquals(2, requests)
            }
        }
    }

    @Test
    fun refetchKeepsLyricsAndTheirTimelineUntilAReplacementArrives() {
        for (fail in listOf(false, true)) {
            val requested = CompletableDeferred<Unit>()
            val response = CompletableDeferred<LyricsWithProvider?>()
            var refreshing = false
            withLyricsState(fetch = {
                if (refreshing) {
                    requested.complete(Unit)
                    response.await()
                } else {
                    LyricsWithProvider("[00:01]Original lyrics", "LRCLIB")
                }
            }) {
                state.fetchLyrics(song)
                awaitJobs()
                val original = state.currentLyrics
                val entries = state.currentLyricsEntries
                state.currentLyricsOffsetMs = 350
                state.mvLyricsRetimed = true
                refreshing = true
                state.fetchLyrics(song, skipCache = true)
                requested.await()
                assertTrue(state.isLyricsLoading)
                assertEquals(original, state.currentLyrics)
                assertEquals(entries, state.currentLyricsEntries)
                if (fail) response.completeExceptionally(IllegalStateException("Provider unavailable")) else response.complete(null)
                awaitJobs()
                assertFalse(state.isLyricsLoading)
                assertEquals(original, state.currentLyrics)
                assertEquals(entries, state.currentLyricsEntries)
                assertEquals(350, state.currentLyricsOffsetMs)
                assertTrue(state.mvLyricsRetimed)

                state.fetchLyrics(song, skipCache = true)
                awaitJobs()
                assertEquals(original, state.currentLyrics)
            }
        }
    }

    @Test
    fun translatingTheDisplayedSourceDoesNotFetchOrResetItsTimeline() {
        var calls = 0
        withLyricsState(fetch = {
            calls++
            LyricsWithProvider("[00:01]Automatic", "LRCLIB")
        }) {
            state.fetchLyrics(song)
            awaitJobs()
            state.currentLyrics = "[00:02]Selected"
            state.currentLyricsProvider = "Chosen"
            state.currentLyricsEntries = listOf(LyricsEntry(7000, "Selected"))
            state.mvLyricsRetimed = true
            val before = calls
            state.setManualLyricsTranslation(true)
            awaitJobs()
            assertEquals(before, calls)
            assertEquals("Chosen", state.currentLyricsProvider)
            assertEquals("[00:02]Selected", state.currentLyrics)
            assertEquals(7000L, state.currentLyricsEntries.single().time)
            assertTrue(state.mvLyricsRetimed)
            assertTrue(state.manualAiTranslationEnabled)
            assertFalse(state.isLyricsLoading)
        }
    }

    @Test
    fun cacheOnlyLyricsDoNotStartVideoRetiming() =
        withLyricsState {
            state.fetchLyrics(song, cacheOnly = true)
            awaitJobs()
            state.currentLyrics = MlxBuilder().line(1_000, "Cached lyrics").build()
            videoActive = true
            release.complete(Unit)
            state.retimeCurrentLyricsIfPossible()
            awaitJobs()
            assertFalse(requested.isCompleted)
        }

    @Test
    fun cacheOnlyMissDoesNotFetchAndOpeningLyricsStillFetches() {
        val calls = mutableListOf<LyricsProvider>()
        withLyricsState(fetch = { provider ->
            calls += provider
            LyricsWithProvider("[00:01]Fetched lyrics", provider.name)
        }) {
            state.fetchLyrics(song, cacheOnly = true)
            awaitJobs()
            assertTrue(calls.isEmpty())
            assertTrue(state.currentLyricsEntries.isEmpty())
            assertFalse(state.isLyricsLoading)

            state.fetchLyrics(song)
            awaitJobs()
            assertEquals(1, calls.size)
            assertEquals("Fetched lyrics", state.currentLyricsEntries.single().text)
        }
    }

    @Test
    fun lateDictionaryPreparationCannotReplaceANewerRequest() {
        val preparing = CompletableDeferred<Unit>()
        val releaseDictionary = CompletableDeferred<Unit>()
        var response = "[00:01]あい"
        withLyricsState(
            fetch = { LyricsWithProvider(response, "LRCLIB") },
            prepareJapaneseDictionary = {
                preparing.complete(Unit)
                withContext(NonCancellable) { releaseDictionary.await() }
                false
            },
        ) {
            try {
                Settings.shared.lyricsRomanizationEnabled = true
                Settings.shared.lyricsRomanizeLanguages = "Japanese:true"
                state.fetchLyrics(song)
                preparing.await()
                assertEquals("あい", state.currentLyricsEntries.single().text)
                response = "[00:02]New lyrics"
                state.fetchLyrics(song, skipCache = true)
                releaseDictionary.complete(Unit)
                awaitJobs()
                assertEquals("New lyrics", state.currentLyricsEntries.single().text)
                assertEquals(response, state.currentLyrics)
                assertFalse(state.isLyricsLoading)
            } finally {
                releaseDictionary.complete(Unit)
            }
        }
    }

    @Test
    fun storedOrderSelectsBetterLyricsAndReorderingReloadsTheSameSong() {
        val calls = mutableListOf<LyricsProvider>()
        val preferred = "[00:01]Original lyrics"
        val fallback = MlxBuilder().line(1_000, 2_000, words = listOf(MlxWord("Other lyrics", 1_000, 2_000))).build()
        withLyricsState(
            fetch = { provider ->
                calls += provider
                when (provider) {
                    LyricsProvider.BETTERLYRICS -> {
                        LyricsWithProvider(preferred, "BetterLyrics")
                    }

                    LyricsProvider.LRCLIB -> {
                        LyricsWithProvider(fallback, "LrcLib")
                    }

                    else -> {
                        error("Unexpected fallback: $provider")
                    }
                }
            },
        ) {
            Settings.shared.lyricsProviderOrder = "BETTERLYRICS,LRCLIB"
            Settings.shared.lyricsEnabledProviders = "BETTERLYRICS,LRCLIB"
            state.fetchLyrics(song)
            awaitJobs()
            assertEquals("BetterLyrics", state.currentLyricsProvider)
            assertEquals(preferred, state.currentLyrics)
            assertEquals(listOf(LyricsProvider.BETTERLYRICS), calls)
            state.fetchLyrics(song)
            awaitJobs()
            assertEquals(listOf(LyricsProvider.BETTERLYRICS), calls)
            Settings.shared.lyricsProviderOrder = "LRCLIB,BETTERLYRICS"
            state.fetchLyrics(song)
            awaitJobs()
            assertEquals("LrcLib", state.currentLyricsProvider)
            assertEquals(listOf(LyricsProvider.BETTERLYRICS, LyricsProvider.LRCLIB), calls)
        }
    }

    @Test
    fun closingVideoCancelsStandaloneRetimingBeforeItPublishes() =
        withLyricsState {
            state.fetchLyrics(song)
            awaitJobs()
            videoActive = true
            state.retimeCurrentLyricsIfPossible()
            val pending = requested.await()
            videoActive = false
            state.restoreOriginalLyricsIfRetimed()
            assertTrue(pending.isCancelled)
            release.complete(Unit)
            awaitJobs()
            assertAudioLyrics()
        }

    @Test
    fun closingVideoRejectsLateFetchTimeRetiming() =
        withLyricsState {
            videoActive = true
            state.fetchLyrics(song)
            requested.await()
            videoActive = false
            state.restoreOriginalLyricsIfRetimed()
            release.complete(Unit)
            awaitJobs()
            assertAudioLyrics()
            assertFalse(state.isLyricsLoading)
        }

    @Test
    fun reopeningVideoCannotReviveRetimingFromThePreviousMode() =
        withLyricsState {
            videoActive = true
            state.fetchLyrics(song)
            val oldFetch = requested.await()
            videoActive = false
            state.restoreOriginalLyricsIfRetimed()
            videoActive = true
            state.retimeCurrentLyricsIfPossible()
            scope.coroutineContext.job.children
                .filter { it != oldFetch }
                .toList()
                .joinAll()
            assertEquals(25_000L, state.currentLyricsEntries.single().time)
            release.complete(Unit)
            awaitJobs()
            assertEquals(25_000L, state.currentLyricsEntries.single().time)
            assertTrue(state.mvLyricsRetimed)
        }

    @Test
    fun completedRetimingRestoresAudioAndCanBeAppliedAgain() =
        withLyricsState {
            release.complete(Unit)
            videoActive = true
            state.fetchLyrics(song)
            awaitJobs()
            assertEquals(13_000L, state.currentLyricsEntries.single().time)
            assertTrue(state.mvLyricsRetimed)
            videoActive = false
            state.restoreOriginalLyricsIfRetimed()
            awaitJobs()
            assertAudioLyrics()
            videoActive = true
            state.retimeCurrentLyricsIfPossible()
            awaitJobs()
            assertEquals(25_000L, state.currentLyricsEntries.single().time)
            assertTrue(state.mvLyricsRetimed)
        }

    @Test
    fun reopeningVideoCancelsPendingAudioRestoration() =
        withLyricsState {
            release.complete(Unit)
            videoActive = true
            state.fetchLyrics(song)
            awaitJobs()
            videoActive = false
            state.restoreOriginalLyricsIfRetimed()
            videoActive = true
            state.retimeCurrentLyricsIfPossible()
            awaitJobs()
            assertEquals(13_000L, state.currentLyricsEntries.single().time)
            assertTrue(state.mvLyricsRetimed)
        }

    @Test
    fun hidingANativeVideoKeepsItsVideoTimeline() =
        withLyricsState {
            song = song.copy(musicVideoType = MUSIC_VIDEO_TYPE_OMV)
            videoActive = true
            state.fetchLyrics(song)
            requested.await()
            videoActive = false
            state.restoreOriginalLyricsIfRetimed()
            release.complete(Unit)
            awaitJobs()
            assertEquals(13_000L, state.currentLyricsEntries.single().time)
            assertTrue(state.mvLyricsRetimed)
        }

    @Test
    fun unknownDurationPassesNullAndCustomSearchOmitsTrackIdentity() =
        runTest {
            Settings.replaceSharedForTest(
                Settings(PropertiesSettings(Properties())).apply {
                    lyricsRomanizationEnabled = false
                    lyricsAiTranslationEnabled = false
                    lyricsProviderOrder = "BETTERLYRICS,LRCLIB"
                    lyricsEnabledProviders = "BETTERLYRICS,LRCLIB"
                },
            )
            val client = HttpClient()
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val job = SupervisorJob()
            val observedDurations = mutableListOf<Long?>()
            val observedProviderLookups = mutableListOf<String>()
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
                        observedDurations += duration
                        observedProviderLookups += "$provider|$title|$artist|$videoId|$duration|$album"
                        return when (provider) {
                            LyricsProvider.LRCLIB -> {
                                LyricsWithProvider(MlxBuilder().meta("LrcLib").line(1_000, "Found").build(), "LrcLib")
                            }

                            else -> {
                                null
                            }
                        }
                    }
                }
            val repository =
                LyricsRepository(
                    fetcher,
                    LyricsTranslationClient(client),
                    youtubeLyricsApi = null,
                    providerResultsDispatcher = null,
                )
            val fixture = Fixture(CoroutineScope(job + dispatcher), repository)
            try {
                fixture.release.complete(Unit)
                fixture.song = SongItem("song", "Original Title", null, emptyList(), null, duration = null)
                fixture.state.fetchLyrics(fixture.song)
                testScheduler.advanceUntilIdle()
                fixture.awaitJobs()
                assertTrue(observedDurations.isNotEmpty())
                assertTrue(observedDurations.all { it == null })

                observedProviderLookups.clear()
                fixture.song = SongItem("song", "Original Title", null, emptyList(), null, duration = 240)
                fixture.state.fetchProviderResults(fixture.song, customTitle = "Custom Song", customArtist = "")
                val search = requireNotNull(fixture.state.providerSearch)
                assertTrue(observedProviderLookups.isEmpty())
                search.toggle(LyricsProvider.LRCLIB)
                assertTrue(
                    search.groups.value
                        .first { it.provider == LyricsProvider.LRCLIB }
                        .loading,
                )
                testScheduler.advanceUntilIdle()
                fixture.awaitJobs()
                assertFalse(search.groups.value.any { it.loading })
                assertEquals("Custom Song", fixture.state.queryTitle)
                assertEquals("", fixture.state.queryArtist)
                assertEquals(
                    listOf(LyricsProvider.LRCLIB),
                    search.groups.value
                        .flatMap { it.results.orEmpty() }
                        .map { it.provider },
                )
                assertTrue(observedProviderLookups.all { it.endsWith("|Custom Song||null|null|null") })
            } finally {
                job.cancelAndJoin()
                client.close()
                Dispatchers.resetMain()
                Settings.replaceSharedForTest(null)
            }
        }

    private class Fixture(
        val scope: CoroutineScope,
        repository: LyricsRepository,
    ) {
        var song = SongItem("song", "Song", null, emptyList(), null)
        var videoActive = false
        val requested = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        private var resolutions = 0
        val state =
            DesktopLyricsState(
                scope = scope,
                currentTrack = { song },
                videoActive = { videoActive },
                counterpartVideoId = { "video" },
                resolveCounterpart = {
                    val first = resolutions++ == 0
                    if (first) {
                        requested.complete(currentCoroutineContext().job)
                        withContext(NonCancellable) { release.await() }
                    }
                    "video" to CounterpartPositionMap(listOf(CounterpartSegment(0, if (first) 12_000 else 24_000, 180_000)))
                },
                repository = { repository },
            )

        suspend fun awaitJobs() {
            scope.coroutineContext.job.children
                .toList()
                .joinAll()
        }

        fun assertAudioLyrics() {
            assertEquals(1_000L, state.currentLyricsEntries.single().time)
            assertFalse(state.mvLyricsRetimed)
        }
    }

    private fun withLyricsState(
        fetch: (suspend (LyricsProvider) -> LyricsWithProvider?)? = null,
        translationClient: LyricsTranslationClient? = null,
        prepareJapaneseDictionary: suspend () -> Boolean = { error("Unexpected dictionary preparation") },
        block: suspend Fixture.() -> Unit,
    ) = runTest {
        Settings.replaceSharedForTest(
            Settings(PropertiesSettings(Properties())).apply {
                lyricsRomanizationEnabled = false
                lyricsAiTranslationEnabled = false
            },
        )
        val client = HttpClient()
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val job = SupervisorJob()
        val lyrics = MlxBuilder().song(songwriters = listOf("Writer")).line(1_000, "Audio lyrics").build()
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
                ): LyricsWithProvider? = if (fetch != null) fetch(provider) else LyricsWithProvider(lyrics, "LRCLIB")
            }
        val fixture =
            Fixture(
                CoroutineScope(job + dispatcher),
                LyricsRepository(
                    fetcher,
                    translationClient ?: LyricsTranslationClient(client),
                    youtubeLyricsApi = null,
                    prepareJapaneseDictionary = prepareJapaneseDictionary,
                ),
            )
        try {
            fixture.block()
        } finally {
            fixture.release.complete(Unit)
            job.cancelAndJoin()
            client.close()
            Dispatchers.resetMain()
            Settings.replaceSharedForTest(null)
        }
    }
}
