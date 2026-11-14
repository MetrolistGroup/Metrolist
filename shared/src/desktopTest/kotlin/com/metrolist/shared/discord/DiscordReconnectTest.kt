package com.metrolist.shared.discord

import androidx.compose.runtime.snapshots.Snapshot
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.MetrolistClock
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiscordReconnectTest {
    @Test
    fun timedOutHandshakesRetryAndReplayPresenceWithoutLifecycleChanges() =
        runTest {
            withManager(refreshAvailable = true, settingsVisible = false, stalledHandshakes = 2) { server, _ ->
                DiscordRpcManager.setActivity(activity("Track"), "song")
                runCurrent()
                assertEquals(DiscordRpcManager.Status.Authorizing, DiscordRpcManager.connectionStatus.value)
                assertEquals(1, server.handshakes)

                advanceTimeBy(15_000)
                runCurrent()
                assertEquals(1, server.cancelledHandshakes)
                assertEquals(DiscordRpcManager.Status.Disconnected, DiscordRpcManager.connectionStatus.value)
                advanceTimeBy(999)
                runCurrent()
                assertEquals(1, server.handshakes)
                advanceTimeBy(1)
                runCurrent()
                assertEquals(2, server.handshakes)

                advanceTimeBy(15_000)
                runCurrent()
                assertEquals(2, server.cancelledHandshakes)
                advanceTimeBy(1_999)
                runCurrent()
                assertEquals(2, server.handshakes)
                advanceTimeBy(1)
                runCurrent()
                assertEquals(3, server.handshakes)
                assertEquals(1, server.connections)
                assertEquals(DiscordRpcManager.Status.Connected, DiscordRpcManager.connectionStatus.value)
                assertEquals("Track", server.lastDetails)
                assertEquals(listOf("saved-refresh"), server.refreshTokens)
                assertNull(DiscordRpcManager.lastError.value)
            }
        }

    @Test
    fun cancelledRefreshPropagatesCancellation() =
        runTest {
            HttpClient(MockEngine { throw CancellationException("Cancelled refresh") }).use { client ->
                assertFailsWith<CancellationException> { DiscordAuth(client).refresh("refresh") }
            }
        }

    @Test
    fun expiredSavedCredentialsRetryAndReplayPresenceAfterTransientRefreshFailure() =
        runTest {
            withManager { server, _ ->
                DiscordRpcManager.setActivity(
                    DiscordActivity(
                        name = "Metrolist",
                        state = "Artist",
                        details = "Track",
                        startTimestamp = 0L,
                        endTimestamp = null,
                        largeImage = null,
                        largeText = null,
                        smallImage = null,
                        smallText = null,
                        button1Label = null,
                        button1Url = null,
                        button2Label = null,
                        button2Url = null,
                    ),
                    songId = "song",
                )
                assertNotNull(DiscordRpcManager.lastError.value)
                server.refreshAvailable = true

                advanceTimeBy(999)
                runCurrent()
                assertEquals(listOf("saved-refresh"), server.refreshTokens)
                advanceTimeBy(1)
                runCurrent()
                assertTrue(server.presence.isCompleted)
                assertEquals(DiscordRpcManager.Status.Connected, DiscordRpcManager.connectionStatus.value)
                assertEquals("refreshed-token", DiscordTokenStore.retrieve())
                assertEquals("rotated-refresh", DiscordTokenStore.getRefreshToken())
                assertEquals(listOf("saved-refresh", "saved-refresh"), server.refreshTokens)
                assertNull(DiscordRpcManager.lastError.value)
            }
        }

    @Test
    fun rejectedUnexpiredAccessTokenIsRefreshedBeforeReidentifying() =
        runTest {
            withManager(expired = false, refreshAvailable = true) { server, _ ->
                assertEquals(DiscordRpcManager.Status.Connected, DiscordRpcManager.connectionStatus.value)
                assertEquals(listOf("saved-refresh"), server.refreshTokens)
                assertEquals("refreshed-token", DiscordRpcManager.accessTokenFlow.value)
                assertNull(DiscordRpcManager.lastError.value)
            }
        }

    @Test
    fun rejectedCredentialsRetryTransientRefreshFailures() =
        runTest {
            withManager(expired = false) { server, _ ->
                assertNotNull(DiscordRpcManager.lastError.value)
                server.refreshAvailable = true
                advanceTimeBy(1_000)
                runCurrent()

                assertEquals(DiscordRpcManager.Status.Connected, DiscordRpcManager.connectionStatus.value)
                assertEquals(listOf("saved-refresh", "saved-refresh"), server.refreshTokens)
                assertNull(DiscordRpcManager.lastError.value)
            }
        }

    @Test
    fun invalidGrantStaysDisconnectedWithoutRetryingRevokedCredentials() =
        runTest {
            withManager(invalidGrant = true) { server, _ ->
                assertNotNull(DiscordRpcManager.lastError.value)
                advanceTimeBy(120_000)
                runCurrent()

                assertEquals(listOf("saved-refresh"), server.refreshTokens)
                assertEquals(DiscordRpcManager.Status.Disconnected, DiscordRpcManager.connectionStatus.value)
                assertEquals("discord_error_token_refresh_failed", DiscordRpcManager.lastError.value?.type)
            }
        }

    @Test
    fun disablingPresenceStopsRefreshRetriesAndReenablingReconnects() =
        runTest {
            withManager { server, settings ->
                assertNotNull(DiscordRpcManager.lastError.value)
                settings.enableDiscordIntegration = false
                Snapshot.sendApplyNotifications()
                runCurrent()
                server.refreshAvailable = true
                advanceTimeBy(120_000)
                runCurrent()
                assertEquals(listOf("saved-refresh"), server.refreshTokens)
                assertEquals(DiscordRpcManager.Status.Disconnected, DiscordRpcManager.connectionStatus.value)

                settings.enableDiscordIntegration = true
                Snapshot.sendApplyNotifications()
                runCurrent()
                assertEquals(DiscordRpcManager.Status.Connected, DiscordRpcManager.connectionStatus.value)
                assertEquals(listOf("saved-refresh", "saved-refresh"), server.refreshTokens)
            }
        }

    @Test
    fun logoutStopsRefreshRetriesWithoutRestoringCredentials() =
        runTest {
            withManager { server, _ ->
                assertNotNull(DiscordRpcManager.lastError.value)
                DiscordRpcManager.logout()
                server.refreshAvailable = true
                advanceTimeBy(120_000)
                runCurrent()

                assertEquals(listOf("saved-refresh"), server.refreshTokens)
                assertNull(DiscordTokenStore.retrieve())
                assertNull(DiscordRpcManager.accessTokenFlow.value)
                assertEquals(DiscordRpcManager.Status.Disconnected, DiscordRpcManager.connectionStatus.value)
            }
        }

    @Test
    fun normalRemoteCloseReconnectsButLogoutDoesNot() =
        runTest {
            withManager(refreshAvailable = true) { server, _ ->
                val connections = server.connections
                server.closeRemote(1000)
                runCurrent()
                advanceTimeBy(1_000)
                runCurrent()
                assertEquals(connections + 1, server.connections)
                assertEquals(DiscordRpcManager.Status.Connected, DiscordRpcManager.connectionStatus.value)
                DiscordRpcManager.logout()
                advanceTimeBy(120_000)
                runCurrent()
                assertEquals(connections + 1, server.connections)
            }
        }

    @Test
    fun gatewayRateLimitWaitsSixtySeconds() =
        runTest {
            withManager(refreshAvailable = true) { server, _ ->
                val connections = server.connections
                server.closeRemote(4008)
                runCurrent()
                advanceTimeBy(59_999)
                runCurrent()
                assertEquals(connections, server.connections)
                advanceTimeBy(1)
                runCurrent()
                assertEquals(connections + 1, server.connections)
            }
        }

    @Test
    fun invalidGatewayConfigurationStopsAndSurfacesAnError() =
        runTest {
            for (code in 4010..4014) {
                withManager(refreshAvailable = true) { server, _ ->
                    val connections = server.connections
                    server.closeRemote(code)
                    runCurrent()
                    advanceTimeBy(120_000)
                    runCurrent()
                    assertEquals(connections, server.connections)
                    assertEquals("discord_error_gateway_configuration", DiscordRpcManager.lastError.value?.type)
                }
            }
        }

    @Test
    fun logoutInvalidatesAQueuedLogin() =
        runTest {
            withManager { server, _ ->
                val connections = server.connections
                DiscordRpcManager.storeAndConnect("new-token", "new-refresh", 7200)
                DiscordRpcManager.logout()
                runCurrent()
                assertEquals(connections, server.connections)
                assertNull(DiscordRpcManager.accessTokenFlow.value)
            }
        }

    @Test
    fun lateProfileResponseCannotRestoreLoggedOutUser() =
        runTest {
            withManager(refreshAvailable = true) { server, settings ->
                val gate = CompletableDeferred<Unit>()
                server.profileGate = gate
                DiscordRpcManager.storeAndConnect("new-token", "new-refresh", 7200)
                runCurrent()
                DiscordRpcManager.logout()
                gate.complete(Unit)
                runCurrent()
                assertNull(DiscordRpcManager.currentUser.value)
                assertEquals("", settings.discordUsername)
                assertNull(DiscordRpcManager.accessTokenFlow.value)
            }
        }

    @Test
    fun manualRefreshQueuesAnOtherwiseIdenticalPresence() =
        runTest {
            withManager(refreshAvailable = true) { server, _ ->
                val activity =
                    DiscordActivity(
                        name = "Metrolist",
                        state = "Artist",
                        details = "Track",
                        startTimestamp = 0L,
                        endTimestamp = null,
                        largeImage = null,
                        largeText = null,
                        smallImage = null,
                        smallText = null,
                        button1Label = null,
                        button1Url = null,
                        button2Label = null,
                        button2Url = null,
                    )
                DiscordRpcManager.setActivity(activity, "song")
                runCurrent()
                val sent = server.presenceCount
                DiscordRpcManager.setActivity(activity, "song")
                runCurrent()
                assertEquals(sent, server.presenceCount)
                assertEquals(DiscordRpcManager.RefreshResult.Queued, DiscordRpcManager.refreshPresence(activity, "song"))
                advanceTimeBy(5_001)
                runCurrent()
                assertEquals(sent + 1, server.presenceCount)
            }
        }

    @Test
    fun idleClosesTheSocketAndHeartbeatThenResumeReplaysTheLatestPresence() =
        runTest {
            withManager(refreshAvailable = true, settingsVisible = false) { server, _ ->
                DiscordRpcManager.setActivity(activity("First"), "first")
                runCurrent()
                DiscordRpcManager.clear()
                runCurrent()
                advanceTimeBy(29_999)
                runCurrent()
                assertEquals(DiscordRpcManager.Status.Connected, DiscordRpcManager.connectionStatus.value)
                advanceTimeBy(1)
                runCurrent()
                assertEquals(DiscordRpcManager.Status.Disconnected, DiscordRpcManager.connectionStatus.value)
                assertTrue(server.clears > 0)
                val connections = server.connections
                val heartbeats = server.heartbeats
                advanceTimeBy(120_000)
                runCurrent()
                assertEquals(connections, server.connections)
                assertEquals(heartbeats, server.heartbeats)
                assertEquals("refreshed-token", DiscordTokenStore.retrieve())

                DiscordRpcManager.setActivity(activity("Latest"), "latest")
                runCurrent()
                assertEquals(connections + 1, server.connections)
                assertEquals(DiscordRpcManager.Status.Connected, DiscordRpcManager.connectionStatus.value)
                advanceTimeBy(5_001)
                runCurrent()
                assertEquals("Latest", server.lastDetails)
            }
        }

    @Test
    fun resumingDuringTheGracePeriodKeepsTheExistingConnection() =
        runTest {
            withManager(refreshAvailable = true, settingsVisible = false) { server, _ ->
                DiscordRpcManager.setActivity(activity("First"), "first")
                runCurrent()
                val connections = server.connections
                DiscordRpcManager.clear()
                runCurrent()
                advanceTimeBy(20_000)
                DiscordRpcManager.setActivity(activity("Resumed"), "first")
                runCurrent()
                advanceTimeBy(20_000)
                runCurrent()
                assertEquals(connections, server.connections)
                assertEquals(DiscordRpcManager.Status.Connected, DiscordRpcManager.connectionStatus.value)
                assertEquals("Resumed", server.lastDetails)
            }
        }

    @Test
    fun offlineCancelsGatewayBackoffAndReconnectsOnlyAfterConnectivityReturns() =
        runTest {
            withManager(refreshAvailable = true) { server, _ ->
                DiscordRpcManager.setActivity(activity("Track"), "song")
                runCurrent()
                server.closeRemote(4008)
                runCurrent()
                PlaybackNetworkStateStore.publish(PlaybackNetworkState("offline", connected = false))
                runCurrent()
                DiscordRpcManager.setActivity(activity("Latest offline track"), "new-song")
                val connections = server.connections
                advanceTimeBy(120_000)
                runCurrent()
                assertEquals(connections, server.connections)
                assertEquals(DiscordRpcManager.Status.Disconnected, DiscordRpcManager.connectionStatus.value)
                PlaybackNetworkStateStore.publish(PlaybackNetworkState("online"))
                runCurrent()
                assertEquals(connections + 1, server.connections)
                advanceTimeBy(5_001)
                runCurrent()
                assertEquals("Latest offline track", server.lastDetails)
            }
        }

    @Test
    fun offlineStartupDefersExpiredTokenRefreshAndGatewayUntilOnline() =
        runTest {
            withManager(refreshAvailable = true, online = false) { server, _ ->
                DiscordRpcManager.setActivity(activity("Track"), "song")
                runCurrent()
                advanceTimeBy(120_000)
                runCurrent()
                assertEquals(0, server.connections)
                assertTrue(server.refreshTokens.isEmpty())
                PlaybackNetworkStateStore.publish(PlaybackNetworkState("online"))
                runCurrent()
                assertEquals(listOf("saved-refresh"), server.refreshTokens)
                assertEquals(1, server.connections)
                assertEquals("Track", server.lastDetails)
            }
        }

    @Test
    fun goingOfflineStopsTokenRefreshRetries() =
        runTest {
            withManager { server, _ ->
                assertEquals(listOf("saved-refresh"), server.refreshTokens)
                PlaybackNetworkStateStore.publish(PlaybackNetworkState("offline", connected = false))
                runCurrent()
                server.refreshAvailable = true
                advanceTimeBy(120_000)
                runCurrent()
                assertEquals(listOf("saved-refresh"), server.refreshTokens)
                PlaybackNetworkStateStore.publish(PlaybackNetworkState("online"))
                runCurrent()
                assertEquals(listOf("saved-refresh", "saved-refresh"), server.refreshTokens)
                assertEquals(DiscordRpcManager.Status.Connected, DiscordRpcManager.connectionStatus.value)
            }
        }

    @Test
    fun disablingWhileOfflineDiscardsPresenceBeforeReenabling() =
        runTest {
            withManager(refreshAvailable = true) { server, settings ->
                DiscordRpcManager.setActivity(activity("Stale"), "old")
                runCurrent()
                PlaybackNetworkStateStore.publish(PlaybackNetworkState("offline", connected = false))
                runCurrent()
                settings.enableDiscordIntegration = false
                Snapshot.sendApplyNotifications()
                runCurrent()
                val sent = server.presenceCount
                settings.enableDiscordIntegration = true
                Snapshot.sendApplyNotifications()
                PlaybackNetworkStateStore.publish(PlaybackNetworkState("online"))
                runCurrent()
                assertEquals(DiscordRpcManager.Status.Connected, DiscordRpcManager.connectionStatus.value)
                assertEquals(sent, server.presenceCount)
            }
        }

    @Test
    fun goingOfflineAllowsAnInFlightTokenRotationToBeStoredWithoutConnecting() =
        runTest {
            withManager { server, _ ->
                val response = CompletableDeferred<Unit>()
                server.refreshResponseGate = response
                server.refreshAvailable = true
                advanceTimeBy(1_000)
                runCurrent()
                assertEquals(2, server.refreshTokens.size)
                PlaybackNetworkStateStore.publish(PlaybackNetworkState("offline", connected = false))
                runCurrent()
                response.complete(Unit)
                runCurrent()
                assertEquals("refreshed-token", DiscordTokenStore.retrieve())
                assertEquals("rotated-refresh", DiscordTokenStore.getRefreshToken())
                assertEquals(0, server.connections)
                PlaybackNetworkStateStore.publish(PlaybackNetworkState("online"))
                runCurrent()
                assertEquals(2, server.refreshTokens.size)
                assertEquals(1, server.connections)
                assertEquals(DiscordRpcManager.Status.Connected, DiscordRpcManager.connectionStatus.value)
            }
        }

    private fun activity(details: String) =
        DiscordActivity(
            name = "Metrolist",
            state = "Artist",
            details = details,
            startTimestamp = 0L,
            endTimestamp = null,
            largeImage = null,
            largeText = null,
            smallImage = null,
            smallText = null,
            button1Label = null,
            button1Url = null,
            button2Label = null,
            button2Url = null,
        )

    private suspend fun TestScope.withManager(
        expired: Boolean = true,
        invalidGrant: Boolean = false,
        refreshAvailable: Boolean = false,
        settingsVisible: Boolean = true,
        online: Boolean = true,
        stalledHandshakes: Int = 0,
        test: suspend (TestServer, Settings) -> Unit,
    ) {
        val settings =
            Settings(MapSettings()).apply {
                enableDiscordIntegration = false
                discordAccessToken = "saved-token"
                discordRefreshToken = "saved-refresh"
                discordExpiresAt = MetrolistClock.nowMs() / 1_000L + if (expired) -1L else 7_200L
            }
        Settings.replaceSharedForTest(settings)
        discordSettingsVisible = settingsVisible
        val previousNetwork = PlaybackNetworkStateStore.current
        PlaybackNetworkStateStore.publish(PlaybackNetworkState("test", connected = online))
        DiscordRpcManager.clearLastError()
        val server = TestServer(this, invalidGrant, refreshAvailable)
        server.stalledHandshakes = stalledHandshakes
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = server.client(dispatcher)
        try {
            DiscordRpcManager.init(client, dispatcher)
            runCurrent()
            assertEquals("saved-token", DiscordRpcManager.accessTokenFlow.value)
            settings.enableDiscordIntegration = true
            Snapshot.sendApplyNotifications()
            runCurrent()
            test(server, settings)
        } finally {
            DiscordRpcManager.destroy()
            DiscordRpcManager.logout()
            client.close()
            runCurrent()
            discordSettingsVisible = false
            PlaybackNetworkStateStore.publish(previousNetwork)
            Settings.replaceSharedForTest(null)
        }
    }

    private class TestServer(
        private val scope: TestScope,
        val invalidGrant: Boolean,
        var refreshAvailable: Boolean,
    ) {
        val refreshTokens = mutableListOf<String>()
        val presence = CompletableDeferred<Unit>()
        var presenceCount = 0
        var connections = 0
        var stalledHandshakes = 0
        var handshakes = 0
        var cancelledHandshakes = 0
        var clears = 0
        var heartbeats = 0
        var lastDetails: String? = null
        var profileGate: CompletableDeferred<Unit>? = null
        var refreshResponseGate: CompletableDeferred<Unit>? = null
        private var activeSession: TestWebSocketSession? = null

        suspend fun closeRemote(code: Int) {
            activeSession!!.incoming.send(Frame.Close(CloseReason(code.toShort(), "test close")))
            activeSession!!.incoming.close()
        }

        fun client(dispatcher: CoroutineDispatcher): HttpClient =
            HttpClient(MockEngine) {
                install(WebSockets)
                engine {
                    this.dispatcher = dispatcher
                    addHandler { request ->
                        when (request.url.host) {
                            "discord.com" -> {
                                when (request.url.encodedPath) {
                                    "/api/v10/oauth2/token" -> {
                                        val token = parseQueryString(request.body.toByteArray().decodeToString())["refresh_token"].orEmpty()
                                        refreshTokens += token
                                        refreshResponseGate?.await()
                                        val (body, status) =
                                            when {
                                                invalidGrant -> {
                                                    """{"error":"invalid_grant"}""" to HttpStatusCode.BadRequest
                                                }

                                                !refreshAvailable -> {
                                                    "{}" to HttpStatusCode.ServiceUnavailable
                                                }

                                                else -> {
                                                    """
                                                    {"access_token":"refreshed-token","refresh_token":"rotated-refresh","expires_in":7200}
                                                    """.trimIndent() to HttpStatusCode.OK
                                                }
                                            }
                                        respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                                    }

                                    "/api/v10/users/@me" -> {
                                        withContext(NonCancellable) { profileGate?.await() }
                                        respond("""{"id":"user","username":"listener"}""")
                                    }

                                    else -> {
                                        error("Unexpected Discord request: ${request.url}")
                                    }
                                }
                            }

                            "gateway.discord.gg" -> {
                                respond("").let { response ->
                                    HttpResponseData(
                                        HttpStatusCode.SwitchingProtocols,
                                        response.requestTime,
                                        response.headers,
                                        response.version,
                                        openGateway(),
                                        response.callContext,
                                    )
                                }
                            }

                            else -> {
                                error("Unexpected host: ${request.url.host}")
                            }
                        }
                    }
                }
            }.apply {
                requestPipeline.intercept(HttpRequestPipeline.Before) {
                    if (context.url.host == "gateway.discord.gg") {
                        handshakes++
                        if (stalledHandshakes > 0) {
                            stalledHandshakes--
                            try {
                                awaitCancellation()
                            } finally {
                                cancelledHandshakes++
                            }
                        }
                    }
                }
            }

        fun openGateway(): WebSocketSession {
            val session = TestWebSocketSession(scope.backgroundScope.coroutineContext)
            activeSession = session
            connections++
            session.incoming.trySend(Frame.Text("""{"op":10,"d":{"heartbeat_interval":41250}}"""))
            scope.backgroundScope.launch {
                for (frame in session.outgoing) {
                    if (frame is Frame.Close) {
                        session.incoming.trySend(frame)
                        session.incoming.close()
                        break
                    }
                    if (frame !is Frame.Text) continue
                    val json = Json.parseToJsonElement(frame.readText()).jsonObject
                    when (json["op"]?.jsonPrimitive?.content) {
                        "1" -> {
                            heartbeats++
                            session.incoming.send(Frame.Text("""{"op":11}"""))
                        }

                        "2" -> {
                            if (json["d"]
                                    ?.jsonObject
                                    ?.get("token")
                                    ?.jsonPrimitive
                                    ?.content == "Bearer saved-token"
                            ) {
                                session.incoming.send(Frame.Close(CloseReason(4004, "expired token")))
                                session.incoming.close()
                                break
                            } else {
                                session.incoming.send(Frame.Text("""{"op":0,"t":"READY","s":1,"d":{"session_id":"session"}}"""))
                            }
                        }

                        "3" -> {
                            if (json["d"]
                                    ?.jsonObject
                                    ?.get("activities")
                                    ?.jsonArray
                                    ?.isNotEmpty() == true
                            ) {
                                presenceCount++
                                lastDetails =
                                    json["d"]!!
                                        .jsonObject["activities"]!!
                                        .jsonArray
                                        .first()
                                        .jsonObject["details"]
                                        ?.jsonPrimitive
                                        ?.content
                                presence.complete(Unit)
                            } else {
                                clears++
                            }
                        }
                    }
                }
            }
            return session
        }
    }

    private class TestWebSocketSession(
        context: CoroutineContext,
    ) : WebSocketSession {
        override val coroutineContext = context + Job(context[Job])
        override var masking = false
        override var maxFrameSize = Long.MAX_VALUE
        override val incoming = Channel<Frame>(Channel.UNLIMITED)
        override val outgoing = Channel<Frame>(Channel.UNLIMITED)
        override val extensions = emptyList<WebSocketExtension<*>>()

        override suspend fun flush() = Unit

        @Deprecated("Use cancel() instead.")
        override fun terminate() = cancel()
    }
}
