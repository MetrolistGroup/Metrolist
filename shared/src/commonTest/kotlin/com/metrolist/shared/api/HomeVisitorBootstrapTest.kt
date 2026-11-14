package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.models.YouTubeLocale
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeVisitorBootstrapTest {
    @Test
    fun pendingHomeResponseAcceptsSuppressedInitialVisitorPublication() = checkPendingHome(accountSwitch = false)

    @Test
    fun pendingHomeResponseRejectsAnAccountSwitch() = checkPendingHome(accountSwitch = true)

    private fun checkPendingHome(accountSwitch: Boolean) =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val previous = responseDecodeDispatcher
            responseDecodeDispatcher = StandardTestDispatcher(testScheduler)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var requests = 0
            val client =
                HttpClient(
                    MockEngine(
                        MockEngineConfig().apply {
                            dispatcher = StandardTestDispatcher(testScheduler)
                            addHandler {
                                requests++
                                started.complete(Unit)
                                release.await()
                                respond(
                                    """{"contents":{"sectionListRenderer":{"contents":[]}}}""",
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }
                        },
                    ),
                ) { install(ContentNegotiation) { json() } }
            try {
                val tube = InnerTube(client).apply { regionOverrideActive = true }
                val response = async { YouTubeRepository(tube).getHomePageData() }
                started.await()
                if (accountSwitch) tube.cookie = "SAPISID=other" else tube.visitorData = "bootstrap"
                release.complete(Unit)
                if (accountSwitch) assertFailsWith<CancellationException> { response.await() } else response.await()
                assertEquals(1, requests)
            } finally {
                client.close()
                responseDecodeDispatcher = previous
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun bootstrapExceptionRejectsAccountLocalePolicyAndLaterVisitorChanges() =
        runTest {
            val client = HttpClient(MockEngine { error("No request expected") })
            try {
                val tube = InnerTube(client).apply { regionOverrideActive = true }
                val initial = tube.sessionSnapshot()
                tube.visitorData = "bootstrap"
                assertTrue(tube.acceptsHomeVisitorBootstrap(initial))
                val published = tube.sessionSnapshot()
                tube.visitorData = "replacement"
                assertFalse(tube.acceptsHomeVisitorBootstrap(published))
                assertFalse(tube.acceptsHomeVisitorBootstrap(initial))
                for (change in listOf<(InnerTube) -> Unit>(
                    { it.cookie = "SAPISID=other" },
                    { it.dataSyncId = "other-channel" },
                    { it.authUser = "1" },
                    { it.locale = YouTubeLocale(gl = "PL", hl = "pl") },
                    { it.useLoginForBrowse = true },
                    { it.regionOverrideActive = false },
                )) {
                    val other = InnerTube(client).apply { regionOverrideActive = true }
                    val expected = other.sessionSnapshot()
                    change(other)
                    other.visitorData = "bootstrap"
                    assertFalse(other.acceptsHomeVisitorBootstrap(expected))
                }
            } finally {
                client.close()
            }
        }
}
