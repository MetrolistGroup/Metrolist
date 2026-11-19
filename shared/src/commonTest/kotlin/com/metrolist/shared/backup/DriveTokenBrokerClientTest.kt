package com.metrolist.shared.backup

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class DriveTokenBrokerClientTest {
    @Test
    fun `loads public oauth configuration from broker`() =
        runTest {
            val client =
                brokerClient(
                    "/v1/config",
                    """{"webClientId":"web","androidClientId":"android","iosClientId":"ios","desktopClientId":"desktop"}""",
                )

            assertEquals("web", DriveTokenBrokerClient("https://broker.example", client).configuration().webClientId)
            client.close()
        }

    @Test
    fun `exchanges authorization codes through the broker`() =
        runTest {
            val client = brokerClient("/v1/auth/exchange", """{"sessionToken":"opaque-session-token","email":"user@example.com"}""")

            val session =
                DriveTokenBrokerClient("https://broker.example/", client)
                    .exchangeCode(DriveClientPlatform.ANDROID, "one-time-code")

            assertEquals("opaque-session-token", session.sessionToken)
            client.close()
        }

    private fun brokerClient(
        path: String,
        response: String,
    ): HttpClient =
        HttpClient(
            MockEngine { request ->
                assertEquals(path, request.url.encodedPath)
                respond(
                    response,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
}
