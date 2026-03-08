package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.YouTubeLocale
import com.metrolist.shared.auth.AccountDiscoveryAction
import com.metrolist.shared.auth.YouTubeSessionState
import com.metrolist.shared.auth.accountDiscoveryAction
import com.metrolist.shared.auth.selectionDataSyncId
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackEventLog
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YouTubeAccountsListTest {
    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        YouTubeSessionState.dismissExpiredSessionNotice()
        PlaybackEventLog.clear()
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
        YouTubeSessionState.dismissExpiredSessionNotice()
        PlaybackEventLog.clear()
    }

    @Test
    fun channelSwitcherKeepsBothSelectableAccountsAndTheNonDefaultSession() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertEquals("www.youtube.com", request.url.host)
                    assertEquals("/youtubei/v1/account/accounts_list", request.url.encodedPath)
                    assertEquals("2", request.headers["X-Goog-AuthUser"])
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    val client =
                        body
                            .getValue("context")
                            .jsonObject
                            .getValue("client")
                            .jsonObject
                    assertEquals("fr", client.getValue("hl").jsonPrimitive.content)
                    assertEquals("CA", client.getValue("gl").jsonPrimitive.content)
                    respond(CHANNELS, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            client(engine).use { client ->
                val tube =
                    InnerTube(client).apply {
                        locale = YouTubeLocale(gl = "CA", hl = "fr")
                        replaceSession("SAPISID=fresh", "visitor", "personal", "2", true)
                    }
                val accounts = YouTubeRepository(tube).getAccountsList(false)
                val selectable = accounts.filter { it.selectionDataSyncId("personal") != null || !it.signinUrl.isNullOrBlank() }
                assertEquals(listOf("Personal", "Brand"), selectable.map { it.name })
                assertEquals(AccountDiscoveryAction.SELECT_ACCOUNT, accountDiscoveryAction(selectable.size, true))
                assertEquals("brand", selectable.last().selectionDataSyncId("personal"))
            }
        }

    @Test
    fun rejectedHttpResponsesCannotBeParsedAsSuccessfulChannelLists() =
        runTest {
            for (status in listOf(400, 401, 403, 429, 503)) {
                client(MockEngine { respond(CHANNELS, HttpStatusCode.fromValue(status)) }).use { client ->
                    val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=stored" }
                    assertTrue(YouTubeRepository(tube).getAccountsList(false).isEmpty())
                    assertFalse(YouTubeSessionState.showExpiredSessionNotice)
                    assertEquals(status != 401, tube.hasSapCookieAuth())
                    val log = PlaybackEventLog.exportText()
                    assertContains(log, "Accounts list status=$status")
                    assertFalse(log.contains("Personal"))
                    assertFalse(log.contains("SAPISID=stored"))
                }
            }
        }

    @Test
    fun malformedResponsesAndNetworkFailuresPreserveTheSession() =
        runTest {
            for (networkFailure in listOf(false, true)) {
                client(
                    MockEngine {
                        if (networkFailure) throw IllegalStateException("Private error details")
                        respond("{", HttpStatusCode.OK)
                    },
                ).use { client ->
                    val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=stored" }
                    val session = tube.sessionSnapshot()
                    assertTrue(YouTubeRepository(tube).getAccountsList(false).isEmpty())
                    assertEquals(session, tube.sessionSnapshot())
                    val log = PlaybackEventLog.exportText()
                    assertContains(log, "Accounts list failed status=${if (networkFailure) "null" else "200"} type=")
                    if (networkFailure) assertContains(log, "type=IllegalStateException")
                    assertFalse(log.contains("Private error details"))
                }
            }
        }

    @Test
    fun replacingTheSessionWhileLoadingCannotReturnThePreviousAccounts() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            client(
                MockEngine {
                    started.complete(Unit)
                    release.await()
                    respond(CHANNELS, HttpStatusCode.OK)
                },
            ).use { client ->
                val tube = InnerTube(client).apply { cookie = "SAPISID=stored" }
                val result = async { YouTubeRepository(tube).getAccountsList(false) }
                started.await()
                tube.cookie = "SAPISID=replacement"
                release.complete(Unit)
                assertFailsWith<CancellationException> { result.await() }
                assertEquals("SAPISID=replacement", tube.cookie)
            }
        }

    private fun client(engine: MockEngine) =
        HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

    companion object {
        private const val CHANNELS = """{"actions":[{"updateChannelSwitcherPageAction":{"page":{"channelSwitcherPageRenderer":{"contents":[
            {"accountItemRenderer":{"accountName":{"simpleText":"Personal"},"isSelected":true,"serviceEndpoint":{
                "selectActiveIdentityEndpoint":{"supportedTokens":[{"pageIdToken":{"pageId":""}},{"datasyncIdToken":{"datasyncIdToken":"||personal"}}]}
            }}},
            {"accountItemRenderer":{"accountName":{"simpleText":"Brand"},"serviceEndpoint":{
                "selectActiveIdentityEndpoint":{"supportedTokens":[{"pageIdToken":{"pageId":""}},{"datasyncIdToken":{"datasyncIdToken":"brand||personal"}}]}
            }}}
        ]}}}}]}"""
    }
}
