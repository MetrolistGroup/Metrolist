package com.metrolist.shared.state

import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastPlaybackProgressTest {
    private val episode =
        SongItem(
            id = "episode",
            title = "Episode",
            artists = emptyList(),
            thumbnail = null,
            album = null,
            musicVideoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE",
        )

    @Test
    fun savesPauseTrackChangeAndFinalCheckpointInOrder() =
        runTest {
            val writes = mutableListOf<Pair<String, Long>>()
            val progress =
                PodcastPlaybackProgress(
                    backgroundScope,
                    { song, position, _, _ -> writes += song.id to position },
                    { throw it },
                    TestTimeSource(),
                )
            progress.update(episode, 0, 8_636_000, true)
            progress.update(episode, 2_813_000, 8_636_000, false)
            progress.update(episode, 2_813_000, 8_636_000, false)
            progress.update(episode, 2_813_000, 8_636_000, true)
            progress.update(episode, 2_814_000, 8_636_000, true)
            progress.update(episode.copy(id = "next"), 0, 100_000, true)
            progress.update(episode.copy(id = "next"), 10_000, 100_000, true)
            progress.flush()?.join()
            assertEquals(listOf("episode" to 2_813_000L, "episode" to 2_814_000L, "next" to 10_000L), writes)
        }

    @Test
    fun checkpointsAtThirtySecondsAndDoesNotPersistMusic() =
        runTest {
            val clock = TestTimeSource()
            val writes = mutableListOf<Long>()
            val progress = PodcastPlaybackProgress(backgroundScope, { _, position, _, _ -> writes += position }, { throw it }, clock)
            progress.update(episode, 0, 100_000, true)
            repeat(29) {
                clock += 1.seconds
                progress.update(episode, (it + 1) * 1000L, 100_000, true)
            }
            runCurrent()
            assertEquals(emptyList(), writes)
            clock += 1.seconds
            progress.update(episode, 30_000, 100_000, true)
            runCurrent()
            assertEquals(listOf(30_000L), writes)
            progress.update(episode.copy(id = "music", musicVideoType = null), 80_000, 100_000, false)
            progress.flush()?.join()
            assertEquals(listOf(30_000L), writes)
        }

    @Test
    fun seekingToStartWhilePausedClearsPreviousProgress() =
        runTest {
            val writes = mutableListOf<Long>()
            val progress =
                PodcastPlaybackProgress(backgroundScope, { _, position, _, _ -> writes += position }, { throw it }, TestTimeSource())
            progress.update(episode, 42_000, 100_000, false)
            progress.update(episode, 0, 100_000, false)
            progress.flush()?.join()
            assertEquals(listOf(42_000L, 0L), writes)
        }

    @Test
    fun seeksAndEpisodeEndFlushBeforeThePeriodicDeadline() =
        runTest {
            val writes = mutableListOf<Long>()
            val clock = TestTimeSource()
            val progress = PodcastPlaybackProgress(backgroundScope, { _, position, _, _ -> writes += position }, { throw it }, clock)
            progress.update(episode, 0, 100_000, true)
            clock += 2.seconds
            progress.update(episode, 2_000, 100_000, true)
            progress.update(episode, 0, 100_000, true)
            progress.flush(force = true)
            progress.update(episode, 90_000, 100_000, true)
            progress.flush(force = true)
            progress.update(episode, 100_000, 100_000, false)
            progress.flush()?.join()
            assertEquals(listOf(0L, 90_000L, 100_000L), writes)
        }

    @Test
    fun orderedWritesRefreshMetadataOnlyWhenItChangesAndRetryAfterFailure() =
        runTest {
            val metadataWrites = mutableListOf<Boolean>()
            var fail = true
            val progress =
                PodcastPlaybackProgress(
                    backgroundScope,
                    { _, _, _, metadata ->
                        metadataWrites += metadata
                        if (fail) {
                            fail = false
                            error("Storage unavailable")
                        }
                    },
                    {},
                    TestTimeSource(),
                )
            progress.update(episode, 1_000, 100_000, false)
            progress.update(episode, 2_000, 100_000, false)
            progress.update(episode, 3_000, 100_000, false)
            progress.update(episode, 4_000, 101_000, false)
            progress.update(episode.copy(title = "Updated"), 5_000, 101_000, false)
            progress.update(episode.copy(title = "Updated"), 6_000, 101_000, false)
            progress.flush()?.join()
            assertEquals(listOf(true, true, false, true, true, false), metadataWrites)
        }

    @Test
    fun explicitlyFlushingRetriesARejectedCheckpointWithoutDuplicatingASuccessfulWrite() =
        runTest {
            val writes = mutableListOf<Long>()
            var failures = 0
            val progress =
                PodcastPlaybackProgress(
                    backgroundScope,
                    { _, position, _, _ ->
                        writes += position
                        if (writes.size == 1) error("Storage unavailable")
                    },
                    { failures++ },
                    TestTimeSource(),
                )
            progress.update(episode, 42_000, 100_000, false)
            runCurrent()
            progress.flush()?.join()
            progress.flush()?.join()
            assertEquals(listOf(42_000L, 42_000L), writes)
            assertEquals(1, failures)
        }

    @Test
    fun automaticallyRetryingAnUnchangedCheckpointWaitsForTheNormalInterval() =
        runTest {
            val writes = mutableListOf<Long>()
            val clock = TestTimeSource()
            val progress =
                PodcastPlaybackProgress(
                    backgroundScope,
                    { _, position, _, _ ->
                        writes += position
                        if (writes.size == 1) error("Storage unavailable")
                    },
                    {},
                    clock,
                )
            progress.update(episode, 42_000, 100_000, false)
            runCurrent()
            clock += 29.seconds
            progress.update(episode, 42_000, 100_000, false)
            runCurrent()
            assertEquals(listOf(42_000L), writes)
            clock += 1.seconds
            progress.update(episode, 42_000, 100_000, false)
            runCurrent()
            assertEquals(listOf(42_000L, 42_000L), writes)
        }

    @Test
    fun repeatedFlushesWhileWriteIsSuspendedDoNotDuplicateIt() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val writes = mutableListOf<Long>()
            val progress =
                PodcastPlaybackProgress(
                    backgroundScope,
                    { _, position, _, _ ->
                        writes += position
                        gate.await()
                    },
                    { throw it },
                    TestTimeSource(),
                )
            progress.update(episode, 42_000, 100_000, false)
            runCurrent()
            repeat(5) { progress.flush() }
            runCurrent()
            assertEquals(listOf(42_000L), writes)
            gate.complete(Unit)
            progress.flush()?.join()
            progress.flush()?.join()
            assertEquals(listOf(42_000L), writes)
        }

    @Test
    fun failedOlderWriteDoesNotRetryOrReplaceNewerQueuedCheckpoint() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val writes = mutableListOf<Long>()
            var errors = 0
            val progress =
                PodcastPlaybackProgress(
                    backgroundScope,
                    { _, position, _, _ ->
                        writes += position
                        if (position == 1_000L) {
                            gate.await()
                            error("First write rejected")
                        }
                    },
                    { errors++ },
                    TestTimeSource(),
                )
            progress.update(episode, 1_000, 100_000, false)
            runCurrent()
            progress.update(episode, 2_000, 100_000, false)
            runCurrent()
            assertEquals(listOf(1_000L), writes)
            gate.complete(Unit)
            progress.flush()?.join()
            repeat(3) { progress.flush()?.join() }
            assertEquals(listOf(1_000L, 2_000L), writes)
            assertEquals(1, errors)
        }

    @Test
    fun failureFromPreviousEpisodeDoesNotInvalidateNewEpisodesSuccessfulWrite() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val writes = mutableListOf<String>()
            val progress =
                PodcastPlaybackProgress(
                    backgroundScope,
                    { song, _, _, _ ->
                        writes += song.id
                        if (song.id == episode.id) {
                            gate.await()
                            error("Previous episode rejected")
                        }
                    },
                    {},
                    TestTimeSource(),
                )
            progress.update(episode, 42_000, 100_000, false)
            runCurrent()
            progress.update(episode.copy(id = "next"), 42_000, 100_000, false)
            gate.complete(Unit)
            progress.flush()?.join()
            progress.flush()?.join()
            assertEquals(listOf("episode", "next"), writes)
        }

    @Test
    fun failedSeekToZeroCanRetryWithoutLosingResetIntent() =
        runTest {
            val writes = mutableListOf<Long>()
            var failZero = true
            val progress =
                PodcastPlaybackProgress(
                    backgroundScope,
                    { _, position, _, _ ->
                        writes += position
                        if (position == 0L && failZero) {
                            failZero = false
                            error("Reset rejected")
                        }
                    },
                    {},
                    TestTimeSource(),
                )
            progress.update(episode, 42_000, 100_000, false)
            progress.flush()?.join()
            progress.update(episode, 0, 100_000, false)
            runCurrent()
            progress.flush()?.join()
            progress.flush()?.join()
            assertEquals(listOf(42_000L, 0L, 0L), writes)
        }

    @Test
    fun failedZeroResetRetriesAtIntervalButInitialZeroIsNeverSaved() =
        runTest {
            val writes = mutableListOf<Long>()
            val clock = TestTimeSource()
            var failZero = true
            val progress =
                PodcastPlaybackProgress(
                    backgroundScope,
                    { _, position, _, _ ->
                        writes += position
                        if (position == 0L && failZero) {
                            failZero = false
                            error("Reset rejected")
                        }
                    },
                    {},
                    clock,
                )
            progress.update(episode, 0, 100_000, false)
            clock += 30.seconds
            progress.update(episode, 0, 100_000, false)
            runCurrent()
            assertEquals(emptyList(), writes)
            progress.update(episode, 42_000, 100_000, false)
            progress.flush()?.join()
            progress.update(episode, 0, 100_000, false)
            runCurrent()
            clock += 29.seconds
            progress.update(episode, 0, 100_000, false)
            runCurrent()
            assertEquals(listOf(42_000L, 0L), writes)
            clock += 1.seconds
            progress.update(episode, 0, 100_000, false)
            runCurrent()
            assertEquals(listOf(42_000L, 0L, 0L), writes)
        }
}
