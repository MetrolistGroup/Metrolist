package com.metrolist.shared.ui.player

import androidx.compose.ui.unit.IntSize
import coil3.ColorImage
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.settings.PlayerThumbnailStyle
import com.metrolist.shared.viewmodels.PlayerViewModel.SongUiModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerTrackPresentationTest {
    private fun song(
        id: String,
        thumbnail: String? = id,
    ) = SongUiModel(id, "Title $id", listOf(ArtistTiny(id, "Artist $id")), thumbnail, 1000L)

    @Test
    fun compactArtworkUsesTheShortWindowEdgeInBothOrientations() {
        assertEquals(IntSize(720, 720), playerArtworkRequestSize(IntSize(720, 1600), PlayerThumbnailStyle.COMPACT, false))
        assertEquals(IntSize(720, 720), playerArtworkRequestSize(IntSize(1600, 720), PlayerThumbnailStyle.COMPACT, false))
        assertEquals(IntSize(1200, 1200), playerArtworkRequestSize(IntSize(1200, 1200), PlayerThumbnailStyle.COMPACT, false))
    }

    @Test
    fun fullWindowArtworkModesRetainWindowDimensions() {
        for (window in listOf(IntSize(720, 1600), IntSize(1600, 720))) {
            assertEquals(window, playerArtworkRequestSize(window, PlayerThumbnailStyle.FULLSCREEN, false))
            assertEquals(window, playerArtworkRequestSize(window, PlayerThumbnailStyle.CANVAS, false))
            assertEquals(window, playerArtworkRequestSize(window, PlayerThumbnailStyle.COMPACT, true))
        }
    }

    @Test
    fun resolutionReloadRetainsMatchingArtworkAndPublishesMetadataImmediately() =
        runTest {
            val songs = MutableStateFlow<SongUiModel?>(song("current"))
            val retained = PlayerTrackArtwork("current", image = ColorImage(width = 720, height = 720))
            val replacement = PlayerTrackArtwork("current", image = ColorImage(width = 1600, height = 1600))
            val seen = mutableListOf<PlayerTrackPresentation>()
            songs
                .withPlayerTrackArtwork(initialArtwork = retained) {
                    delay(100)
                    replacement
                }.onEach(seen::add)
                .launchIn(backgroundScope)

            runCurrent()
            assertSame(retained, seen.last().artwork)
            songs.value = songs.value?.copy(title = "Edited title")
            runCurrent()
            assertEquals("Edited title", seen.last().song?.title)
            assertSame(retained, seen.last().artwork)
            advanceTimeBy(100)
            runCurrent()
            assertSame(replacement, seen.last().artwork)
            assertEquals("Edited title", seen.last().song?.title)
            seen.forEach { assertEquals("current", it.artwork.url) }
        }

    @Test
    fun failedResolutionReloadRetainsTheMatchingArtwork() =
        runTest {
            val retained = PlayerTrackArtwork("current", image = ColorImage())
            val seen = mutableListOf<PlayerTrackPresentation>()
            MutableStateFlow<SongUiModel?>(song("current"))
                .withPlayerTrackArtwork(initialArtwork = retained) {
                    delay(100)
                    error("decode failed")
                }.onEach(seen::add)
                .launchIn(backgroundScope)

            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertSame(retained, seen.last().artwork)
        }

    @Test
    fun trackChangesDuringResolutionReloadClearTheRetainedCover() =
        runTest {
            val songs = MutableStateFlow<SongUiModel?>(song("first"))
            val retained = PlayerTrackArtwork("first", image = ColorImage())
            val seen = mutableListOf<PlayerTrackPresentation>()
            songs
                .withPlayerTrackArtwork(initialArtwork = retained) { url ->
                    delay(100)
                    PlayerTrackArtwork(url, image = ColorImage())
                }.onEach(seen::add)
                .launchIn(backgroundScope)

            runCurrent()
            assertSame(retained, seen.last().artwork)
            songs.value = song("second")
            runCurrent()
            assertEquals("second", seen.last().song?.id)
            assertEquals("second", seen.last().artwork.url)
            assertNull(seen.last().artwork.image)
            advanceTimeBy(100)
            runCurrent()
            assertEquals("second", seen.last().artwork.url)
            assertNotNull(seen.last().artwork.image)
        }

    @Test
    fun returningToTheInitialCoverAfterATrackChangeDoesNotRestoreTheObsoleteFallback() =
        runTest {
            val songs = MutableStateFlow<SongUiModel?>(song("first"))
            val retained = PlayerTrackArtwork("first", image = ColorImage())
            var latest: PlayerTrackPresentation? = null
            songs
                .withPlayerTrackArtwork(initialArtwork = retained) { error("decode failed") }
                .onEach { latest = it }
                .launchIn(backgroundScope)

            runCurrent()
            assertSame(retained, latest?.artwork)
            songs.value = song("second")
            runCurrent()
            songs.value = song("first")
            runCurrent()
            assertEquals("first", latest?.artwork?.url)
            assertNull(latest?.artwork?.image)
        }

    @Test
    fun openingPlayerDuringAnArtworkRequestPublishesTheCurrentSong() =
        runTest {
            val current = song("current")
            val songs = MutableStateFlow<SongUiModel?>(current)
            val seen = mutableListOf<PlayerTrackPresentation>()
            songs
                .withPlayerTrackArtwork { awaitCancellation() }
                .onEach(seen::add)
                .launchIn(backgroundScope)

            runCurrent()

            assertEquals(current, seen.lastOrNull()?.song)
            assertEquals(current.thumbnail, seen.last().artwork.url)
            assertNull(seen.last().artwork.bitmap)
        }

    @Test
    fun publishesCurrentMetadataAndArtworkUrlBeforeDecodeCompletes() =
        runTest {
            val songs = MutableStateFlow<SongUiModel?>(song("first"))
            val seen = mutableListOf<PlayerTrackPresentation>()
            val decoded = ColorImage()
            songs
                .withPlayerTrackArtwork { url ->
                    delay(100)
                    PlayerTrackArtwork(url, image = decoded)
                }.onEach(seen::add)
                .launchIn(backgroundScope)
            runCurrent()
            assertEquals("first", seen.single().song?.id)
            assertNull(seen.single().artwork.image)
            advanceTimeBy(100)
            runCurrent()
            assertSame(decoded, seen.last().artwork.image)
            songs.value = song("second")
            runCurrent()
            assertEquals("second", seen.last().song?.id)
            assertEquals("second", seen.last().artwork.url)
            assertNull(seen.last().artwork.image)
            val second = seen.last()
            advanceTimeBy(99)
            assertSame(second, seen.last())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf("first", "second"), seen.map { it.song?.id }.distinct())
            assertSame(decoded, seen.last().artwork.image)
            assertEquals("Title second", seen.last().song?.title)
            assertEquals(
                "Artist second",
                seen
                    .last()
                    .song
                    ?.artists
                    ?.single()
                    ?.name,
            )
            assertEquals("second", seen.last().artwork.url)
        }

    @Test
    fun rapidSkipsCancelPendingArtworkAndClearingCancelsTheLastRequest() =
        runTest {
            val songs = MutableStateFlow<SongUiModel?>(song("first"))
            val seen = mutableListOf<PlayerTrackPresentation>()
            val cancelled = mutableListOf<String>()
            songs
                .withPlayerTrackArtwork { url ->
                    try {
                        delay(100)
                        PlayerTrackArtwork(url)
                    } catch (error: CancellationException) {
                        cancelled += url
                        throw error
                    }
                }.onEach(seen::add)
                .launchIn(backgroundScope)
            runCurrent()
            songs.value = song("second")
            runCurrent()
            songs.value = song("third")
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(listOf("first", "second", "third"), seen.map { it.song?.id }.distinct())
            songs.value = song("fourth")
            runCurrent()
            songs.value = null
            runCurrent()
            advanceTimeBy(100)
            runCurrent()
            assertEquals(listOf("first", "second", "third", "fourth", null), seen.map { it.song?.id }.distinct())
            assertNull(seen.last().artwork.url)
            assertEquals(listOf("first", "second", "fourth"), cancelled)
        }

    @Test
    fun metadataUpdatesAndDuplicateCoversReuseThePreparedArtwork() =
        runTest {
            val songs = MutableStateFlow<SongUiModel?>(song("first"))
            val seen = mutableListOf<PlayerTrackPresentation>()
            var requests = 0
            songs
                .withPlayerTrackArtwork { url ->
                    requests++
                    PlayerTrackArtwork(url, image = ColorImage())
                }.onEach(seen::add)
                .launchIn(backgroundScope)
            runCurrent()
            val prepared = seen.last().artwork
            songs.value = songs.value?.copy(title = "Edited title", isLiked = true)
            runCurrent()
            assertEquals("Edited title", seen.last().song?.title)
            songs.value = song("second", "first")
            runCurrent()
            assertEquals("second", seen.last().song?.id)
            assertSame(prepared, seen.last().artwork)
            assertEquals(1, requests)
        }

    @Test
    fun metadataChangesDuringDecodeDoNotRestartItOrPublishStaleMetadata() =
        runTest {
            val songs = MutableStateFlow<SongUiModel?>(song("first"))
            val seen = mutableListOf<PlayerTrackPresentation>()
            var requests = 0
            songs
                .withPlayerTrackArtwork { url ->
                    requests++
                    delay(100)
                    PlayerTrackArtwork(url)
                }.onEach(seen::add)
                .launchIn(backgroundScope)
            runCurrent()
            advanceTimeBy(50)
            songs.value = songs.value?.copy(title = "Updated title")
            runCurrent()
            advanceTimeBy(50)
            runCurrent()
            assertEquals("Updated title", seen.last().song?.title)
            assertEquals(1, requests)
        }

    @Test
    fun failedOrMissingArtworkDoesNotKeepThePreviousTrack() =
        runTest {
            val songs = MutableStateFlow<SongUiModel?>(song("first"))
            val seen = mutableListOf<PlayerTrackPresentation>()
            songs
                .withPlayerTrackArtwork { url ->
                    if (url == "failed") error("decode failed")
                    PlayerTrackArtwork(url)
                }.onEach(seen::add)
                .launchIn(backgroundScope)
            runCurrent()
            songs.value = song("failed")
            runCurrent()
            assertEquals("failed", seen.last().song?.id)
            assertEquals("failed", seen.last().artwork.url)
            assertNull(seen.last().artwork.bitmap)
            songs.value = song("missing", null)
            runCurrent()
            assertEquals("missing", seen.last().song?.id)
            assertNull(seen.last().artwork.url)
        }

    @Test
    fun skippingRetainsThePreviousTrackUntilTheReplacementArtworkIsReady() =
        runTest {
            val songs = MutableStateFlow<SongUiModel?>(song("first"))
            val seen = mutableListOf<PlayerTrackPresentation>()
            songs
                .withPlayerTrackArtwork { url ->
                    delay(100)
                    PlayerTrackArtwork(url, image = ColorImage())
                }.retainWhileArtworkLoads()
                .onEach(seen::add)
                .launchIn(backgroundScope)
            advanceTimeBy(100)
            runCurrent()
            val first = seen.last()
            songs.value = song("second").copy(duration = 2000L)
            runCurrent()
            assertSame(first, seen.last())
            songs.value = songs.value?.copy(title = "Updated title")
            runCurrent()
            assertSame(first, seen.last())
            advanceTimeBy(100)
            runCurrent()
            val second = seen.last()
            assertEquals("second", second.song?.id)
            assertEquals("Updated title", second.song?.title)
            assertEquals(
                "Artist second",
                second.song
                    ?.artists
                    ?.single()
                    ?.name,
            )
            assertEquals(2000L, second.song?.duration)
            assertEquals("second", second.artwork.url)
            assertNotNull(second.artwork.image)
            assertEquals(listOf("first", "second"), seen.map { it.song?.id }.distinct())
        }

    @Test
    fun aFailedOrMissingCoverAdvancesMetadataInsteadOfRetainingTheOldTrackForever() =
        runTest {
            val songs = MutableStateFlow<SongUiModel?>(song("first"))
            var latest = PlayerTrackPresentation()
            songs
                .withPlayerTrackArtwork { url ->
                    if (url == "failed") {
                        delay(100)
                        error("decode failed")
                    }
                    PlayerTrackArtwork(url, image = ColorImage())
                }.retainWhileArtworkLoads()
                .onEach { latest = it }
                .launchIn(backgroundScope)
            runCurrent()
            val first = latest
            songs.value = song("failed")
            runCurrent()
            assertSame(first, latest)
            advanceTimeBy(100)
            runCurrent()
            assertEquals("failed", latest.song?.id)
            assertNull(latest.artwork.image)
            songs.value = song("missing", null)
            runCurrent()
            assertEquals("missing", latest.song?.id)
            assertNull(latest.artwork.url)
        }

    @Test
    fun rapidSkipsKeepTheLoadedTrackAndClearingCancelsThePendingReplacement() =
        runTest {
            val songs = MutableStateFlow<SongUiModel?>(song("first"))
            val cancelled = mutableListOf<String>()
            val seen = mutableListOf<PlayerTrackPresentation>()
            songs
                .withPlayerTrackArtwork { url ->
                    try {
                        if (url != "first") delay(100)
                        PlayerTrackArtwork(url, image = ColorImage())
                    } catch (error: CancellationException) {
                        cancelled += url
                        throw error
                    }
                }.retainWhileArtworkLoads()
                .onEach(seen::add)
                .launchIn(backgroundScope)
            runCurrent()
            val first = seen.last()
            songs.value = song("second")
            runCurrent()
            songs.value = song("third")
            runCurrent()
            assertSame(first, seen.last())
            advanceTimeBy(100)
            runCurrent()
            assertEquals("third", seen.last().song?.id)
            assertNotNull(seen.last().artwork.image)
            songs.value = song("fourth")
            runCurrent()
            songs.value = null
            runCurrent()
            assertNull(seen.last().song)
            assertNull(seen.last().artwork.url)
            advanceTimeBy(100)
            runCurrent()
            assertNull(seen.last().song)
            assertEquals(listOf("second", "fourth"), cancelled)
            assertEquals(listOf("first", "third", null), seen.map { it.song?.id }.distinct())
        }

    @Test
    fun firstOpenAndMetadataEditsDoNotWaitForArtwork() =
        runTest {
            val songs = MutableStateFlow<SongUiModel?>(song("first"))
            var latest = PlayerTrackPresentation()
            songs
                .withPlayerTrackArtwork { awaitCancellation() }
                .retainWhileArtworkLoads()
                .onEach { latest = it }
                .launchIn(backgroundScope)
            runCurrent()
            assertEquals("first", latest.song?.id)
            songs.value = songs.value?.copy(title = "Edited title")
            runCurrent()
            assertEquals("Edited title", latest.song?.title)
            songs.value = song("second")
            runCurrent()
            assertEquals("second", latest.song?.id)
        }

    @Test
    fun aMiniPlayerPreviewReleasesThePendingTrackBeforeTheFullSizeDecodeCompletes() =
        runTest {
            val songs = MutableStateFlow<SongUiModel?>(song("first"))
            val handoff = PlayerArtworkHandoff()
            var latest = PlayerTrackPresentation()
            songs
                .withPlayerTrackArtwork { url ->
                    if (url != "first") awaitCancellation()
                    PlayerTrackArtwork(url, image = ColorImage())
                }.onEach { handoff.expect(it.song?.displayThumbnail) }
                .withMiniPlayerArtwork(handoff.artwork)
                .retainWhileArtworkLoads()
                .onEach { latest = it }
                .launchIn(backgroundScope)
            runCurrent()
            val first = latest
            songs.value = song("second")
            runCurrent()
            assertSame(first, latest)
            assertEquals("second", handoff.url)
            val preview = PlayerTrackArtwork("second", image = ColorImage())
            handoff.publish(preview)
            runCurrent()
            assertEquals("second", latest.song?.id)
            assertSame(preview, latest.artwork)
            songs.value = songs.value?.copy(title = "Edited second")
            runCurrent()
            assertEquals("Edited second", latest.song?.title)
            assertSame(preview, latest.artwork)
        }
}
