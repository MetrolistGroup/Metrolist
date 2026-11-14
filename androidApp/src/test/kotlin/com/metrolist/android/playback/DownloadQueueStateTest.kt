package com.metrolist.android.playback

import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrFormatId
import com.metrolist.innertubex.sabr.SabrProtocolException
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.IncompleteAudioDownloadException
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class DownloadQueueStateTest {
    @Test
    fun unavailableRowsAreExcludedFromDirectAndTrackedPlaylistDownloads() {
        val unavailable = song("unavailable").copy(isUnavailable = true)
        val playable = song("playable")
        assertEquals(listOf(playable), pendingPlaylistDownloads(listOf(unavailable, playable), emptySet()))
        assertEquals(listOf(playable), mergeDownloadQueue(emptyList(), listOf(unavailable, playable), 1L).map { it.song })
    }

    @Test
    fun completionSummaryTracksRetryPauseResumeCancelAndDismiss() {
        val failed = listOf("a", "b").map { DownloadRequest(song(it), DownloadQueueState.FAILED, enqueuedAt = 1L) }
        assertEquals(0 to 2, downloadCompletionSummary(failed))
        val retry = retryDownloadQueue(failed, "a")
        assertNull(downloadCompletionSummary(retry))
        val paused = changeDownloadQueueState(retry, "a", DownloadQueueState.PAUSED)
        assertNull(downloadCompletionSummary(paused))
        val resumed = changeDownloadQueueState(paused, "a", DownloadQueueState.QUEUED)
        assertNull(downloadCompletionSummary(resumed))
        val completed = updateDownloadQueue(resumed, "a") { it.copy(state = DownloadQueueState.COMPLETED) }
        assertEquals(1 to 1, downloadCompletionSummary(completed))
        assertEquals(1 to 0, downloadCompletionSummary(cancelDownloadQueue(completed, song("b"))))
        assertNull(downloadCompletionSummary(cancelDownloadQueue(failed)))
        assertNull(downloadCompletionSummary(emptyList()))
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun pauseResumeAndCancellationWakeWaitersWithoutTimers() =
        runTest {
            val control = AudioDownloadControl()
            control.pause()
            val paused = launch(start = CoroutineStart.UNDISPATCHED) { control.awaitIfPaused() }
            assertFalse(paused.isCompleted)
            control.resume()
            paused.join()
            control.pause()
            val pausedAgain = launch(start = CoroutineStart.UNDISPATCHED) { control.awaitIfPaused() }
            val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { control.awaitCancellation() }
            assertFalse(pausedAgain.isCompleted)
            assertFalse(cancelled.isCompleted)
            control.cancel()
            pausedAgain.join()
            cancelled.join()
            control.pause()
            control.resume()
            assertTrue(control.isCancelled)
            assertFalse(control.isPaused)
            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun onlyQueuedAndRunningDownloadsKeepTheServiceAlive() {
        assertTrue(DownloadQueueState.QUEUED.isRunnable)
        assertTrue(DownloadQueueState.RUNNING.isRunnable)
        assertFalse(DownloadQueueState.PAUSED.isRunnable)
        assertFalse(DownloadQueueState.FAILED.isRunnable)
        assertFalse(DownloadQueueState.COMPLETED.isRunnable)
        assertFalse(DownloadQueueState.CANCELLED.isRunnable)
    }

    @Test
    fun playlistDownloadIntentUsesStableLocalRemoteAndLikedIds() {
        assertEquals("PL123", trackablePlaylistId("VLPL123"))
        assertEquals("LM", trackablePlaylistId("VLLM"))
        assertEquals("LP_LOCAL", trackablePlaylistId("LP_LOCAL"))
        assertEquals("LM", trackablePlaylistId("auto:liked"))
        assertNull(trackablePlaylistId("auto:weekly"))
        assertNull(trackablePlaylistId(""))
    }

    @Test
    fun cancelAllPreservesCompletedSongsForLaterTrackedDownloads() {
        val done = DownloadRequest(song("done"), DownloadQueueState.COMPLETED, enqueuedAt = 1L)
        val active = DownloadRequest(song("active"), DownloadQueueState.RUNNING, enqueuedAt = 1L)
        val cancelled = cancelDownloadQueue(listOf(done, active))
        assertEquals(done, cancelled.first())
        assertEquals(DownloadQueueState.CANCELLED, cancelled.last().state)
        val restored = mergeTrackedPlaylistDownloads(cancelled, listOf(song("done"), song("active")), 2L)
        assertEquals(DownloadQueueState.QUEUED, restored.first().state)
        assertEquals(DownloadQueueState.CANCELLED, restored.last().state)
    }

    @Test
    fun repeatedBatchPreservesPendingRequestsAndAddsOnlyNewSongs() {
        val original =
            listOf(
                DownloadRequest(song("queued"), DownloadQueueState.QUEUED, enqueuedAt = 1L),
                DownloadRequest(song("running"), DownloadQueueState.RUNNING, enqueuedAt = 1L),
            )
        assertEquals(original, mergeDownloadQueue(original, original.map { it.song }, 2L))
        val changed = mergeDownloadQueue(original, original.map { it.song } + song("new"), 2L)
        assertEquals(original, changed.take(2))
        assertEquals("new", changed.last().song.id)
    }

    @Test
    fun trackedPlaylistReconciliationQueuesOnlyNewSongs() {
        val songs = listOf(song("a"), song("b"), song("b"))

        assertEquals(listOf("b"), pendingPlaylistDownloads(songs, setOf("a")).map(SongItem::id))
    }

    @Test
    fun trackedPlaylistSyncKeepsExistingAndNewSongsPausedUntilResume() {
        val paused = DownloadRequest(song("paused"), DownloadQueueState.PAUSED, enqueuedAt = 1L)
        val songs = listOf(song("paused"), song("new"))

        val merged = mergeTrackedPlaylistDownloads(listOf(paused), songs, 2L)

        assertEquals(listOf("paused", "new"), merged.map { it.song.id })
        assertEquals(listOf(DownloadQueueState.PAUSED, DownloadQueueState.PAUSED), merged.map { it.state })
        assertEquals(paused, merged.first())
        assertEquals(DownloadQueueState.QUEUED, mergeDownloadQueue(listOf(paused), songs, 2L).first().state)
    }

    @Test
    fun cancelledRequestsRemainDurableWithoutBlockingNewTracks() {
        val queued = DownloadRequest(song("cancelled"), DownloadQueueState.QUEUED, enqueuedAt = 1L)
        val cancelled = Json.decodeFromString<List<DownloadRequest>>(Json.encodeToString(cancelDownloadQueue(listOf(queued))))

        assertEquals(DownloadQueueState.CANCELLED, cancelled.single().state)
        assertEquals(
            listOf(DownloadQueueState.CANCELLED, DownloadQueueState.QUEUED),
            mergeTrackedPlaylistDownloads(cancelled, listOf(song("cancelled"), song("new")), 2L).map { it.state },
        )
        assertEquals(DownloadQueueState.QUEUED, mergeDownloadQueue(cancelled, listOf(song("cancelled")), 2L).single().state)
    }

    @Test
    fun individualCancelSurvivesTrackedSyncWithoutAffectingOtherRequests() {
        val queued =
            listOf(
                DownloadRequest(song("cancelled"), DownloadQueueState.RUNNING, enqueuedAt = 1L),
                DownloadRequest(song("other"), DownloadQueueState.PAUSED, enqueuedAt = 1L),
            )
        val restored =
            Json.decodeFromString<List<DownloadRequest>>(
                Json.encodeToString(cancelDownloadQueue(queued, song("cancelled"))),
            )

        assertEquals(listOf(DownloadQueueState.CANCELLED, DownloadQueueState.PAUSED), restored.map { it.state })
        assertEquals(
            listOf(DownloadQueueState.CANCELLED, DownloadQueueState.PAUSED, DownloadQueueState.PAUSED),
            mergeTrackedPlaylistDownloads(restored, listOf(song("cancelled"), song("other"), song("new")), 2L).map { it.state },
        )
        assertEquals(DownloadQueueState.QUEUED, mergeDownloadQueue(restored, listOf(song("cancelled")), 3L).first().state)

        val alreadyStored = cancelDownloadQueue(emptyList(), song("stored"))
        assertEquals(DownloadQueueState.CANCELLED, alreadyStored.single().state)
        assertEquals(
            DownloadQueueState.CANCELLED,
            mergeTrackedPlaylistDownloads(alreadyStored, listOf(song("stored")), 4L).single().state,
        )
    }

    @Test
    fun finishingWorkerCannotOverwritePersistedCancellation() {
        val running = DownloadRequest(song("canceled"), DownloadQueueState.RUNNING, enqueuedAt = 1L)
        val cancelled =
            Json.decodeFromString<List<DownloadRequest>>(
                Json.encodeToString(cancelDownloadQueue(listOf(running), song("canceled"))),
            )

        for (terminal in listOf(DownloadQueueState.COMPLETED, DownloadQueueState.FAILED)) {
            assertSame(cancelled, updateDownloadQueue(cancelled, "canceled") { it.copy(state = terminal) })
        }
        assertSame(cancelled, updateDownloadQueue(cancelled, "canceled") { it.copy(downloadedBytes = 100L) })
        assertEquals(
            DownloadQueueState.COMPLETED,
            updateDownloadQueue(listOf(running), "canceled") { it.copy(state = DownloadQueueState.COMPLETED) }.single().state,
        )
    }

    @Test
    fun onlyTerminalBatchesComplete() {
        assertTrue(listOf(DownloadQueueState.COMPLETED).isTerminalBatch())
        assertTrue(listOf(DownloadQueueState.COMPLETED, DownloadQueueState.FAILED).isTerminalBatch())
        assertFalse(emptyList<DownloadQueueState>().isTerminalBatch())
        assertFalse(listOf(DownloadQueueState.COMPLETED, DownloadQueueState.QUEUED).isTerminalBatch())
        assertFalse(listOf(DownloadQueueState.COMPLETED, DownloadQueueState.PAUSED).isTerminalBatch())
        assertFalse(listOf(DownloadQueueState.CANCELLED).isTerminalBatch())
    }

    @Test
    fun batchQueueMergePreservesRunningRequestsAndRefreshesPendingRequests() {
        val running = DownloadRequest(song("running"), DownloadQueueState.RUNNING, enqueuedAt = 1L)
        val paused = DownloadRequest(song("paused"), DownloadQueueState.PAUSED, enqueuedAt = 2L)

        val merged = mergeDownloadQueue(listOf(running, paused), listOf(song("paused"), song("new")), enqueuedAt = 3L)

        assertEquals(listOf("running", "paused", "new"), merged.map { it.song.id })
        assertEquals(running, merged[0])
        assertEquals(DownloadQueueState.QUEUED, merged[1].state)
        assertEquals(3L, merged[1].enqueuedAt)
        assertEquals(DownloadQueueState.QUEUED, merged[2].state)
    }

    @Test
    fun downloadsAlwaysRequestAudioOnlyResources() {
        val hints =
            ContentHints(
                wantVideo = true,
                playbackClientOverrideId = "video-client",
                sabrFirst = true,
            ).forAudioDownload()

        assertFalse(hints.wantVideo)
        assertNull(hints.playbackClientOverrideId)
        assertFalse(hints.sabrFirst)
        assertFalse(hints.allowHls)
        assertTrue(hints.allowSabr)
        assertTrue(hints.allowBoundedRange)
    }

    @Test
    fun failedDirectDownloadRetriesWithSabrBeforeDirectFallbacks() =
        runTest {
            val attempted = mutableListOf<Boolean>()
            downloadWithResourceRefresh(resolve = { refresh -> ContentHints().forAudioDownload(preferSabr = refresh) }) { hints ->
                attempted += hints.sabrFirst
                if (!hints.sabrFirst) throw DownloadTransferException(IOException("Tokenless direct stream cut off"))
                assertTrue(hints.allowSabr)
                assertFalse(hints.allowHls)
            }
            assertEquals(listOf(false, true), attempted)
        }

    @Test
    fun incompleteAudioHasAnActionableFailureCode() {
        assertEquals(DownloadFailureCode.INCOMPLETE, downloadFailureCode(DownloadTransferException(SabrProtocolException("cut off"))))
        assertEquals(
            DownloadFailureCode.INCOMPLETE,
            downloadFailureCode(DownloadTransferException(IncompleteAudioDownloadException("cut off"))),
        )
    }

    @Test
    fun sabrAudioCanBeDownloadedWithoutForcingADirectResource() =
        runTest {
            val direct = PlaybackResource("video", "https://example.test/audio", emptyMap(), null, null, mimeType = "audio/webm")
            val cachedSabr =
                direct.copy(
                    sabrBootstrap =
                        SabrBootstrap(
                            videoId = "video",
                            serverAbrStreamingUrl = "https://example.test/sabr",
                            videoPlaybackUstreamerConfig = byteArrayOf(1),
                            clientName = 1,
                            clientVersion = "test",
                            audioFormat = SabrFormatId(251),
                            discardVideoFormat = SabrFormatId(136),
                            discardVideoHeight = 720,
                            durationMs = 30_000L,
                            contentLengthBytes = 1_000L,
                            mimeType = "audio/webm",
                        ),
                )
            val refreshes = mutableListOf<Boolean>()
            val transferred = mutableListOf<PlaybackResource>()

            downloadWithResourceRefresh(
                resolve = { refresh ->
                    refreshes += refresh
                    if (refresh) direct else cachedSabr
                },
            ) { resource ->
                requireDownloadableAudioResource(resource)
                transferred += resource
            }

            assertEquals(listOf(false), refreshes)
            assertEquals(listOf(cachedSabr), transferred)
            requireDownloadableAudioResource(direct.copy(useRangeChunks = true, requireBoundedRange = true))
        }

    @Test
    fun cancellingStalledSabrDownloadStopsTransferAndKeepsQueueWorkerAlive() =
        runTest {
            val control = AudioDownloadControl()
            val started = CompletableDeferred<Unit>()
            var closed = false
            launch {
                started.await()
                control.cancel()
            }
            try {
                withSabrDownloadControl(control) {
                    try {
                        started.complete(Unit)
                        awaitCancellation()
                    } finally {
                        closed = true
                    }
                }
                fail("Expected download cancellation")
            } catch (_: IOException) {
            }
            assertTrue(closed)
            assertEquals("next", withSabrDownloadControl(AudioDownloadControl()) { "next" })
        }

    @Test
    fun failedDownloadRefreshesTheResourceOnce() =
        runTest {
            val refreshRequests = mutableListOf<Boolean>()
            val downloadedResources = mutableListOf<String>()

            downloadWithResourceRefresh(
                resolve = { refresh ->
                    refreshRequests += refresh
                    if (refresh) "fresh" else "stale"
                },
            ) { resource ->
                if (resource == "stale") throw DownloadTransferException(IllegalStateException("expired resource"))
                downloadedResources += resource
            }

            assertEquals(listOf(false, true), refreshRequests)
            assertEquals(listOf("fresh"), downloadedResources)
        }

    @Test
    fun resourceRefreshStopsAfterTwoFailedAttempts() =
        runTest {
            var attempts = 0

            try {
                downloadWithResourceRefresh(resolve = { "resource" }) {
                    attempts++
                    throw DownloadTransferException(IllegalStateException("download failed"))
                }
                throw AssertionError("Expected download failure")
            } catch (_: DownloadTransferException) {
            }

            assertEquals(2, attempts)
        }

    @Test
    fun userCancelledRangeStopsOnlyItsTransferAndLeavesNextDownloadRunnable() =
        runTest {
            val control = AudioDownloadControl()
            var singleCalls = 0
            val failure = IllegalStateException("cancelled range")
            var caught: Exception? = null

            try {
                downloadWithRangeFallback(
                    control = control,
                    range = {
                        control.cancel()
                        throw failure
                    },
                    single = {
                        singleCalls++
                        "unexpected fallback"
                    },
                )
            } catch (e: Exception) {
                caught = e
            }
            assertSame(failure, caught)
            assertFalse(caught is CancellationException)
            assertEquals(0, singleCalls)
            var cancelledAfterProbe: Exception? = null
            try {
                downloadWithRangeFallback(
                    control = control,
                    range = { null },
                    single = {
                        singleCalls++
                        "unexpected fallback"
                    },
                )
            } catch (e: Exception) {
                cancelledAfterProbe = e
            }
            assertTrue(cancelledAfterProbe is IllegalStateException)
            assertEquals(0, singleCalls)
            assertEquals(
                "next song",
                downloadWithRangeFallback(
                    control = AudioDownloadControl(),
                    range = { null },
                    single = {
                        singleCalls++
                        "next song"
                    },
                ),
            )
            assertEquals(1, singleCalls)
        }

    @Test
    fun coroutineCancellationFromRangeDoesNotFallBackToSingleStream() =
        runTest {
            val cancellation = CancellationException("worker stopped")
            var caught: Exception? = null
            var singleCalled = false
            try {
                downloadWithRangeFallback(
                    control = null,
                    range = { throw cancellation },
                    single = {
                        singleCalled = true
                        "unexpected fallback"
                    },
                )
            } catch (e: CancellationException) {
                caught = e
            }
            assertSame(cancellation, caught)
            assertFalse(singleCalled)
        }

    @Test
    fun cancelledTransferDoesNotRetryOrRefresh() =
        runTest {
            val refreshes = mutableListOf<Boolean>()
            val cancellation = CancellationException("cancelled")
            var caught: Exception? = null

            try {
                downloadWithResourceRefresh(resolve = { refresh ->
                    refreshes += refresh
                    "direct"
                }) {
                    throw cancellation
                }
            } catch (e: CancellationException) {
                caught = e
            }

            assertSame(cancellation, caught)
            assertEquals(listOf(false), refreshes)
        }

    @Test
    fun finalizationFailureDoesNotRefreshTheResource() =
        runTest {
            val failure = IllegalStateException("database failed")
            val refreshRequests = mutableListOf<Boolean>()
            var thrown: Exception? = null

            try {
                downloadWithResourceRefresh(
                    resolve = { refresh ->
                        refreshRequests += refresh
                        "resource"
                    },
                ) { throw failure }
            } catch (e: Exception) {
                thrown = e
            }

            assertSame(failure, thrown)
            assertEquals(listOf(false), refreshRequests)
        }

    @Test
    fun pauseAndResumeKeepOtherDownloadsAndTerminalStatesIntact() {
        val queue =
            listOf(
                DownloadRequest(song("active"), DownloadQueueState.RUNNING, 1L, downloadedBytes = 50L, totalBytes = 100L),
                DownloadRequest(song("queued"), DownloadQueueState.QUEUED, 2L),
                DownloadRequest(song("failed"), DownloadQueueState.FAILED, 3L),
                DownloadRequest(song("done"), DownloadQueueState.COMPLETED, 4L),
                DownloadRequest(song("cancelled"), DownloadQueueState.CANCELLED, 5L),
            )
        val paused = changeDownloadQueueState(queue, "active", DownloadQueueState.PAUSED)
        assertEquals(DownloadQueueState.PAUSED, paused[0].state)
        assertEquals(queue.drop(1), paused.drop(1))
        assertSame(paused, updateDownloadQueue(paused, "active") { it.copy(state = DownloadQueueState.COMPLETED) })
        val restored = Json.decodeFromString<List<DownloadRequest>>(Json.encodeToString(paused))
        val resumed = changeDownloadQueueState(restored, "active", DownloadQueueState.QUEUED)
        assertEquals(DownloadQueueState.QUEUED, resumed[0].state)
        assertEquals(0L, resumed[0].downloadedBytes)
        assertNull(resumed[0].totalBytes)
        assertEquals(queue.drop(1), resumed.drop(1))
        val allPaused = changeDownloadQueueState(queue, null, DownloadQueueState.PAUSED)
        assertEquals(listOf(DownloadQueueState.PAUSED, DownloadQueueState.PAUSED), allPaused.take(2).map { it.state })
        assertEquals(queue.drop(2), allPaused.drop(2))
        assertTrue(
            changeDownloadQueueState(allPaused, null, DownloadQueueState.QUEUED).take(2).all { it.state == DownloadQueueState.QUEUED },
        )
    }

    @Test
    fun individualRetryClearsStaleProgressWithoutRequeueingOtherFailures() {
        val failure =
            DownloadRequest(
                song("failed"),
                DownloadQueueState.FAILED,
                1L,
                downloadedBytes = 50L,
                totalBytes = 100L,
                lastError = "network",
                attemptCount = 2,
            )
        val queue =
            listOf(failure, failure.copy(song = song("other")), failure.copy(song = song("paused"), state = DownloadQueueState.PAUSED))
        val retried = retryDownloadQueue(queue, "failed")
        assertEquals(DownloadQueueState.QUEUED, retried[0].state)
        assertEquals(0L, retried[0].downloadedBytes)
        assertNull(retried[0].totalBytes)
        assertNull(retried[0].lastError)
        assertEquals(2, retried[0].attemptCount)
        assertEquals(queue.drop(1), retried.drop(1))
        assertEquals(queue.last(), retryDownloadQueue(queue, null).last())
        assertTrue(retryDownloadQueue(queue, null).take(2).all { it.state == DownloadQueueState.QUEUED })
    }

    private fun song(id: String) =
        SongItem(
            id = id,
            title = id,
            thumbnail = null,
            artists = emptyList(),
            album = null,
        )
}
