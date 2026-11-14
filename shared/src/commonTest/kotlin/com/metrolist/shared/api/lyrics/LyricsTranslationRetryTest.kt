package com.metrolist.shared.api.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsTranslationRetryTest {
    @Test
    fun retriesOnceAfterRequestOrResponseFailure() =
        runTest {
            for (error in listOf(
                LyricsTranslationHttpException(503, "Unavailable"),
                LyricsTranslationException(LyricsTranslationFailure.EMPTY_RESPONSE, "Empty response"),
                IllegalStateException("Connection lost"),
            )) {
                for (succeeds in listOf(false, true)) {
                    var attempts = 0
                    val result =
                        retryLyricsTranslation {
                            attempts++
                            if (succeeds && attempts == 2) Result.success("translated") else Result.failure(error)
                        }
                    assertEquals(2, attempts)
                    assertEquals(succeeds, result.isSuccess)
                }
            }
        }

    @Test
    fun credentialsAndRateLimitsDoNotRetry() =
        runTest {
            for (error in listOf(
                IllegalArgumentException("Missing credentials"),
                LyricsTranslationHttpException(401, "Unauthorized"),
                LyricsTranslationHttpException(403, "Forbidden"),
                LyricsTranslationHttpException(429, "Rate limited"),
                LyricsTranslationHttpException(429, """{"error":{"message":"free-models-per-day"}}"""),
                LyricsTranslationException(LyricsTranslationFailure.MODEL_BUSY, "Upstream rate limited"),
            )) {
                var attempts = 0
                assertTrue(
                    retryLyricsTranslation<String> {
                        attempts++
                        Result.failure(error)
                    }.isFailure,
                )
                assertEquals(1, attempts)
            }
        }

    @Test
    fun cancellationNeverRetriesOrBecomesFailure() =
        runTest {
            for (returned in listOf(false, true)) {
                var attempts = 0
                assertFailsWith<CancellationException> {
                    retryLyricsTranslation<String> {
                        attempts++
                        if (returned) Result.failure(CancellationException()) else throw CancellationException()
                    }
                }
                assertEquals(1, attempts)
            }
            var attempts = 0
            val job =
                launch {
                    retryLyricsTranslation<String> {
                        attempts++
                        Result.failure(IllegalStateException("Unavailable"))
                    }
                }
            runCurrent()
            job.cancelAndJoin()
            assertEquals(1, attempts)
        }
}
