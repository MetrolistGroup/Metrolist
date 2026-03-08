package com.metrolist.shared.api.lyrics

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.Headers
import io.ktor.http.headersOf
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LyricsTranslationFailureTest {
    @Test
    fun documentedErrorsDistinguishAccountQuotasFromBusyProviders() {
        val cases =
            listOf(
                Triple(404, "No endpoints found matching your data policy (Free model publication)", LyricsTranslationFailure.DATA_POLICY),
                Triple(401, "User not found", LyricsTranslationFailure.AUTHENTICATION),
                Triple(402, "Key limit exceeded", LyricsTranslationFailure.CREDIT_LIMIT),
                Triple(404, "No endpoints found for model", LyricsTranslationFailure.MODEL_UNAVAILABLE),
                Triple(429, "Rate limit exceeded: free-models-per-day", LyricsTranslationFailure.FREE_DAILY_LIMIT),
                Triple(429, "Rate limit exceeded: free-models-per-min", LyricsTranslationFailure.FREE_MINUTE_LIMIT),
                Triple(429, "Model is temporarily rate-limited upstream", LyricsTranslationFailure.MODEL_BUSY),
                Triple(429, "Rate limit exceeded", LyricsTranslationFailure.RATE_LIMIT),
                Triple(503, "No available model providers", LyricsTranslationFailure.MODEL_UNAVAILABLE),
                Triple(400, "Invalid parameter", LyricsTranslationFailure.REQUEST_FAILED),
            )
        for ((status, message, expected) in cases) {
            val body = """{"error":{"code":$status,"message":"$message"}}"""
            assertEquals(expected, LyricsTranslationHttpException(status, body).lyricsTranslationFailure())
            assertEquals(expected, classifyLyricsTranslationHttpFailure(200, body))
        }
        val upstream = """
                {"error":{"code":429,
                "message":"Rate limit exceeded",
                "metadata":{"provider_name":"Google","provider_code":"rate_limited","error_type":"rate_limit_exceeded"}}}
                """
        assertEquals(
            LyricsTranslationFailure.MODEL_BUSY,
            classifyLyricsTranslationHttpFailure(429, upstream, headersOf("Retry-After", "60")),
        )
        val accountHeaders =
            Headers.build {
                append("X-RateLimit-Remaining", "0")
                append("X-RateLimit-Reset", "1900000000000")
                append("Retry-After", "60")
            }
        assertEquals(LyricsTranslationFailure.RATE_LIMIT, classifyLyricsTranslationHttpFailure(429, upstream, accountHeaders))
        assertEquals(LyricsTranslationFailure.RATE_LIMIT, classifyLyricsTranslationHttpFailure(429, "not JSON", accountHeaders))
        assertEquals(
            LyricsTranslationFailure.MODEL_UNAVAILABLE,
            classifyLyricsTranslationHttpFailure(
                200,
                """{"choices":[{"error":{"code":502,"metadata":{"error_type":"provider_unavailable"}}}]}""",
            ),
        )
    }

    @Test
    fun failureDoesNotRetainProviderTextOrCredentialsAndReadsRetryHeaders() {
        val failure = LyricsTranslationHttpException(429, "sk-or-private response body", headersOf("Retry-After", "45"))
        assertFalse(failure.toString().contains("sk-or-private"))
        assertFalse(failure.toString().contains("response body"))
        assertEquals(46_000L, failure.retryAtMillis(1_000))
        assertEquals(
            1_900_000_000_000,
            LyricsTranslationHttpException(429, "", headersOf("X-RateLimit-Reset", "1900000000")).retryAtMillis(1_000),
        )
        assertEquals(LyricsTranslationFailure.NETWORK, IOException("offline").lyricsTranslationFailure())
        assertEquals(LyricsTranslationFailure.TIMEOUT, HttpRequestTimeoutException("https://openrouter.ai", 100).lyricsTranslationFailure())
    }
}
