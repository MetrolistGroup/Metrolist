package com.metrolist.android.recognition

import com.metrolist.shared.recognition.RecognitionResult
import com.metrolist.shared.recognition.RecognitionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecognitionCompletionTest {
    private val result = RecognitionResult("track", "Song", "Artist")

    @Test
    fun publishesMatchBeforePlaybackLookupCompletes() =
        runTest {
            val lookup = CompletableDeferred<String?>()
            val published = mutableListOf<RecognitionStatus.Success>()
            val storedIds = mutableListOf<String>()
            launch {
                completeRecognition(result, {}, { lookup.await() }, { storedIds += it }, { published += it }, backgroundScope)
            }
            runCurrent()
            assertEquals(listOf(RecognitionStatus.Success(result)), published)
            lookup.complete("video")
            runCurrent()
            assertEquals("video", published.last().result.youtubeVideoId)
            assertEquals(listOf("video"), storedIds)
        }

    @Test
    fun failedHistoryWriteKeepsMatchAndReportsThatItWasNotSaved() =
        runTest {
            val published = mutableListOf<RecognitionStatus.Success>()
            completeRecognition(
                result,
                save = { error("disk full") },
                resolve = { "video" },
                updateVideoId = { error("unsaved history must not be updated") },
                publish = { published += it },
                enrichmentScope = backgroundScope,
            )
            runCurrent()
            assertEquals(result, published.first().result)
            assertTrue(published.all { !it.historySaved })
            assertEquals("video", published.last().result.youtubeVideoId)
        }

    @Test
    fun optionalLookupFailureDoesNotReplaceTheMatchWithAnError() =
        runTest {
            val published = mutableListOf<RecognitionStatus.Success>()
            completeRecognition(result, {}, { error("offline") }, {}, { published += it }, backgroundScope)
            runCurrent()
            assertEquals(listOf(RecognitionStatus.Success(result)), published)
        }

    @Test
    fun cancellationIsNotReportedAsAStorageFailure() =
        runTest {
            var published = false
            try {
                completeRecognition(result, { throw CancellationException() }, { null }, {}, { published = true }, backgroundScope)
                error("Expected cancellation")
            } catch (_: CancellationException) {
                assertFalse(published)
            }
        }
}
