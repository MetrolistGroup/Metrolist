package com.metrolist.shared.api.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoogleRomanizationFailureTest {
    @Test
    fun failedRequestsCannotReturnOriginalLyricsAsSuccessfulRomanization() =
        runBlocking {
            for (failure in listOf("http", "network", "malformed", "empty", "short", "extra")) {
                HttpClient(
                    MockEngine {
                        if (failure == "network") throw IOException("Offline")
                        respond(
                            when (failure) {
                                "malformed" -> "{"
                                "empty" -> "[]"
                                "short" -> payload("annyeong")
                                "extra" -> payload("annyeong|sarang|extra")
                                else -> payload("annyeong|sarang")
                            },
                            if (failure == "http") HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                        )
                    },
                ).use { http ->
                    assertTrue(romanize(http, listOf("안녕", "사랑")).isFailure, failure)
                }
            }
        }

    @Test
    fun failedLaterBatchCannotBeCachedAsPartialSuccess() =
        runBlocking {
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    respond(
                        payload(List(50) { "annyeong" }.joinToString("|")),
                        if (requests ==
                            1
                        ) {
                            HttpStatusCode.OK
                        } else {
                            HttpStatusCode.ServiceUnavailable
                        },
                    )
                },
            ).use { http ->
                assertTrue(romanize(http, List(51) { "안녕" }).isFailure)
                assertEquals(2, requests)
            }
        }

    @Test
    fun successfulRomanizationPreservesLatinAndBlankLinePositions() =
        runBlocking {
            HttpClient(MockEngine { respond(payload("annyeong | sarang")) }).use { http ->
                assertEquals(
                    listOf("Stay", "annyeong", "", "sarang"),
                    romanize(http, listOf("Stay", "안녕", "", "사랑")).getOrThrow().romanizedLines,
                )
            }
        }

    @Test
    fun cancellationIsNotConvertedToProviderFailure() =
        runBlocking {
            HttpClient(MockEngine { throw CancellationException("Cancelled") }).use { http ->
                assertFailsWith<CancellationException> { romanize(http, listOf("안녕")) }
                Unit
            }
        }

    @Test
    fun transientFailureUsesTheExistingTranslationRetry() =
        runBlocking {
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    respond(payload("annyeong"), if (requests == 1) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK)
                },
            ).use { http ->
                val result = retryLyricsTranslation { romanize(http, listOf("안녕")) }
                assertEquals(listOf("annyeong"), result.getOrThrow().romanizedLines)
                assertEquals(2, requests)
            }
        }

    @Test
    fun latinOnlyLyricsDoNotNeedANetworkRequest() =
        runBlocking {
            val lines = listOf("Stay", "", "123!")
            HttpClient(MockEngine { error("No request expected") }).use { http ->
                assertEquals(lines, romanize(http, lines).getOrThrow().romanizedLines)
            }
        }

    private suspend fun romanize(
        http: HttpClient,
        lines: List<String>,
    ) = LyricsTranslationClient(http).translateLyricsLines(
        lines = lines,
        provider = "Google Translate",
        targetLanguage = "en",
        mode = "Romanized",
        apiKey = "",
        baseUrl = "",
        model = "",
    )

    private fun payload(text: String) = """[[[null,null,null,"$text"]]]"""
}
