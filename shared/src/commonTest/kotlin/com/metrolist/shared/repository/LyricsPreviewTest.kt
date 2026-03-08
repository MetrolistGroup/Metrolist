package com.metrolist.shared.repository

import com.metrolist.shared.api.lyrics.LyricsFetcher
import com.metrolist.shared.api.lyrics.LyricsTranslationClient
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsTimingKind
import com.metrolist.shared.model.LyricsWithProvider
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class LyricsPreviewTest {
    @BeforeTest
    fun configureProviders() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        Settings.shared.lyricsProviderOrder = "BETTERLYRICS,LRCLIB"
        Settings.shared.lyricsEnabledProviders = "BETTERLYRICS,LRCLIB"
    }

    @AfterTest
    fun resetSettings() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun prefersSyncedLyricsAndCachesConcurrentRequestsForTheSession() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = mutableListOf<LyricsProvider>()
            val fetcher =
                previewFetcher { provider ->
                    calls += provider
                    when (provider) {
                        LyricsProvider.BETTERLYRICS -> {
                            started.complete(Unit)
                            release.await()
                            LyricsWithProvider("Unsynced provider text", "BetterLyrics")
                        }

                        LyricsProvider.LRCLIB -> {
                            LyricsWithProvider("[00:01.00]First line\n[00:03.00]Second line", "LrcLib")
                        }

                        else -> {
                            null
                        }
                    }
                }
            HttpClient(MockEngine { error("Preview must not invoke translation") }).use { client ->
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client))
                val first = async { repository.getPreviewLyrics() }
                started.await()
                val second = async { repository.getPreviewLyrics() }
                release.complete(Unit)
                val result = first.await()
                assertEquals(LyricsTimingKind.LINE_SYNCED, result?.timingKind)
                assertEquals("LrcLib", result?.providerName)
                assertSame(result, second.await())
                assertSame(result, repository.getPreviewLyrics())
                assertEquals(listOf(LyricsProvider.BETTERLYRICS, LyricsProvider.LRCLIB), calls)
            }
        }

    @Test
    fun leavesUnsyncedPreviewRetryableWhenOtherProvidersFail() =
        runTest {
            val fetcher =
                previewFetcher { provider ->
                    if (provider == LyricsProvider.BETTERLYRICS) {
                        LyricsWithProvider("Plain lyrics", "BetterLyrics")
                    } else {
                        error("Provider unavailable")
                    }
                }
            HttpClient(MockEngine { error("Preview must not invoke translation") }).use { client ->
                val result = LyricsRepository(fetcher, LyricsTranslationClient(client)).getPreviewLyrics()
                assertNull(result)
            }
        }

    @Test
    fun failedFetchCanBeRetried() =
        runTest {
            var available = false
            val fetcher =
                previewFetcher { provider ->
                    if (available && provider == LyricsProvider.LRCLIB) {
                        LyricsWithProvider("[00:01.00]Recovered lyrics", "LrcLib")
                    } else {
                        null
                    }
                }
            HttpClient(MockEngine { error("Preview must not invoke translation") }).use { client ->
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client))
                assertNull(repository.getPreviewLyrics())
                available = true
                assertEquals("LrcLib", repository.getPreviewLyrics()?.providerName)
            }
        }

    @Test
    fun cancelledFetchReleasesTheCacheLockAndCanBeRetried() =
        runTest {
            val started = CompletableDeferred<Unit>()
            var wait = true
            val fetcher =
                previewFetcher {
                    if (wait) {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                    LyricsWithProvider("[00:01.00]Recovered lyrics", "BetterLyrics")
                }
            HttpClient(MockEngine { error("Preview must not invoke translation") }).use { client ->
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client))
                val request = launch { repository.getPreviewLyrics() }
                started.await()
                request.cancelAndJoin()
                wait = false
                assertEquals("BetterLyrics", repository.getPreviewLyrics()?.providerName)
            }
        }

    @Test
    fun rejectsProviderTestPayloadAndTriesTheNextProvider() =
        runTest {
            Settings.shared.lyricsProviderOrder = "LYPRIO,LRCLIB"
            Settings.shared.lyricsEnabledProviders = "LYPRIO,LRCLIB"
            val calls = mutableListOf<LyricsProvider>()
            val fetcher =
                previewFetcher { provider ->
                    calls += provider
                    when (provider) {
                        LyricsProvider.LYPRIO -> {
                            LyricsWithProvider(
                                """{"version":2,"provider":"LYPRIO","subprovider":"UNISON_OFFLINE","syncType":"line","song":{},"lines":[{"startMs":1000,"text":"Test line"},{"startMs":2000,"text":"Test line 2"}]}""",
                                "Lyprio",
                            )
                        }

                        LyricsProvider.LRCLIB -> {
                            LyricsWithProvider("[00:18.00]We're no strangers to love", "LrcLib")
                        }

                        else -> {
                            null
                        }
                    }
                }
            HttpClient(MockEngine { error("No translation") }).use { client ->
                val repository = LyricsRepository(fetcher, LyricsTranslationClient(client))
                assertEquals("LrcLib", repository.getPreviewLyrics()?.providerName)
                assertEquals(listOf(LyricsProvider.LYPRIO, LyricsProvider.LRCLIB), calls)
                calls.clear()
                assertEquals(
                    "LrcLib",
                    repository
                        .getLyrics(
                            LYRICS_PREVIEW_VIDEO_ID,
                            "Never Gonna Give You Up",
                            "Rick Astley",
                            LYRICS_PREVIEW_DURATION_MS,
                            preferredProviders = listOf(LyricsProvider.LYPRIO, LyricsProvider.LRCLIB),
                        )?.provider,
                )
                assertEquals(listOf(LyricsProvider.LYPRIO, LyricsProvider.LRCLIB), calls)
            }
        }

    private fun previewFetcher(fetch: suspend (LyricsProvider) -> LyricsWithProvider?): LyricsFetcher =
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
                assertEquals("dQw4w9WgXcQ", videoId)
                assertEquals("Never Gonna Give You Up", title)
                assertEquals("Rick Astley", artist)
                assertEquals(213_000L, duration)
                return fetch(provider)
            }
        }
}
