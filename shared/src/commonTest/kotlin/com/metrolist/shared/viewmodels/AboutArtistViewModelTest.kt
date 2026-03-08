package com.metrolist.shared.viewmodels

import com.metrolist.shared.api.lastfm.LASTFM_PLACEHOLDER_HASH
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class AboutArtistViewModelTest {
    @Test
    fun reopeningRetriesOfflineLoadsAndClearsABiographyInAnotherLanguage() =
        runTest {
            var now = 1L
            var online = false
            var language = "en"
            lastFmArtistClient(beforeRequest = { check(online) }).use { client ->
                val viewModel =
                    AboutArtistViewModel(
                        "id",
                        "Artist",
                        backgroundScope,
                        client,
                        LastFmArtistCache({ "{}" }, {}, { now }, { language }, dispatcher = StandardTestDispatcher(testScheduler)),
                    )
                viewModel.load(listOf("Song"))
                runCurrent()
                assertNull(
                    viewModel.uiState.value.lastFmData
                        ?.summary,
                )
                assertFalse(viewModel.uiState.value.isLoading)
                online = true
                now += 24 * 60 * 60 * 1_000L
                viewModel.load(listOf("Song"))
                runCurrent()
                assertEquals(
                    "Biography",
                    viewModel.uiState.value.lastFmData
                        ?.summary,
                )
                language = "de"
                viewModel.load(listOf("Song"))
                assertNull(
                    viewModel.uiState.value.lastFmData
                        ?.summary,
                )
                runCurrent()
                assertNull(
                    viewModel.uiState.value.lastFmData
                        ?.summary,
                )
                assertFalse(viewModel.uiState.value.hasError)
            }
        }

    @Test
    fun inFlightLoadsDeduplicateAndCancelOnLanguageChangeOrDisposal() =
        runTest {
            var language = "en"
            var requests = 0
            var cancellations = 0
            HttpClient(MockEngine) {
                engine {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler {
                        requests++
                        try {
                            awaitCancellation()
                        } finally {
                            cancellations++
                        }
                    }
                }
            }.use { client ->
                val viewModel =
                    AboutArtistViewModel(
                        "id",
                        "Artist",
                        backgroundScope,
                        client,
                        LastFmArtistCache({ "{}" }, {}, contentLanguage = { language }, dispatcher = StandardTestDispatcher(testScheduler)),
                    )
                viewModel.load(listOf("Song"))
                runCurrent()
                viewModel.load(listOf("Song"))
                runCurrent()
                assertEquals(1, requests)
                language = "de"
                viewModel.load(listOf("Song"))
                runCurrent()
                assertEquals(1, cancellations)
                assertEquals(2, requests)
                assertFalse(viewModel.uiState.value.hasError)
                backgroundScope.cancel()
                runCurrent()
                assertEquals(2, cancellations)
            }
        }

    @Test
    fun metadataAppearsBeforeTheGalleryFinishes() =
        runTest {
            val releaseGallery = CompletableDeferred<Unit>()
            lastFmArtistClient({
                releaseGallery.await()
                ARTIST_GALLERY
            }).use { client ->
                val viewModel =
                    AboutArtistViewModel(
                        "id",
                        "Artist",
                        backgroundScope,
                        client,
                        LastFmArtistCache({ "{}" }, {}, contentLanguage = { "en" }, dispatcher = StandardTestDispatcher(testScheduler)),
                    )
                viewModel.load(listOf("Song"))
                runCurrent()
                assertFalse(viewModel.uiState.value.isLoading)
                assertEquals(
                    "Biography",
                    viewModel.uiState.value.lastFmData
                        ?.summary,
                )
                assertEquals(
                    emptyList(),
                    viewModel.uiState.value.lastFmData
                        ?.photos,
                )
                releaseGallery.complete(Unit)
                runCurrent()
                assertEquals(
                    listOf(ArtistPhoto(ARTIST_PHOTO)),
                    viewModel.uiState.value.lastFmData
                        ?.photos,
                )
            }
        }

    @Test
    fun artistPhotosMergeGalleryAndFallbackSources() {
        assertEquals(
            listOf("gallery", "fallback"),
            mergeLastFmArtistPhotos(
                listOf("gallery", "", "https://example.com/$LASTFM_PLACEHOLDER_HASH.jpg"),
                listOf("gallery", "fallback"),
            ),
        )
    }
}
