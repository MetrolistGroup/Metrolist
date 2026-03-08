package com.metrolist.shared.ui.player

import coil3.ColorImage
import com.metrolist.shared.viewmodels.PlayerViewModel.SongUiModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerArtworkHandoffTest {
    @Test
    fun aRetainedPainterCannotPublishThePreviousTracksImageUnderANewUrl() {
        val cover = "https://lh3.googleusercontent.com/cover=w640-h640-l90-rj"
        assertTrue(miniPlayerArtworkMatches(cover, "https://lh3.googleusercontent.com/cover=w64-h64-l90-rj"))
        assertTrue(miniPlayerArtworkMatches("https://lh3.googleusercontent.com/cover=w2400-h2400-l90-rj", cover))
        assertFalse(miniPlayerArtworkMatches(cover, "https://lh3.googleusercontent.com/other-cover=w64-h64-l90-rj"))
        assertFalse(miniPlayerArtworkMatches(cover, null))
    }

    @Test
    fun videoRenditionsShareTheMiniPlayerCoverWithoutAcceptingAnotherVideoOrRevision() {
        val small = "https://i.ytimg.com/vi/video/mqdefault.jpg#revision"
        val large = "https://i.ytimg.com/vi/video/maxresdefault.jpg#revision"
        assertTrue(miniPlayerArtworkMatches(small, large))
        assertTrue(miniPlayerArtworkMatches(large, small))
        assertFalse(miniPlayerArtworkMatches(small, "https://i.ytimg.com/vi/other/maxresdefault.jpg#revision"))
        assertFalse(miniPlayerArtworkMatches(small, "https://i.ytimg.com/vi/video/maxresdefault.jpg#new-revision"))
    }

    @Test
    fun openingWithALoadedMiniPlayerCoverUsesItWhileTheFullSizeRequestWaits() =
        runTest {
            val song = SongUiModel("current", "Title", emptyList(), "cover", 1000L)
            val handoff = PlayerArtworkHandoff().apply { expect("cover") }
            val mini = PlayerTrackArtwork("cover", image = ColorImage(width = 64, height = 64))
            handoff.publish(mini)
            val songs = MutableStateFlow<SongUiModel?>(song)
            var latest: PlayerTrackPresentation? = null
            songs
                .withPlayerTrackArtwork { awaitCancellation() }
                .onEach { handoff.expect(it.song?.displayThumbnail) }
                .withMiniPlayerArtwork(handoff.artwork)
                .onEach { latest = it }
                .launchIn(backgroundScope)
            runCurrent()

            assertSame(mini, latest?.artwork)
            songs.value = song.copy(id = "next", thumbnail = "next-cover")
            runCurrent()
            assertNull(latest?.artwork?.image)
            assertNull(handoff.artwork.value)
        }

    @Test
    fun miniPlayerCoverArrivingDuringAFullSizeLoadReplacesTheEmptyArtwork() =
        runTest {
            val song = SongUiModel("current", "Title", emptyList(), "cover", 1000L)
            val presentations = MutableStateFlow(PlayerTrackPresentation(song, PlayerTrackArtwork("cover")))
            val handoff = PlayerArtworkHandoff().apply { expect("cover") }
            var latest: PlayerTrackPresentation? = null
            presentations.withMiniPlayerArtwork(handoff.artwork).onEach { latest = it }.launchIn(backgroundScope)
            runCurrent()
            assertNull(latest?.artwork?.image)

            val mini = PlayerTrackArtwork("cover", image = ColorImage(width = 64, height = 64))
            handoff.publish(mini)
            runCurrent()
            assertSame(mini, latest?.artwork)

            val full = PlayerTrackArtwork("cover", image = ColorImage(width = 1200, height = 1200))
            presentations.value = PlayerTrackPresentation(song, full)
            runCurrent()
            assertSame(full, latest?.artwork)
            handoff.publish(mini.copy(image = ColorImage()))
            runCurrent()
            assertSame(full, latest?.artwork)
        }

    @Test
    fun changingSongsClearsThePreviewAndRejectsAnOutgoingDecode() =
        runTest {
            val first = SongUiModel("first", "Title", emptyList(), "first-cover", 1000L)
            val presentations = MutableStateFlow(PlayerTrackPresentation(first, PlayerTrackArtwork("first-cover")))
            val handoff = PlayerArtworkHandoff().apply { expect("first-cover") }
            val mini = PlayerTrackArtwork("first-cover", image = ColorImage())
            handoff.publish(mini)
            var latest: PlayerTrackPresentation? = null
            presentations.withMiniPlayerArtwork(handoff.artwork).onEach { latest = it }.launchIn(backgroundScope)
            runCurrent()
            assertSame(mini, latest?.artwork)

            presentations.value =
                PlayerTrackPresentation(first.copy(id = "second", thumbnail = "second-cover"), PlayerTrackArtwork("second-cover"))
            runCurrent()
            assertNull(latest?.artwork?.image)
            handoff.expect("second-cover")
            handoff.publish(mini)
            runCurrent()
            assertNull(handoff.artwork.value)
            assertNull(latest?.artwork?.image)

            handoff.expect("first-cover")
            assertNull(handoff.artwork.value)
            handoff.expect(null)
            assertNull(handoff.artwork.value)
        }
}
