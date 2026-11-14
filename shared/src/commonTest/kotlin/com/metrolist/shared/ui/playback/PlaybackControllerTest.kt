package com.metrolist.shared.ui.playback

import com.metrolist.shared.cast.CastDevice
import com.metrolist.shared.model.RepeatMode
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.IndexedQueueItem
import com.metrolist.shared.playback.QueueRemoval
import com.metrolist.shared.playback.isRecommendedQueueSource
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.components.lyrics.LyricsPositionClock
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackControllerTest {
    @Test
    fun lyricsReadNativeTimeBetweenIosPeriodicObserverUpdates() {
        for (speed in listOf(0.5f, 1f, 2f)) {
            runTest {
                val controller = RecordingPlaybackController()
                controller.positionReader = { 45_000L + (testScheduler.currentTime * speed).toLong() }
                controller.state.value = PlaybackUiState(positionMs = 45_000L, playbackSpeed = speed)
                val session = PlaybackControllerPlayerSession(controller, backgroundScope)
                val clock = LyricsPositionClock(speed, timeSource = testScheduler.timeSource)
                advanceTimeBy(80L)
                runCurrent()
                clock.sample(session.currentPosition.value, session::currentPositionMs)
                assertEquals(controller.positionReader!!(), clock.positionMs())
                var previous = clock.positionMs()
                repeat(100) {
                    advanceTimeBy(8L)
                    val mediaMs = (testScheduler.currentTime * speed).toLong()
                    controller.state.value = controller.state.value.copy(positionMs = 45_000L + mediaMs / 100L * 100L)
                    runCurrent()
                    clock.sample(session.currentPosition.value, session::currentPositionMs)
                    val position = clock.positionMs()
                    assertTrue(position >= previous)
                    assertEquals(controller.positionReader!!(), position)
                    previous = position
                }
            }
        }
    }

    private val song =
        SongItem(
            id = "song",
            title = "Song",
            thumbnail = null,
            artists = emptyList(),
            album = null,
        )

    @Test
    fun dislikeSkipsOnlyTheCurrentOccurrenceAndUndoDoesNotSkip() =
        runTest {
            withLocalVotes {
                val controller = RecordingPlaybackController()
                val next = song.copy(id = "next")
                val queue = listOf(song, next, song)
                controller.state.value = PlaybackUiState(currentSong = song, queue = queue, currentQueueIndex = 0)
                controller.onSkipNext = {
                    controller.state.value = controller.state.value.copy(currentSong = next, currentQueueIndex = 1)
                }
                val session = PlaybackControllerPlayerSession(controller, backgroundScope)
                SongLikeState.addLike(song)

                assertTrue(session.toggleDislike(song))
                assertTrue(SongLikeState.isDisliked(song.id))
                assertFalse(SongLikeState.isLiked(song.id))
                assertEquals(1, controller.nextRequests)
                assertEquals(next, controller.state.value.currentSong)
                assertEquals(1, controller.state.value.currentQueueIndex)
                assertEquals(queue, controller.state.value.queue)

                assertFalse(session.toggleDislike(song))
                assertFalse(SongLikeState.isDisliked(song.id))
                assertFalse(SongLikeState.isLiked(song.id))
                assertEquals(1, controller.nextRequests)
                assertTrue(session.toggleDislike(song))
                assertEquals(1, controller.nextRequests)
            }
        }

    @Test
    fun dislikeChecksTheBackendSongBeforeTheSessionCollectorUpdates() =
        runTest {
            withLocalVotes {
                val controller = RecordingPlaybackController()
                controller.state.value = PlaybackUiState(currentSong = song, queue = listOf(song), currentQueueIndex = 0)
                val session = PlaybackControllerPlayerSession(controller, backgroundScope)
                controller.state.value = controller.state.value.copy(currentSong = song.copy(id = "next"))

                assertEquals(song, session.currentSong.value)
                assertTrue(session.toggleDislike(song))
                assertEquals(0, controller.nextRequests)
                controller.state.value = PlaybackUiState()
                assertTrue(session.toggleDislike(song.copy(id = "unqueued")))
                assertEquals(0, controller.nextRequests)
            }
        }

    @Test
    fun displayedSongDislikePreservesTheNewSongsLikeAndPlayback() =
        runTest {
            withLocalVotes {
                val controller = RecordingPlaybackController()
                val next = song.copy(id = "next")
                controller.state.value = PlaybackUiState(currentSong = song, queue = listOf(song, next), currentQueueIndex = 0)
                val session = PlaybackControllerPlayerSession(controller, backgroundScope)
                val dislikeDisplayedSong = { session.toggleDislike(song) }
                SongLikeState.addLike(next)
                controller.state.value = controller.state.value.copy(currentSong = next, currentQueueIndex = 1)
                runCurrent()
                assertEquals(next, session.currentSong.value)

                assertTrue(dislikeDisplayedSong())

                assertTrue(SongLikeState.isDisliked(song.id))
                assertTrue(SongLikeState.isLiked(next.id))
                assertFalse(SongLikeState.isDisliked(next.id))
                assertEquals(0, controller.nextRequests)
                assertEquals(next, controller.state.value.currentSong)
            }
        }

    private suspend fun withLocalVotes(block: suspend () -> Unit) {
        val previous = Settings.sharedOverrideForTest()
        SongLikeState.awaitPersistence()
        Settings.replaceSharedForTest(Settings(MapSettings()))
        try {
            SongLikeState.refreshFromSettings()
            block()
        } finally {
            SongLikeState.awaitPersistence()
            Settings.replaceSharedForTest(previous)
            if (previous != null) SongLikeState.refreshFromSettings()
        }
    }

    @Test
    fun playerSessionFollowsRuntimeSpeedAndOnlyExposesTheCurrentSongsResource() =
        runTest {
            val controller = RecordingPlaybackController()
            val resource =
                PlaybackResource(
                    videoId = song.id,
                    url = "https://example.com/audio",
                    headers = emptyMap(),
                    loudnessDb = null,
                    expiresAt = null,
                    itag = 140,
                )
            controller.state.value = PlaybackUiState(currentSong = song, playbackResource = resource, playbackSpeed = 1.5f)
            val session = PlaybackControllerPlayerSession(controller, backgroundScope)
            runCurrent()
            assertSame(resource, session.currentPlaybackResource.value)
            assertEquals(1.5f, session.playbackSpeedFlow.value)

            session.setPlaybackSpeed(1.25f)
            assertEquals(1.25f, controller.requestedSpeed)
            assertEquals(1.5f, session.playbackSpeedFlow.value)
            controller.state.value = controller.state.value.copy(playbackSpeed = 1f)
            runCurrent()
            assertEquals(1f, session.playbackSpeedFlow.value)

            controller.state.value = controller.state.value.copy(currentSong = song.copy(id = "next"))
            runCurrent()
            assertNull(session.currentPlaybackResource.value)
            val nextResource = resource.copy(videoId = "next", itag = 251)
            controller.state.value = controller.state.value.copy(playbackResource = nextResource)
            runCurrent()
            assertSame(nextResource, session.currentPlaybackResource.value)
            controller.state.value = controller.state.value.copy(playbackResource = null)
            runCurrent()
            assertNull(session.currentPlaybackResource.value)
            controller.state.value = controller.state.value.copy(currentSong = null, playbackResource = nextResource)
            runCurrent()
            assertNull(session.currentPlaybackResource.value)
        }

    @Test
    fun positionTicksPreserveQueueEntriesAndExposeActualBuffering() =
        runTest {
            val controller = RecordingPlaybackController()
            controller.state.value = PlaybackUiState(currentSong = song, queue = listOf(song), currentQueueIndex = 0, durationMs = 10_000)
            val session = PlaybackControllerPlayerSession(controller, backgroundScope)
            runCurrent()
            assertEquals(1, controller.state.subscriptionCount.value)
            val entries = session.queueState.value.entries
            controller.state.value = controller.state.value.copy(positionMs = 1_000, bufferedPositionMs = 8_000)
            runCurrent()
            assertSame(entries, session.queueState.value.entries)
            assertEquals(1_000L, session.currentPosition.value)
            assertEquals(8_000L, session.bufferedPosition.value)
            assertEquals(0.8f, session.bufferedProgress.value)
            val next = song.copy(id = "next")
            controller.state.value = controller.state.value.copy(queue = listOf(song, next))
            runCurrent()
            assertEquals(
                listOf(song, next),
                session.queueSongs(
                    session.queueState.value.entries
                        .map { it.key }
                        .toSet(),
                ),
            )
        }

    @Test
    fun queuePlaylistSelectionKeepsOccurrencesInQueueOrderAndExcludesSuggestions() =
        runTest {
            val controller = RecordingPlaybackController()
            val next = song.copy(id = "next")
            controller.state.value =
                PlaybackUiState(
                    currentSong = next,
                    queue = listOf(song, next, song),
                    currentQueueIndex = 1,
                    suggestedTracks = listOf(song.copy(id = "suggested")),
                )
            val session = PlaybackControllerPlayerSession(controller, backgroundScope)
            runCurrent()
            val keys =
                session.queueState.value.entries
                    .filter { it.key >= 0 }
                    .map { it.key }
            assertEquals(listOf(song, next, song), session.queueSongs(keys.reversed().toSet()))
            assertEquals(listOf(song, song), session.queueSongs(setOf(keys.last(), keys.first())))
        }

    @Test
    fun playerSessionPreservesRecommendationsWithoutTreatingThemAsQueuedSongs() =
        runTest {
            val controller = RecordingPlaybackController()
            val next = song.copy(id = "next")
            val recommended = song.copy(id = "recommended")
            controller.state.value =
                PlaybackUiState(
                    currentSong = song,
                    queue = listOf(song, next),
                    currentQueueIndex = 0,
                    suggestedTracks = listOf(song, recommended, next, recommended),
                )
            val session = PlaybackControllerPlayerSession(controller, backgroundScope)
            runCurrent()
            val entries = session.queueState.value.entries
            val recommendationKey = entries.last().key
            assertEquals(listOf(song.id, next.id, recommended.id), entries.map { it.song.id })
            assertEquals(listOf(song, next), session.queueSongs.value)
            assertTrue(recommendationKey < 0)
            assertTrue(
                entries
                    .last()
                    .song.queueSourceTitle
                    .isRecommendedQueueSource(),
            )
            assertEquals(listOf(next, recommended), session.queueSongs(setOf(recommendationKey, 1L)))

            session.skipToQueueEntry(recommendationKey)
            assertEquals(recommended, controller.playedSuggestion)
            session.addQueueEntry(recommendationKey)
            assertEquals(listOf(recommended), controller.queuedSongs)
            session.playNextQueueEntry(recommendationKey)
            assertEquals(listOf(recommended), controller.nextSongs)
            assertNull(session.removeQueueEntry(recommendationKey))
            assertNull(controller.removedIndices)
            assertFalse(session.moveQueueEntry(recommendationKey, 1L))
            assertFalse(session.moveQueueEntry(0L, Long.MAX_VALUE))
            assertTrue(controller.moves.isEmpty())

            controller.state.value = controller.state.value.copy(suggestedTracks = listOf(next, recommended))
            runCurrent()
            assertEquals(
                recommendationKey,
                session.queueState.value.entries
                    .last()
                    .key,
            )
            controller.state.value = controller.state.value.copy(queue = listOf(song, next, recommended))
            runCurrent()
            assertTrue(
                session.queueState.value.entries
                    .all { it.key >= 0 },
            )
            assertNull(session.queueSong(recommendationKey))
            assertNull(session.queueSong(Long.MAX_VALUE))
            controller.playedSuggestion = null
            session.skipToQueueEntry(recommendationKey)
            assertNull(controller.playedSuggestion)
        }

    @Test
    fun batchOrderUsesOccurrenceKeysAndRejectsStaleOrInvalidQueues() =
        runTest {
            val controller = RecordingPlaybackController()
            controller.state.value = PlaybackUiState(queue = listOf(song, song.copy(id = "other"), song), currentQueueIndex = 2)
            val session = PlaybackControllerPlayerSession(controller, backgroundScope)
            runCurrent()
            val keys =
                session.queueState.value.entries
                    .map { it.key }
            assertFalse(session.reorderQueueEntries(listOf(keys[0], keys[0], keys[2])))
            assertFalse(session.reorderQueueEntries(keys + 99L))
            val reordered = listOf(keys[2], keys[0], keys[1])
            assertTrue(session.reorderQueueEntries(reordered))
            runCurrent()
            assertEquals(
                reordered,
                session.queueState.value.entries
                    .map { it.key },
            )
            assertEquals(0, controller.state.value.currentQueueIndex)
            controller.state.value = controller.state.value.copy(queue = emptyList(), currentQueueIndex = -1)
            assertFalse(session.reorderQueueEntries(keys))
        }

    @Test
    fun reorderingEqualSongsStillPublishesDistinctOccurrenceKeys() =
        runTest {
            val controller = RecordingPlaybackController()
            controller.state.value =
                PlaybackUiState(queue = List(3) { song }, currentQueueIndex = 1, suggestedTracks = listOf(song.copy(id = "suggestion")))
            val session = PlaybackControllerPlayerSession(controller, backgroundScope)
            runCurrent()
            val keys =
                session.queueState.value.entries
                    .map { it.key }
            assertTrue(session.reorderQueueEntries(keys.filter { it >= 0 }.reversed()))
            runCurrent()
            assertEquals(
                keys.filter { it >= 0 }.reversed() + keys.filter { it < 0 },
                session.queueState.value.entries
                    .map { it.key },
            )
            assertEquals(1, session.queueState.value.currentIndex)
        }

    @Test
    fun playerSessionForwardsQueueEditsAndUndoUsingOnlyValidQueueIndices() =
        runTest {
            val controller = RecordingPlaybackController()
            val next = song.copy(id = "next")
            controller.state.value = PlaybackUiState(queue = listOf(song, next), currentQueueIndex = 0)
            val removed = listOf(IndexedQueueItem(1, next))
            val removal = QueueRemoval(removed, removed, 0, 0, false)
            controller.removal = removal
            val session = PlaybackControllerPlayerSession(controller, backgroundScope)
            runCurrent()

            assertFalse(session.moveQueueEntry(1L, 1L))
            assertTrue(session.moveQueueEntry(1L, 0L))
            assertEquals(listOf(1 to 0), controller.moves)
            assertEquals(removal, session.removeQueueEntries(setOf(-1L, 1L, Long.MAX_VALUE)))
            assertEquals(setOf(1), controller.removedIndices)
            assertEquals(listOf(removal), session.pendingQueueRemovals())
            session.restoreQueueItems(listOf(removal))
            assertEquals(listOf(removal), controller.restoredRemovals)
            assertTrue(session.pendingQueueRemovals().isEmpty())
        }

    @Test
    fun playerSessionKeepsOccurrenceKeysWithTheirSongsAcrossQueueChanges() =
        runTest {
            val controller = RecordingPlaybackController()
            val next = song.copy(id = "next")
            val added = song.copy(id = "added")
            controller.state.value = PlaybackUiState(queue = listOf(song, next, next), currentQueueIndex = 0)
            val session = PlaybackControllerPlayerSession(controller, backgroundScope)
            runCurrent()
            val originalKeys =
                session.queueState.value.entries
                    .map { it.key }
            assertEquals(3, originalKeys.toSet().size)
            controller.state.value = controller.state.value.copy(positionMs = 1_000L, currentQueueIndex = 1)
            runCurrent()
            assertEquals(
                originalKeys,
                session.queueState.value.entries
                    .map { it.key },
            )

            controller.state.value = controller.state.value.copy(queue = listOf(next, song, next, added))
            assertEquals(song, session.queueSong(originalKeys[0]))
            session.skipToQueueEntry(originalKeys[0])
            assertEquals(song, controller.playedSong)
            runCurrent()
            val newKeys =
                session.queueState.value.entries
                    .map { it.key }
            assertEquals(listOf(originalKeys[1], originalKeys[0], originalKeys[2]), newKeys.take(3))
            assertTrue(newKeys[3] !in originalKeys)
            assertTrue(session.moveQueueEntry(originalKeys[2], originalKeys[1]))
            assertEquals(listOf(2 to 0), controller.moves)

            controller.state.value = controller.state.value.copy(queue = listOf(next, added))
            assertNull(session.queueSong(originalKeys[0]))
            assertFalse(session.moveQueueEntry(originalKeys[0], originalKeys[1]))
        }

    @Test
    fun skipCapabilitiesFollowQueueBoundsRepeatAndSuggestionPolicy() {
        val controller = RecordingPlaybackController()
        val queue = listOf(song, song.copy(id = "next"))
        val state = PlaybackUiState(currentSong = song, queue = queue, currentQueueIndex = 0)
        controller.state.value = state
        assertTrue(controller.canSkipNext)
        assertFalse(controller.canSkipPrevious)
        assertFalse(state.copy(positionMs = 3000L).canSkipPrevious)
        assertTrue(state.copy(positionMs = 3001L).canSkipPrevious)
        assertTrue(state.copy(currentQueueIndex = 1).canSkipPrevious)

        for (mode in RepeatMode.entries) {
            val tail = state.copy(queue = listOf(song), repeatMode = mode)
            controller.state.value = tail
            assertEquals(mode == RepeatMode.ALL, controller.canSkipNext)
            assertEquals(mode == RepeatMode.ALL, controller.canSkipPrevious)
            val suggested = tail.copy(suggestedTracks = listOf(queue.last()))
            assertTrue(suggested.canSkipNext())
            assertEquals(mode == RepeatMode.ALL, suggested.canSkipNext(allowSuggestions = false))
            for (index in listOf(-1, 1)) {
                val invalid = suggested.copy(currentQueueIndex = index)
                assertFalse(invalid.canSkipNext())
                assertFalse(invalid.canSkipPrevious)
            }
            assertFalse(tail.copy(queue = emptyList()).canSkipNext())
            assertFalse(tail.copy(queue = emptyList()).canSkipPrevious)
        }
    }

    @Test
    fun metadataDurationOverridesAnIncorrectNativeTimeline() {
        assertEquals(300_000L, preferredPlaybackDurationMs(metadataSeconds = 300L, nativeDurationMs = 600_000L))
        assertEquals(600_000L, preferredPlaybackDurationMs(metadataSeconds = null, nativeDurationMs = 600_000L))
    }

    @Test
    fun localDurationKeepsCatalogSecondsAndRoundsUpOnlyWithoutMetadata() {
        assertEquals(198_000L, preferredPlaybackDurationMs(metadataSeconds = 198L, nativeDurationMs = 197_400L))
        assertEquals(198_000L, preferredPlaybackDurationMs(metadataSeconds = 198L, nativeDurationMs = 198_400L))
        for (metadataSeconds in listOf(null, 0L, -1L)) {
            assertEquals(198_000L, preferredPlaybackDurationMs(metadataSeconds, nativeDurationMs = 197_400L))
            assertEquals(198_000L, preferredPlaybackDurationMs(metadataSeconds, nativeDurationMs = 197_999L))
            assertEquals(198_000L, preferredPlaybackDurationMs(metadataSeconds, nativeDurationMs = 198_000L))
            assertEquals(0L, preferredPlaybackDurationMs(metadataSeconds, nativeDurationMs = 0L))
            assertEquals(0L, preferredPlaybackDurationMs(metadataSeconds, nativeDurationMs = -1L))
        }
        assertEquals(Long.MAX_VALUE, preferredPlaybackDurationMs(null, Long.MAX_VALUE))
    }

    @Test
    fun localPlayHistoryHonorsThresholdAndPause() {
        assertFalse(isQualifiedLocalPlay(playTimeMs = 29_999L, thresholdSeconds = 30f, historyPaused = false))
        assertTrue(isQualifiedLocalPlay(playTimeMs = 30_000L, thresholdSeconds = 30f, historyPaused = false))
        assertFalse(isQualifiedLocalPlay(playTimeMs = 60_000L, thresholdSeconds = 30f, historyPaused = true))
    }
}

