package com.metrolist.shared.api.lastfm

import com.metrolist.shared.api.ScrobbleApiException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LastFmAuthenticationTest {
    @BeforeTest
    fun initializeLastFm() {
        LastFM.initialize("api-key", "secret")
    }

    @Test
    fun signsSortedRawUtf8ParametersAndFormEncodesCredentialsExactlyOnce() =
        runBlocking {
            val credentials =
                listOf(
                    Triple("listener", "password", "3cc6cae3e1cd81e6ecbc26d61ad99c94"),
                    Triple("listener+tag@example.com", " päss+&=%密碼🔒 ", "2378f515b19ef0ac718f9aa50c7886e6"),
                    Triple("用户", " e\u0301\t\n", "7f0e28c27e81ff13a5dbe0dd4ddd3bd0"),
                )
            for ((username, password, signature) in credentials) {
                HttpClient(
                    MockEngine { request ->
                        assertEquals(HttpMethod.Post, request.method)
                        assertEquals("https://ws.audioscrobbler.com/2.0/", request.url.toString())
                        val body = assertIs<FormDataContent>(request.body)
                        assertEquals(ContentType.Application.FormUrlEncoded.withParameter("charset", "UTF-8"), body.contentType)
                        val parameters = parseQueryString(body.bytes().decodeToString())
                        assertEquals(
                            mapOf(
                                "method" to listOf("auth.getMobileSession"),
                                "username" to listOf(username),
                                "password" to listOf(password),
                                "api_key" to listOf("api-key"),
                                "api_sig" to listOf(signature),
                                "format" to listOf("json"),
                            ),
                            parameters.entries().associate { it.key to it.value },
                        )
                        respond(
                            """{"session":{"name":"canonical-name","key":"session-key","subscriber":0}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ).use { client ->
                    val session = LastFM.getMobileSession(client, username, password).getOrThrow().session
                    assertEquals("canonical-name", session?.name)
                    assertEquals("session-key", session?.key)
                }
            }
        }

    @Test
    fun onlyAuthenticationRejectionIsReportedAsInvalidCredentials() =
        runBlocking {
            for (code in listOf(4, 6, 8, 9, 10, 11, 13, 14, 16, 26, 29)) {
                client("""{"error":$code,"message":"API error"}""", HttpStatusCode.Forbidden).use { client ->
                    val failure = LastFM.getMobileSession(client, "listener", "password").exceptionOrNull()
                    assertEquals(code, assertIs<ScrobbleApiException>(failure).apiErrorCode)
                    assertEquals(
                        if (code == 4) LastFmLoginFailure.INVALID_CREDENTIALS else LastFmLoginFailure.SERVICE,
                        failure.lastFmLoginFailure(),
                        "Last.fm error $code",
                    )
                }
            }
        }

    @Test
    fun httpAndMalformedSessionFailuresAreServiceErrors() =
        runBlocking {
            for ((body, status) in listOf(
                "server unavailable" to HttpStatusCode.ServiceUnavailable,
                "forbidden" to HttpStatusCode.Forbidden,
                "not json" to HttpStatusCode.OK,
                "{}" to HttpStatusCode.OK,
                """{"session":{"name":"listener","key":""}}""" to HttpStatusCode.OK,
                """{"session":{"name":"","key":"key"}}""" to HttpStatusCode.OK,
            )) {
                client(body, status).use { client ->
                    val result = LastFM.getMobileSession(client, "listener", "password")
                    assertTrue(result.isFailure)
                    assertEquals(LastFmLoginFailure.SERVICE, result.exceptionOrNull()!!.lastFmLoginFailure())
                }
            }
        }

    @Test
    fun transportFailureIsAConnectionError() =
        runBlocking {
            for (failure in listOf(IOException("offline"), HttpRequestTimeoutException("https://ws.audioscrobbler.com/2.0/", 1L))) {
                HttpClient(MockEngine { throw failure }).use { client ->
                    val result = LastFM.getMobileSession(client, "listener", "password")
                    assertEquals(LastFmLoginFailure.CONNECTION, result.exceptionOrNull()!!.lastFmLoginFailure())
                }
            }
        }

    @Test
    fun cancellationPropagatesWithoutBecomingALoginFailure() =
        runBlocking {
            HttpClient(MockEngine { throw CancellationException("cancelled") }).use { client ->
                assertFailsWith<CancellationException> {
                    LastFM.getMobileSession(client, "listener", "password")
                }
                Unit
            }
        }

    private fun client(
        body: String,
        status: HttpStatusCode,
    ) = HttpClient(
        MockEngine {
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )
}
