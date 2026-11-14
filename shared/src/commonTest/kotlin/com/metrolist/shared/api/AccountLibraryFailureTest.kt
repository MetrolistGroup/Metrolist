package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.auth.YouTubeSessionState
import com.metrolist.shared.auth.YouTubeSyncState
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountLibraryFailureTest {
    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        YouTubeSessionState.dismissExpiredSessionNotice()
        YouTubeSyncState.clear()
    }

    @AfterTest
    fun tearDown() {
        YouTubeSessionState.dismissExpiredSessionNotice()
        YouTubeSyncState.clear()
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun confirmedLibraryRejectionOpensTheExistingSignInNotice() =
        runBlocking {
            for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
                YouTubeSessionState.dismissExpiredSessionNotice()
                client { "{}" to status }.use { client ->
                    val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
                    assertTrue(YouTubeRepository(tube).getLibraryUploadSongsResult().isFailure)
                    assertTrue(YouTubeSessionState.showExpiredSessionNotice)
                    assertFalse(tube.hasSapCookieAuth())
                }
            }
        }

    @Test
    fun browseOnlyForbiddenDoesNotExpireAValidAccount() =
        runBlocking {
            client { body ->
                if ("FEmusic_library_privately_owned_tracks" in body) {
                    "{}" to HttpStatusCode.Forbidden
                } else {
                    """{"activeAccountHeaderRenderer":{"accountName":{"runs":[{"text":"Music account"}]}}}""" to HttpStatusCode.OK
                }
            }.use { client ->
                val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
                val before = tube.sessionSnapshot()
                assertTrue(YouTubeRepository(tube).getLibraryUploadSongsResult().isFailure)
                assertEquals(before, tube.sessionSnapshot())
                assertFalse(YouTubeSessionState.showExpiredSessionNotice)
            }
        }

    @Test
    fun loggedOutSuccessResponseIsNotAnEmptyLibrary() =
        runBlocking {
            val loggedOut = """{
                "responseContext":{"mainAppWebResponseContext":{"loggedOut":true}},
                "contents":{"sectionListRenderer":{"contents":[]}}
            }"""
            client { loggedOut to HttpStatusCode.OK }.use { client ->
                val tube = InnerTube(client).apply { cookie = "SAPISID=test" }
                assertTrue(YouTubeRepository(tube).getLibraryUploadSongsResult().isFailure)
                assertTrue(YouTubeSessionState.showExpiredSessionNotice)
                assertFalse(tube.hasSapCookieAuth())
            }
        }

    @Test
    fun malformedAndUnavailableLibrariesStayIncompleteWithoutSigningOut() =
        runBlocking {
            for ((body, status) in listOf("{" to HttpStatusCode.OK, "{}" to HttpStatusCode.OK, "{}" to HttpStatusCode.ServiceUnavailable)) {
                client { body to status }.use { client ->
                    val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=test" }
                    val before = tube.sessionSnapshot()
                    assertFalse(YouTubeRepository(tube).getCompleteLibraryItemsResult().complete)
                    assertEquals(before, tube.sessionSnapshot())
                    assertFalse(YouTubeSessionState.showExpiredSessionNotice)
                }
            }
        }

    @Test
    fun aRealEmptyLibraryStillCompletes() =
        runBlocking {
            client { EMPTY_LIBRARY to HttpStatusCode.OK }.use { client ->
                val result = YouTubeRepository(InnerTube(client).apply { cookie = "SAPISID=test" }).getCompleteLibraryItemsResult()
                assertTrue(result.complete)
                assertTrue(result.items.isEmpty())
            }
        }

    private fun client(response: (String) -> Pair<String, HttpStatusCode>): HttpClient =
        HttpClient(
            MockEngine { request ->
                val (body, status) = response((request.body as TextContent).text)
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

    companion object {
        const val EMPTY_LIBRARY = """{"contents":{"sectionListRenderer":{"contents":[]}}}"""
    }
}
