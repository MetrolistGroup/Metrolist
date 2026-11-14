package com.metrolist.shared.viewmodels

import com.metrolist.shared.api.artwork.CanvasContentSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasFetchTest {
    @Test
    fun hiddenCanvasNeverFetchesAndCompletedLookupsSurviveRepeatsAndReopening() =
        runTest {
            val request = CanvasFetchRequest(backgroundScope)
            var fetches = 0
            request.launch("downloaded-song") { fetches++ }
            runCurrent()
            assertEquals(0, fetches)

            request.setVisible(true)
            request.launch("downloaded-song") { fetches++ }
            runCurrent()
            repeat(10) {
                request.launch("downloaded-song") { fetches++ }
                request.setVisible(false)
                request.setVisible(true)
                request.launch("downloaded-song") { fetches++ }
                runCurrent()
            }
            assertEquals(1, fetches)
            request.invalidate()
            request.launch("downloaded-song") { fetches++ }
            runCurrent()
            assertEquals(2, fetches)
        }

    @Test
    fun hidingCancelsNetworkRetriesAndReopeningRestartsOnlyUnfinishedWork() =
        runTest {
            val request = CanvasFetchRequest(backgroundScope)
            var fetches = 0
            request.setVisible(true)
            request.launch("song") {
                retryCanvasLookup<String> {
                    fetches++
                    error("Network failure")
                }
            }
            runCurrent()
            request.setVisible(false)
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(1, fetches)
            request.setVisible(true)
            request.launch("song") { fetches++ }
            runCurrent()
            assertEquals(2, fetches)
        }

    @Test
    fun switchingRequestsCancelsOldDownloadAndUnavailableResultIsRemembered() =
        runTest {
            val request = CanvasFetchRequest(backgroundScope)
            val pending = CompletableDeferred<Unit>()
            var publishedOld = false
            var lookups = 0
            request.setVisible(true)
            request.launch("old") {
                pending.await()
                publishedOld = true
            }
            runCurrent()
            request.launch("new") { lookups++ }
            pending.complete(Unit)
            runCurrent()
            request.setVisible(false)
            request.setVisible(true)
            request.launch("new") { lookups++ }
            runCurrent()
            assertFalse(publishedOld)
            assertEquals(1, lookups)
        }

    @Test
    fun playbackFailureIsReportedBeforeRefreshAndRepeatedFailureFallsBack() =
        runTest {
            val recovery = CanvasPlaybackRecovery()
            val events = mutableListOf<String>()

            suspend fun retry() =
                recovery.recover(
                    reportFailure = { events += "exclude failed profile" },
                    refresh = {
                        events += "resolve fresh stream"
                        "canvas"
                    },
                )
            assertEquals("canvas", retry())
            assertEquals(listOf("exclude failed profile", "resolve fresh stream"), events)
            assertEquals(null, retry())
            assertEquals(listOf("exclude failed profile", "resolve fresh stream", "exclude failed profile"), events)
            recovery.reset()
            assertEquals("canvas", retry())
            assertEquals(5, events.size)
        }

    @Test
    fun expiredUrlRefreshesWithoutExcludingItsOtherwiseHealthyProfile() =
        runTest {
            val recovery = CanvasPlaybackRecovery()
            assertEquals(
                "fresh-url",
                recovery.recover(
                    expired = true,
                    reportFailure = { error("Expiry is not a profile failure") },
                    refresh = { "fresh-url" },
                ),
            )
            assertEquals(null, recovery.recover<String>(expired = true, reportFailure = {}) { error("Already refreshed") })
        }

    @Test
    fun cancellingRecoveryDoesNotRefreshOrAllowAnUnboundedRetry() =
        runTest {
            val recovery = CanvasPlaybackRecovery()
            val waiting = CompletableDeferred<Unit>()
            var refreshed = false
            val job =
                async {
                    recovery.recover(reportFailure = { waiting.await() }) {
                        refreshed = true
                        "canvas"
                    }
                }
            runCurrent()
            job.cancel()
            waiting.complete(Unit)
            runCurrent()
            assertFalse(refreshed)
            assertEquals(null, recovery.recover<String>(reportFailure = {}) { error("Already attempted") })
        }

    @Test
    fun failedLookupRetriesAfterCooldownAndCancellationPreventsLateResults() =
        runTest {
            var attempts = 0
            val result = async { retryCanvasLookup { if (++attempts == 1) error("Transient stream failure") else "fresh-stream" } }
            runCurrent()
            assertEquals(1, attempts)
            advanceTimeBy(29_999)
            assertEquals(1, attempts)
            advanceTimeBy(1)
            runCurrent()
            assertEquals("fresh-stream", result.await())
            assertEquals(2, attempts)

            val cancelled =
                async {
                    retryCanvasLookup<String> {
                        attempts++
                        error("Still failing")
                    }
                }
            runCurrent()
            cancelled.cancel()
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(3, attempts)
        }

    @Test
    fun failingLookupStopsAfterThreeAttemptsAndMissingCanvasIsNotRetried() =
        runTest {
            var attempts = 0
            assertEquals(
                null,
                retryCanvasLookup<String> {
                    attempts++
                    error("Still failing")
                },
            )
            assertEquals(3, attempts)

            attempts = 0
            assertEquals(
                null,
                retryCanvasLookup<String> {
                    attempts++
                    null
                },
            )
            assertEquals(1, attempts)
        }

    @Test
    fun sourcesPublishIndependentlyAndOneFailureDoesNotDiscardTheOther() =
        runTest {
            val apple = CompletableDeferred<String?>()
            val published = mutableMapOf<CanvasContentSource, String?>()
            val job =
                async {
                    loadCanvasSources(
                        spotify = { "spotify.mp4" },
                        apple = { apple.await() },
                        onResult = { source, url -> published[source] = url },
                    )
                }
            runCurrent()
            assertEquals(mapOf<CanvasContentSource, String?>(CanvasContentSource.SPOTIFY_CANVAS to "spotify.mp4"), published)
            assertFalse(job.isCompleted)
            apple.completeExceptionally(IllegalStateException("Unavailable artwork"))
            assertEquals("spotify.mp4" to null, job.await())
            assertTrue(CanvasContentSource.APPLE_MUSIC in published)
        }

    @Test
    fun cancellingSourcesDoesNotPublishUnavailableResults() =
        runTest {
            var published = false
            val wait = CompletableDeferred<String?>()
            val job = async { loadCanvasSources({ wait.await() }, { wait.await() }) { _, _ -> published = true } }
            runCurrent()
            job.cancel()
            runCurrent()
            assertFalse(published)
        }

    @Test
    fun switchingSongsCancelsThePreviousCanvasBeforeItCanPublish() =
        runTest {
            val result = CompletableDeferred<Unit>()
            var published = false
            val request =
                launchCanvasFetch {
                    result.await()
                    published = true
                }
            runCurrent()
            request.cancel()
            result.complete(Unit)
            runCurrent()
            assertTrue(request.isCancelled)
            assertFalse(published)
        }

    @Test
    fun lookupFailureDoesNotCancelThePlayerScope() =
        runTest {
            val request = launchCanvasFetch { error("Test lookup failed") }
            request.join()
            assertFalse(request.isCancelled)
            var nextSongLoaded = false
            launchCanvasFetch { nextSongLoaded = true }.join()
            assertTrue(nextSongLoaded)
        }
}