private class RecordingPlaybackController : PlaybackController {
    override val state = MutableStateFlow(PlaybackUiState())
    var positionReader: (() -> Long)? = null

    override fun currentPositionMs(): Long = positionReader?.invoke() ?: state.value.positionMs

    var requestedSpeed: Float? = null
    var nextRequests = 0
    var onSkipNext: () -> Unit = {}
    var playedSong: SongItem? = null
    var nextSongs = emptyList<SongItem>()
    var queuedSongs = emptyList<SongItem>()
    var playedSuggestion: SongItem? = null
    var removedIndices: Set<Int>? = null
    var removal: QueueRemoval? = null
    var restoredRemovals = emptyList<QueueRemoval>()
    val moves = mutableListOf<Pair<Int, Int>>()

    override fun play(
        song: SongItem,
        queue: List<SongItem>,
        songsOnly: Boolean,
    ) {
        playedSong = song
    }

    override fun togglePlayPause() = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun skipToNext() {
        nextRequests++
        onSkipNext()
    }

    override fun skipToPrevious() = Unit

    override fun setPlaybackSpeed(speed: Float) {
        requestedSpeed = speed
    }

    override fun setPlaybackPitch(pitch: Float) = Unit

    override fun startRadio(song: SongItem) = Unit

    override fun playNext(songs: List<SongItem>) {
        nextSongs = songs
    }

    override fun addToQueue(songs: List<SongItem>) {
        queuedSongs = songs
    }

    override fun playSuggestedTrack(song: SongItem) {
        playedSuggestion = song
    }

    override fun removeQueueItems(indices: Set<Int>): QueueRemoval? {
        removedIndices = indices
        return removal
    }

    override fun restoreQueueItems(removals: List<QueueRemoval>) {
        restoredRemovals = removals
    }

    override fun moveQueueItem(
        from: Int,
        to: Int,
    ) {
        moves += from to to
    }

    override fun reorderQueue(order: List<Int>): Boolean {
        state.value =
            state.value.copy(queue = order.map(state.value.queue::get), currentQueueIndex = order.indexOf(state.value.currentQueueIndex))
        return true
    }

    override fun toggleVideoMode() = Unit

    override fun startCastDiscovery() = Unit

    override fun stopCastDiscovery() = Unit

    override fun castTo(device: CastDevice) = Unit

    override fun disconnectCast() = Unit
}
