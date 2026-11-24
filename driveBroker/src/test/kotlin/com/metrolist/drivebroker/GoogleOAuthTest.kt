package com.metrolist.drivebroker

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GoogleOAuthTest {
    @Test
    fun `exchanges a mobile server authorization code and validates its audience`() =
        runBlocking {
            val configuration = testConfiguration()
            val client =
                oauthClient(
                    """{"access_token":"short-lived","refresh_token":"long-lived","id_token":"identity"}""",
                    """{"sub":"google-subject","email":"user@example.com","email_verified":"true","aud":"web-client-id","iss":"https://accounts.google.com","exp":"${System.currentTimeMillis() / 1000 + 3600}"}""",
                )

            val account = GoogleOAuth(client, configuration).exchange(ClientPlatform.ANDROID, "one-time-code", null, null)

            assertEquals(DriveAccount("google-subject", "web-client-id", "user@example.com", "long-lived"), account)
            client.close()
        }

    @Test
    fun `rejects an identity token minted for another client`() =
        runBlocking {
            val configuration = testConfiguration()
            val client =
                oauthClient(
                    """{"refresh_token":"long-lived","id_token":"identity"}""",
                    """{"sub":"google-subject","aud":"other-client-id","iss":"accounts.google.com","exp":${System.currentTimeMillis() / 1000 + 3600}}""",
                )

            assertNull(GoogleOAuth(client, configuration).exchange(ClientPlatform.ANDROID, "one-time-code", null, null))
            client.close()
        }

    @Test
    fun `exchanges an iOS authorization code with its registered redirect and PKCE verifier`() =
        runBlocking {
            val configuration = testConfiguration()
            val client =
                oauthClient(
                    """{"refresh_token":"long-lived","id_token":"identity"}""",
                    """{"sub":"google-subject","aud":"ios-client-id.apps.googleusercontent.com","iss":"accounts.google.com","exp":${System.currentTimeMillis() / 1000 + 3600}}""",
                )

            val account =
                GoogleOAuth(client, configuration).exchange(
                    ClientPlatform.IOS,
                    "one-time-code",
                    "com.googleusercontent.apps.ios-client-id:/oauth2redirect",
                    "v".repeat(43),
                )

            assertEquals("ios-client-id.apps.googleusercontent.com", account?.clientId)
            client.close()
        }

    @Test
    fun `requires loopback redirect and PKCE verifier for desktop exchange`() =
        runBlocking {
            val configuration = testConfiguration()
            var requests = 0
            val client =
                HttpClient(
                    MockEngine {
                        requests++
                        error("No request expected")
                    },
                )

            assertNull(
                GoogleOAuth(
                    client,
                    configuration,
                ).exchange(ClientPlatform.DESKTOP, "one-time-code", "https://example.com/callback", "invalid"),
            )
            assertEquals(0, requests)
            client.close()
        }

    @Test
    fun `treats a lost revocation response as ambiguous`() =
        runBlocking {
            val client =
                HttpClient(
                    MockEngine {
                        throw IOException("connection lost")
                    },
                )
            val error =
                runCatching {
                    GoogleOAuth(client, testConfiguration()).revoke("refresh-token")
                }.exceptionOrNull()
            assertTrue(error is GoogleRevocationAmbiguousException)
            assertTrue((error as? GoogleRevocationAmbiguousException)?.cause is IOException)
            client.close()
        }

    private fun oauthClient(
        tokenResponse: String,
        identityResponse: String,
    ): HttpClient =
        HttpClient(
            MockEngine { request ->
                val response = if (request.url.encodedPath == "/token") tokenResponse else identityResponse
                respond(
                    response,
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    private fun testConfiguration() =
        BrokerConfiguration(
            port = 8080,
            databaseUrl = "jdbc:postgresql://localhost/metrolist",
            databaseUser = "metrolist",
            databasePassword = "secret",
            encryptionKey = ByteArray(32),
            webClientId = "web-client-id",
            webClientSecret = "web-client-secret",
            androidClientId = "android-client-id",
            iosClientId = "ios-client-id.apps.googleusercontent.com",
            desktopClientId = "desktop-client-id",
        )
}
