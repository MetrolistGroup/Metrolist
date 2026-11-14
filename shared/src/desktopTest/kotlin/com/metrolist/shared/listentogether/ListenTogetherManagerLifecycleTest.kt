package com.metrolist.shared.listentogether

import androidx.compose.runtime.snapshotFlow
import com.metrolist.listentogether.codec.MessageCodec
import com.metrolist.shared.listentogether.network.ApproveSuggestionPayload
import com.metrolist.shared.listentogether.network.ErrorPayload
import com.metrolist.shared.listentogether.network.JoinRejectedPayload
import com.metrolist.shared.listentogether.network.JoinRoomPayload
import com.metrolist.shared.listentogether.network.KickUserPayload
import com.metrolist.shared.listentogether.network.MessageTypes
import com.metrolist.shared.listentogether.network.PlaybackActionPayload
import com.metrolist.shared.listentogether.network.PlaybackActions
import com.metrolist.shared.listentogether.network.PortableMessageCodec
import com.metrolist.shared.listentogether.network.ProtocolFeatures
import com.metrolist.shared.listentogether.network.ReconnectedPayload
import com.metrolist.shared.listentogether.network.RoomCreatedPayload
import com.metrolist.shared.listentogether.network.RoomState
import com.metrolist.shared.listentogether.network.ServerCapabilities
import com.metrolist.shared.listentogether.network.SuggestTrackPayload
import com.metrolist.shared.listentogether.network.SuggestionApprovedPayload
import com.metrolist.shared.listentogether.network.SuggestionReceivedPayload
import com.metrolist.shared.listentogether.network.SuggestionSubmittedPayload
import com.metrolist.shared.listentogether.network.TrackInfo
import com.metrolist.shared.listentogether.network.UserInfo
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.http.URLProtocol
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.websocket.WebSockets as ServerWebSockets

class ListenTogetherManagerLifecycleTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pausedGuestsWaitForReadinessChangesAndStopAfterThreeRecoveryAttempts() =
        runTest {
            val client = HttpClient(MockEngine { error("No request expected") })
            val playback = FakePlayback().apply { readiness.value = PlaybackReadiness.READY }
            var resolutions = 0
            val manager =
                ListenTogetherManager(
                    callerScope = backgroundScope,
                    httpClient = client,
                    playback = playback,
                    resolver =
                        ListenTogetherTrackResolver {
                            resolutions++
                            null
                        },
                    monotonicClockMs = { testScheduler.currentTime },
                    wallClockMs = { testScheduler.currentTime },
                    timestampFormatter = { "time" },
                    sessionPersistenceEnabled = false,
                )
            try {
                manager.handleReconnected(
                    ReconnectedPayload(
                        "ROOM123",
                        "guest",
                        RoomState(
                            roomCode = "ROOM123",
                            hostId = "host",
                            users = emptyList(),
                            currentTrack = TrackInfo("track", "Track", "Artist", duration = 180_000L),
                            isPlaying = false,
                            position = 0L,
                            lastUpdate = 0L,
                        ),
                        false,
                    ),
                )
                @Suppress("UNCHECKED_CAST")
                val connection =
                    ListenTogetherManager::class.java
                        .getDeclaredField("_connectionState")
                        .apply { isAccessible = true }
                        .get(manager)
                        as MutableStateFlow<ListenTogetherConnectionState>
                connection.value = ListenTogetherConnectionState.CONNECTED
                advanceTimeBy(2_000)
                runCurrent()
                playback.readinessReads = 0
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(0, playback.readinessReads)
                assertEquals(1, resolutions)
                playback.readiness.value = PlaybackReadiness.FAILED
                advanceTimeBy(10_000)
                runCurrent()
                assertEquals(4, resolutions)
                val failedReads = playback.readinessReads
                advanceTimeBy(60_000)
                runCurrent()
                assertEquals(failedReads, playback.readinessReads)
            } finally {
                manager.close()
                client.close()
            }
        }

    @Test
    fun customServerDetailsPersistWithoutChangingTheConnectionAndAreRemovedWithTheServer() {
        val caller = CoroutineScope(Job() + Dispatchers.Unconfined)
        val client = HttpClient(MockEngine { error("Metadata edits must not connect") })
        val url = "wss://custom.example/ws"
        Settings.shared.listenTogetherCustomServers = url
        val manager = handoffManager(caller, client, false)
        try {
            val selectedUrl = manager.serverUrl.value
            assertFalse(manager.updateCustomServerMetadata(selectedUrl, "Name", "Maintainer"))
            assertFalse(manager.updateCustomServerMetadata(url, " ", "Maintainer"))
            assertTrue(manager.updateCustomServerMetadata(url, " My server ", " My maintainer "))
            assertEquals(selectedUrl, manager.serverUrl.value)
            val edited = manager.servers.value.single { it.url == url }
            assertEquals("My server", edited.name)
            assertEquals("My maintainer", edited.operator)
            val restored = handoffManager(caller, client, false)
            try {
                assertEquals(edited, restored.servers.value.single { it.url == url })
            } finally {
                restored.close()
            }
            manager.removeCustomServer(url)
            assertFalse(manager.servers.value.any { it.url == url })
            assertEquals("{}", Settings.shared.listenTogetherServerMetadata)
        } finally {
            manager.close()
            client.close()
            caller.cancel()
        }
    }

    @Test
    fun backgroundEncodesPreserveCommandOrderAndDiscardAnObsoleteConnection() =
        runBlocking {
            val dispatcher = PausingCodecDispatcher()
            val server = HandoffTestServer()
            val client = loopbackClient(server.port())
            val caller = CoroutineScope(coroutineContext + SupervisorJob())
            val manager = handoffManager(caller, client, false, codecDispatcher = dispatcher)
            try {
                manager.setServerUrl("wss://127.0.0.1:${server.port()}/ws")
                manager.createRoom("Host")
                withTimeout(5_000) { manager.isInRoom.first { it } }
                dispatcher.paused = true
                manager.kickUser("first")
                val first = withTimeout(5_000) { dispatcher.pending.receive() }
                manager.kickUser("second")
                yield()
                assertTrue(dispatcher.pending.tryReceive().isFailure)
                dispatcher.paused = false
                Dispatchers.Default.dispatch(first.first, first.second)
                withTimeout(5_000) { server.kickedUsers.first { it.size == 2 } }
                assertEquals(listOf("first", "second"), server.kickedUsers.value)

                dispatcher.paused = true
                manager.kickUser("obsolete")
                val obsolete = withTimeout(5_000) { dispatcher.pending.receive() }
                manager.setServerUrl("wss://replacement.example/ws")
                dispatcher.paused = false
                val encoded = CompletableDeferred<Unit>()
                Dispatchers.Default.dispatch(obsolete.first) {
                    obsolete.second.run()
                    encoded.complete(Unit)
                }
                encoded.await()
                yield()
                assertEquals(listOf("first", "second"), server.kickedUsers.value)
            } finally {
                dispatcher.paused = false
                manager.close()
                caller.cancel()
                client.close()
                server.stop()
            }
        }

    @Test
    fun decodedCapabilitiesCannotMutateAReplacementConnection() =
        runBlocking {
            val dispatcher = PausingCodecDispatcher().apply { paused = true }
            val client = HttpClient(MockEngine { error("No request expected") })
            val caller = CoroutineScope(coroutineContext + SupervisorJob())
            val manager = handoffManager(caller, client, false, codecDispatcher = dispatcher)
            try {
                val bytes =
                    MessageCodec().encode(
                        MessageTypes.SERVER_CAPABILITIES,
                        ServerCapabilities(supportsProtobuf = false, supportsCompression = false, serverVersion = "obsolete"),
                    )
                val decode = async(start = CoroutineStart.UNDISPATCHED) { manager.handleBinary(bytes) }
                val envelope = withTimeout(5_000) { dispatcher.pending.receive() }
                Dispatchers.Default.dispatch(envelope.first, envelope.second)
                val payload = withTimeout(5_000) { dispatcher.pending.receive() }
                manager.setServerUrl("wss://replacement.example/ws")
                dispatcher.paused = false
                Dispatchers.Default.dispatch(payload.first, payload.second)
                decode.await()
                assertEquals(ListenTogetherConnectionState.DISCONNECTED, manager.connectionState.value)
                assertFalse(manager.isInRoom.value)
                assertNull(manager.roomState.value)
            } finally {
                manager.close()
                caller.cancel()
                client.close()
            }
        }

    private class PausingCodecDispatcher : CoroutineDispatcher() {
        @Volatile
        var paused = false
        val pending = Channel<Pair<CoroutineContext, Runnable>>(Channel.UNLIMITED)

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            if (paused) pending.trySend(context to block).getOrThrow() else Dispatchers.Default.dispatch(context, block)
        }
    }

    @Test
    fun lostSuggestionAcknowledgementRetriesWithoutDuplicatingTheHostApproval() =
        runBlocking {
            var received = 0
            val server = HandoffTestServer(dropFirstSuggestionAck = true, receiveSuggestion = { received++ })
            val client = loopbackClient(server.port())
            val caller = CoroutineScope(coroutineContext + SupervisorJob())
            val guest = handoffManager(caller, client, sessionPersistenceEnabled = false, reconnectDelayMillis = 10L)
            try {
                guest.setServerUrl("wss://127.0.0.1:${server.port()}/ws")
                guest.createRoom("Guest")
                withTimeout(5_000L.milliseconds) { guest.isInRoom.first { it } }
                guest.setGuestRoomStateForTesting()
                val submitted =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        guest.events.first { it == ListenTogetherEvent.Notice(ListenTogetherText.SUGGESTION_SUBMITTED) }
                    }
                guest.suggestTrack(TrackInfo("first", "First", "Artist", duration = 180_000L))
                withTimeout(5_000L.milliseconds) { submitted.await() }
                assertEquals(2, server.suggestionAttempts.get())
                assertEquals(1, received)
                assertEquals(1, server.suggestedTracks.value.size)
                assertTrue(guest.isGuestPlaybackLocked)
            } finally {
                guest.close()
                client.close()
                caller.cancel()
                server.stop()
            }
        }

    @Test
    fun suggestionsRoundTripOnceAndRepeatedTapsDuringReconnectDoNotDropSongs() =
        runBlocking {
            val caller = CoroutineScope(coroutineContext + SupervisorJob())
            val hostClient = HttpClient(MockEngine { error("No host transport expected") })
            val host = handoffManager(caller, hostClient, sessionPersistenceEnabled = false)
            val state = RoomState("ROOM123", "host", emptyList(), isPlaying = false, position = 0, lastUpdate = 0)
            host.handleReconnected(ReconnectedPayload("ROOM123", "host", state, true))
            val reconnectGate = CompletableDeferred<Unit>()
            val server =
                HandoffTestServer(reconnectGate = reconnectGate, receiveSuggestion = { suggestion ->
                    host.handleBinary(PortableMessageCodec().encode(MessageTypes.SUGGESTION_RECEIVED, suggestion))
                })
            val client = loopbackClient(server.port())
            val guest = handoffManager(caller, client, sessionPersistenceEnabled = false, reconnectDelayMillis = 10L)
            try {
                guest.setServerUrl("wss://127.0.0.1:${server.port()}/ws")
                guest.createRoom("Guest")
                withTimeout(5_000L.milliseconds) { guest.isInRoom.first { it } }
                guest.handleReconnected(ReconnectedPayload("ROOM123", "room-user", state, false))
                val first = TrackInfo("first", "First", "Artist", duration = 180_000L)
                repeat(3) { guest.suggestTrack(first) }
                withTimeout(5_000L.milliseconds) {
                    host.pendingSuggestions.first { it.size == 1 }
                    guest.submittedSuggestions.first { it.size == 1 }
                }
                assertEquals(
                    first,
                    host.pendingSuggestions.value
                        .single()
                        .trackInfo,
                )
                repeat(3) { guest.suggestTrack(first) }

                val transport =
                    ListenTogetherManager::class.java
                        .getDeclaredField("socket")
                        .apply { isAccessible = true }
                        .get(guest) as DefaultClientWebSocketSession
                transport.cancel()
                withTimeout(5_000L.milliseconds) { guest.roomAuthenticated.first { !it } }
                val second = first.copy(id = "second", title = "Second")
                repeat(3) { guest.suggestTrack(second) }
                reconnectGate.complete(Unit)
                withTimeout(5_000L.milliseconds) {
                    host.pendingSuggestions.first { it.size == 2 }
                    guest.submittedSuggestions.first { it.size == 2 }
                }
                assertEquals(listOf(first, second), host.pendingSuggestions.value.map { it.trackInfo })
                assertEquals(listOf(first, second), server.suggestedTracks.value)

                guest.handleBinary(MessageCodec().encode(MessageTypes.SUGGESTION_APPROVED, SuggestionApprovedPayload("first", first)))
                guest.suggestTrack(first)
                withTimeout(5_000L.milliseconds) { server.suggestedTracks.first { it.size == 3 } }
                assertEquals(listOf(first, second, first), server.suggestedTracks.value)
            } finally {
                guest.close()
                host.close()
                client.close()
                hostClient.close()
                caller.cancel()
                server.stop()
            }
        }

    @Test
    fun autoApprovalConfirmationSurvivesRemovalFromPendingSuggestions() = verifyAutoApprovalConfirmation(requestIds = false)

    @Test
    fun automaticApprovalsWaitForServerAcknowledgement() = verifyAutoApprovalConfirmation(requestIds = true)

    @Test
    fun rejectedAutomaticApprovalsStayVisibleForRetry() = verifyAutoApprovalConfirmation(requestIds = true, rejectFirst = true)

    private fun verifyAutoApprovalConfirmation(
        requestIds: Boolean,
        rejectFirst: Boolean = false,
    ) = runBlocking<Unit> {
        val codec = MessageCodec()
        val track = TrackInfo("song", "Song", "Artist", null, 180_000L, null)
        val approvalReceived = CompletableDeferred<Unit>()
        val confirmApproval = CompletableDeferred<Unit>()
        var attempts = 0
        val server =
            embeddedServer(ServerCIO, host = "127.0.0.1", port = 0, watchPaths = emptyList()) {
                install(ServerWebSockets)
                routing {
                    webSocket("/ws") {
                        suspend fun message(
                            type: String,
                            payload: Any,
                        ) = send(Frame.Binary(true, codec.encode(type, payload)))
                        for (frame in incoming) {
                            if (frame !is Frame.Binary) continue
                            val envelope = codec.decodeEnvelope(frame.data)
                            val type = envelope.type
                            val bytes = envelope.payload
                            when (type) {
                                MessageTypes.CLIENT_CAPABILITIES -> {
                                    message(
                                        MessageTypes.SERVER_CAPABILITIES,
                                        ServerCapabilities(
                                            true,
                                            false,
                                            "test",
                                            features = if (requestIds) listOf(ProtocolFeatures.REQUEST_ID) else emptyList(),
                                        ),
                                    )
                                }

                                MessageTypes.CREATE_ROOM -> {
                                    message(MessageTypes.ROOM_CREATED, RoomCreatedPayload("ROOM1234", "host", "session"))
                                    message(
                                        MessageTypes.SUGGESTION_RECEIVED,
                                        SuggestionReceivedPayload("suggestion", "guest", "Guest", track),
                                    )
                                }

                                MessageTypes.APPROVE_SUGGESTION -> {
                                    assertEquals(
                                        "suggestion",
                                        (codec.decodePayload(type, bytes) as ApproveSuggestionPayload).suggestionId,
                                    )
                                    if (rejectFirst && ++attempts == 1) {
                                        send(
                                            Frame.Binary(
                                                true,
                                                codec.encode(
                                                    MessageTypes.ERROR,
                                                    ErrorPayload("queue_full", "Queue is full"),
                                                    envelope.requestId,
                                                ),
                                            ),
                                        )
                                        continue
                                    }
                                    approvalReceived.complete(Unit)
                                    confirmApproval.await()
                                    if (requestIds) send(Frame.Binary(true, codec.encode(MessageTypes.ACK, null, envelope.requestId)))
                                    message(MessageTypes.SUGGESTION_APPROVED, SuggestionApprovedPayload("suggestion", track))
                                }
                            }
                        }
                    }
                }
            }.start(wait = false)
        val port =
            server.engine
                .resolvedConnectors()
                .single()
                .port
        val client = loopbackClient(port)
        val caller = CoroutineScope(coroutineContext + SupervisorJob())
        val previous = Settings.shared.listenTogetherAutoApproveSongSuggestions
        Settings.shared.listenTogetherAutoApproveSongSuggestions = true
        val manager = handoffManager(caller, client, sessionPersistenceEnabled = false)
        val notice =
            async(start = CoroutineStart.UNDISPATCHED) {
                manager.events.first { it is ListenTogetherEvent.SuggestionAutoApproved } as ListenTogetherEvent.SuggestionAutoApproved
            }
        val rejection =
            if (rejectFirst) {
                async(start = CoroutineStart.UNDISPATCHED) { manager.events.first { it is ListenTogetherEvent.Failure } }
            } else {
                null
            }
        try {
            manager.setServerUrl("wss://127.0.0.1:$port/ws")
            manager.createRoom("Host")
            withTimeout(5_000L.milliseconds) {
                if (rejection != null) {
                    rejection.await()
                    assertEquals(1, manager.pendingSuggestions.value.size)
                    manager.handleBinary(
                        codec.encode(MessageTypes.SUGGESTION_RECEIVED, SuggestionReceivedPayload("suggestion", "guest", "Guest", track)),
                    )
                }
                approvalReceived.await()
                if (requestIds) {
                    assertEquals(1, manager.pendingSuggestions.value.size)
                } else {
                    manager.pendingSuggestions.first { it.isEmpty() }
                }
                assertFalse(notice.isCompleted)
                confirmApproval.complete(Unit)
                assertEquals(ListenTogetherSuggestion("suggestion", "guest", "Guest", track), notice.await().suggestion)
                manager.pendingSuggestions.first { it.isEmpty() }
            }
        } finally {
            notice.cancel()
            rejection?.cancel()
            manager.close()
            client.close()
            caller.cancel()
            server.stop(0L, 1_000L)
            Settings.shared.listenTogetherAutoApproveSongSuggestions = previous
        }
    }

    @Test
    fun deliveredJoinReconnectsAndCanBeCancelledWithoutWaitingForApproval() =
        runBlocking {
            val codec = MessageCodec()
            val joins = MutableStateFlow(0)
            val server =
                embeddedServer(ServerCIO, host = "127.0.0.1", port = 0, watchPaths = emptyList()) {
                    install(ServerWebSockets)
                    routing {
                        webSocket("/ws") {
                            for (frame in incoming) {
                                if (frame !is Frame.Binary) continue
                                val (type, _) = codec.decode(frame.data)
                                when (type) {
                                    MessageTypes.CLIENT_CAPABILITIES -> {
                                        send(
                                            Frame.Binary(
                                                true,
                                                codec.encode(MessageTypes.SERVER_CAPABILITIES, ServerCapabilities(true, false, "test")),
                                            ),
                                        )
                                    }

                                    MessageTypes.JOIN_ROOM -> {
                                        if ((++joins.value) == 1) {
                                            close()
                                            break
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.start(wait = false)
            val port =
                server.engine
                    .resolvedConnectors()
                    .single()
                    .port
            val client = loopbackClient(port)
            val caller = CoroutineScope(coroutineContext + SupervisorJob())
            val manager = handoffManager(caller, client, sessionPersistenceEnabled = false, reconnectDelayMillis = 10L)
            try {
                manager.setServerUrl("wss://127.0.0.1:$port/ws")
                assertTrue(manager.joinRoom("ROOM1234", "Guest"))
                withTimeout(5_000L.milliseconds) {
                    joins.first { it >= 2 }
                    manager.joinRequestSubmitted.first { it }
                }
                assertTrue(manager.joinRequestPending.value)
                manager.cancelJoinRequest()
                assertFalse(manager.joinRequestPending.value)
                assertFalse(manager.hasPendingRoomRequest)
                assertTrue(manager.joinRoom("OTHER123"))
                withTimeout(5_000L.milliseconds) { joins.first { it >= 3 } }
                assertEquals("OTHER123", manager.pendingRoomCode)
            } finally {
                manager.close()
                client.close()
                caller.cancel()
                server.stop(0L, 1_000L)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun disconnectedObserversDoNotSchedulePolling() =
        runTest {
            val client = HttpClient(MockEngine { error("No request expected") })
            val manager =
                ListenTogetherManager(
                    codecDispatcher = Dispatchers.Unconfined,
                    callerScope = this,
                    httpClient = client,
                    playback = FakePlayback(),
                    resolver = ListenTogetherTrackResolver { null },
                    monotonicClockMs = { currentTime },
                    wallClockMs = { currentTime },
                    timestampFormatter = { "time" },
                    sessionPersistenceEnabled = false,
                )
            try {
                advanceUntilIdle()
                assertEquals(500L, currentTime)
                manager.setGuestRoomStateForTesting()
                advanceUntilIdle()
                assertEquals(500L, currentTime)
                manager.setGuestRoomStateForTesting(inRoom = false)
                advanceUntilIdle()
                assertEquals(500L, currentTime)
            } finally {
                manager.close()
                client.close()
            }
        }

    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun localPreferencesPublishAndPersistForHostsAndGuestsWithABlankUsername() =
        runBlocking {
            val server = HandoffTestServer()
            val client = loopbackClient(server.port())
            val caller = CoroutineScope(coroutineContext + SupervisorJob())
            val manager = handoffManager(caller, client, sessionPersistenceEnabled = false)
            try {
                manager.setServerUrl("wss://127.0.0.1:${server.port()}/ws")
                assertTrue(manager.createRoom("Listener"))
                withTimeout(5_000L.milliseconds) { manager.isInRoom.first { it } }
                for (role in listOf(ListenTogetherRoomRole.HOST, ListenTogetherRoomRole.GUEST)) {
                    if (role == ListenTogetherRoomRole.GUEST) manager.setGuestRoomStateForTesting()
                    manager.setUsername("")
                    assertEquals(role, manager.role.value)
                    assertEquals("", manager.username.value)
                    assertEquals("", Settings.shared.listenTogetherUsername)
                    assertTrue(listenTogetherControls(Settings.shared.listenTogetherEnabled, role, inRoom = true).settingsEnabled)
                    repeat(2) {
                        val joins = !manager.autoApproveJoinRequests.value
                        val suggestions = !manager.autoApproveSongSuggestions.value
                        val volume = !manager.syncHostVolume.value
                        val expected = Triple(joins, suggestions, volume)
                        val observed =
                            async(start = CoroutineStart.UNDISPATCHED) {
                                combine(
                                    manager.autoApproveJoinRequests,
                                    manager.autoApproveSongSuggestions,
                                    manager.syncHostVolume,
                                    ::Triple,
                                ).first { it == expected }
                            }
                        manager.setAutoApproveJoinRequests(joins)
                        manager.setAutoApproveSongSuggestions(suggestions)
                        manager.setSyncHostVolume(volume)
                        assertEquals(expected, withTimeout(5_000L.milliseconds) { observed.await() })
                        assertEquals(joins, Settings.shared.listenTogetherAutoApproveJoinRequests)
                        assertEquals(suggestions, Settings.shared.listenTogetherAutoApproveSongSuggestions)
                        assertEquals(volume, Settings.shared.listenTogetherSyncHostVolume)
                    }
                    manager.blockUser("Blocked listener")
                    assertEquals(setOf("Blocked listener"), manager.blockedUsernames.value)
                    assertTrue(Settings.shared.listenTogetherBlockedUsers.contains("Blocked listener"))
                    manager.unblockUser("Blocked listener")
                    assertTrue(manager.blockedUsernames.value.isEmpty())
                    assertEquals("", Settings.shared.listenTogetherBlockedUsers)
                    manager.setUsername(" Updated listener ")
                    assertEquals("Updated listener", manager.username.value)
                    assertEquals("Updated listener", Settings.shared.listenTogetherUsername)
                    assertEquals(role == ListenTogetherRoomRole.GUEST, manager.isGuestPlaybackLocked)
                }
            } finally {
                manager.close()
                client.close()
                caller.cancel()
                server.stop()
            }
        }

    @Test
    fun `master switch disconnects the room and blocks creation joining and restoration`() =
        runBlocking {
            val server = HandoffTestServer()
            val client = loopbackClient(server.port())
            val caller = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            Settings.shared.listenTogetherServerUrl = "wss://127.0.0.1:${server.port()}/ws"
            val manager = handoffManager(caller, client, sessionPersistenceEnabled = true)
            var restored: ListenTogetherManager? = null
            try {
                assertTrue(Settings.shared.listenTogetherEnabled)
                assertTrue(manager.createRoom("Listener"))
                withTimeout(5_000L.milliseconds) { manager.isInRoom.first { it } }
                val savedSession =
                    withTimeout(5_000L.milliseconds) {
                        snapshotFlow { Settings.shared.listenTogetherDesktopSession }.first { it.isNotBlank() }
                    }

                manager.setEnabled(false)
                assertFalse(manager.isInRoom.value)
                assertFalse(manager.hasActiveOrPendingRoom)
                assertEquals(ListenTogetherConnectionState.DISCONNECTED, manager.connectionState.value)
                assertEquals("", Settings.shared.listenTogetherDesktopSession)
                assertFalse(manager.createRoom("ignored"))
                assertFalse(manager.joinRoom("ROOM1234", "ignored"))
                manager.connect()
                assertEquals(ListenTogetherConnectionState.DISCONNECTED, manager.connectionState.value)
                assertEquals("Listener", manager.username.value)
                manager.handleBinary(
                    MessageCodec().encode(MessageTypes.ROOM_CREATED, RoomCreatedPayload("ROOM123", "host", "late-session")),
                )
                assertFalse(manager.isInRoom.value)
                assertEquals("", Settings.shared.listenTogetherDesktopSession)

                Settings.shared.listenTogetherDesktopSession = savedSession
                restored = handoffManager(caller, client, sessionPersistenceEnabled = true)
                delay(600L)
                assertEquals(1, server.connections.value)
                assertFalse(restored.hasActiveOrPendingRoom)
                restored.close()
                manager.setEnabled(true)
                assertTrue(manager.createRoom("Listener"))
                withTimeout(5_000L.milliseconds) { manager.isInRoom.first { it } }
                assertEquals(2, server.connections.value)
            } finally {
                restored?.close()
                manager.close()
                client.close()
                caller.cancel()
                server.stop()
            }
        }

    @Test
    fun `device follower cannot create join or restore an independent room`() {
        val callerScope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val client = HttpClient(MockEngine { error("A follower must not connect independently") })
        val manager =
            ListenTogetherManager(
                callerScope = callerScope,
                httpClient = client,
                playback = FakePlayback(),
                resolver = ListenTogetherTrackResolver { null },
                monotonicClockMs = { 1L },
                wallClockMs = { 1L },
                timestampFormatter = { "time" },
                sessionPersistenceEnabled = false,
            )
        try {
            manager.setUsername("kept")
            assertTrue(manager.setPlaybackParticipationAllowed(false))
            assertFalse(manager.createRoom("ignored-create"))
            assertFalse(manager.joinRoom("ROOM123", "ignored-join"))
            assertEquals("kept", manager.username.value)
            manager.connect()
            assertFalse(manager.hasActiveOrPendingRoom)
            assertEquals(manager.connectionState.value, ListenTogetherConnectionState.DISCONNECTED)
            assertTrue(manager.setPlaybackParticipationAllowed(true))
            assertTrue(manager.createRoom("listener"))
            assertEquals("listener", manager.username.value)
            assertTrue(manager.hasActiveOrPendingRoom)
            assertFalse(manager.setPlaybackParticipationAllowed(false))
        } finally {
            manager.close()
            client.close()
            callerScope.cancel()
        }
    }

    @Test
    fun `close leaves caller scope and injected client alive`() {
        Settings.shared.listenTogetherDesktopSession = ""
        val callerJob = Job()
        val callerScope = CoroutineScope(callerJob + Dispatchers.Unconfined)
        val client = HttpClient(MockEngine { error("No request expected") })
        val manager =
            ListenTogetherManager(
                callerScope = callerScope,
                httpClient = client,
                playback = FakePlayback(),
                resolver = ListenTogetherTrackResolver { null },
                monotonicClockMs = { 0L },
                wallClockMs = { 0L },
                timestampFormatter = { "time" },
            )

        manager.close()

        assertTrue(callerJob.isActive)
        assertEquals(client.coroutineContext[Job]?.isActive, true)
        client.close()
        callerScope.cancel()
    }

    @Test
    fun `applyHostVolume does not unmute guest when muted`() {
        runBlocking {
            Settings.shared.listenTogetherSyncHostVolume = true
            val callerJob = Job()
            val callerScope = CoroutineScope(callerJob + Dispatchers.Unconfined)
            val client = HttpClient(MockEngine { error("No request expected") })
            val playback = FakePlayback()
            playback.volume.value = 0f
            val manager =
                ListenTogetherManager(
                    codecDispatcher = Dispatchers.Unconfined,
                    callerScope = callerScope,
                    httpClient = client,
                    playback = playback,
                    resolver = ListenTogetherTrackResolver { null },
                    monotonicClockMs = { 0L },
                    wallClockMs = { 0L },
                    timestampFormatter = { "time" },
                )
            manager.setGuestRoomStateForTesting(inRoom = true)

            manager.applyHostVolume(0.8f)

            assertEquals(null, playback.lastRemoteVolume)

            manager.close()
            client.close()
            callerScope.cancel()
        }
    }

    @Test
    fun `applyHostVolume updates volume when guest is unmuted`() {
        runBlocking {
            Settings.shared.listenTogetherSyncHostVolume = true
            val callerJob = Job()
            val callerScope = CoroutineScope(callerJob + Dispatchers.Unconfined)
            val client = HttpClient(MockEngine { error("No request expected") })
            val playback = FakePlayback()
            playback.volume.value = 0.5f
            val manager =
                ListenTogetherManager(
                    codecDispatcher = Dispatchers.Unconfined,
                    callerScope = callerScope,
                    httpClient = client,
                    playback = playback,
                    resolver = ListenTogetherTrackResolver { null },
                    monotonicClockMs = { 0L },
                    wallClockMs = { 0L },
                    timestampFormatter = { "time" },
                )
            manager.setGuestRoomStateForTesting(inRoom = true)

            manager.applyHostVolume(0.8f)

            assertEquals(0.8f, playback.lastRemoteVolume)

            manager.setSyncHostVolume(false)
            manager.applyHostVolume(0.1f)
            assertEquals(0.8f, playback.lastRemoteVolume)

            manager.setSyncHostVolume(true)
            manager.applyHostVolume(0.2f)
            assertEquals(0.2f, playback.lastRemoteVolume)

            manager.close()
            client.close()
            callerScope.cancel()
        }
    }

    @Test
    fun `terminal error cleans up room state and clears persisted session`() {
        runBlocking {
            Settings.shared.listenTogetherDesktopSession =
                "token\n---\nROOM\n---\nuser\n---\ntrue\n---\n123\n---\nwss://metroserverx.meowery.eu/ws"
            val callerJob = Job()
            val callerScope = CoroutineScope(callerJob + Dispatchers.Unconfined)
            val client = HttpClient(MockEngine { error("No request expected") })
            val manager =
                ListenTogetherManager(
                    codecDispatcher = Dispatchers.Unconfined,
                    callerScope = callerScope,
                    httpClient = client,
                    playback = FakePlayback(),
                    resolver = ListenTogetherTrackResolver { null },
                    monotonicClockMs = { 0L },
                    wallClockMs = { 0L },
                    timestampFormatter = { "time" },
                )
            manager.setGuestRoomStateForTesting(inRoom = true)

            val codec = MessageCodec()
            val errorFrame = codec.encode(MessageTypes.ERROR, ErrorPayload("session_expired", "Session expired"))
            manager.handleBinary(errorFrame)

            assertFalse(manager.isInRoom.value)
            assertEquals(ListenTogetherRoomRole.NONE, manager.role.value)
            assertNull(manager.roomState.value)
            assertEquals("", Settings.shared.listenTogetherDesktopSession)

            manager.close()
            client.close()
            callerScope.cancel()
        }
    }

    @Test
    fun `queue actions after terminal cleanup do not change local queue`() {
        runBlocking {
            val callerJob = Job()
            val callerScope = CoroutineScope(callerJob + Dispatchers.Unconfined)
            val client = HttpClient(MockEngine { error("No request expected") })
            val playback = FakePlayback()
            val localSong =
                SongItem(
                    id = "local-song",
                    title = "Local Song",
                    thumbnail = null,
                    artists = emptyList(),
                    album = null,
                )
            playback.queue.value = listOf(localSong)
            val manager =
                ListenTogetherManager(
                    codecDispatcher = Dispatchers.Unconfined,
                    callerScope = callerScope,
                    httpClient = client,
                    playback = playback,
                    resolver = ListenTogetherTrackResolver { null },
                    monotonicClockMs = { 0L },
                    wallClockMs = { 0L },
                    timestampFormatter = { "time" },
                )
            manager.handleReconnected(
                ReconnectedPayload(
                    roomCode = "TEST1234",
                    userId = "guest",
                    state =
                        RoomState(
                            roomCode = "TEST1234",
                            hostId = "host",
                            users = emptyList(),
                            currentTrack = null,
                            isPlaying = false,
                            position = 0L,
                            lastUpdate = 0L,
                        ),
                    isHost = false,
                ),
            )
            val codec = MessageCodec()
            manager.handleBinary(codec.encode(MessageTypes.ERROR, ErrorPayload("session_not_found", "Session expired")))
            manager.handleBinary(
                codec.encode(
                    MessageTypes.SYNC_PLAYBACK,
                    PlaybackActionPayload(action = PlaybackActions.QUEUE_CLEAR, revision = 1L),
                ),
            )

            assertEquals(listOf(localSong), playback.queue.value)

            manager.close()
            client.close()
            callerScope.cancel()
        }
    }

    @Test
    fun `not_in_room error cleans up room state`() {
        runBlocking {
            val callerJob = Job()
            val callerScope = CoroutineScope(callerJob + Dispatchers.Unconfined)
            val client = HttpClient(MockEngine { error("No request expected") })
            val manager =
                ListenTogetherManager(
                    codecDispatcher = Dispatchers.Unconfined,
                    callerScope = callerScope,
                    httpClient = client,
                    playback = FakePlayback(),
                    resolver = ListenTogetherTrackResolver { null },
                    monotonicClockMs = { 0L },
                    wallClockMs = { 0L },
                    timestampFormatter = { "time" },
                )
            manager.setGuestRoomStateForTesting(inRoom = true)

            val codec = MessageCodec()
            val errorFrame = codec.encode(MessageTypes.ERROR, ErrorPayload("not_in_room", "You are not in a room"))
            manager.handleBinary(errorFrame)

            assertFalse(manager.isInRoom.value)
            assertEquals(ListenTogetherRoomRole.NONE, manager.role.value)

            manager.close()
            client.close()
            callerScope.cancel()
        }
    }

    @Test
    fun `reconnecting as host with matching track sets host role and active room`() {
        runBlocking {
            val callerJob = Job()
            val callerScope = CoroutineScope(callerJob + Dispatchers.Unconfined)
            val client = HttpClient(MockEngine { error("No request expected") })
            val playback = FakePlayback()
            val song =
                SongItem(
                    id = "song-123",
                    title = "Test Song",
                    thumbnail = null,
                    artists = emptyList(),
                    album = null,
                )
            playback.currentSong.value = song
            val manager =
                ListenTogetherManager(
                    codecDispatcher = Dispatchers.Unconfined,
                    callerScope = callerScope,
                    httpClient = client,
                    playback = playback,
                    resolver = ListenTogetherTrackResolver { null },
                    monotonicClockMs = { 0L },
                    wallClockMs = { 0L },
                    timestampFormatter = { "time" },
                )

            val trackInfo =
                TrackInfo(
                    id = "song-123",
                    title = "Test Song",
                    artist = "",
                    album = null,
                    duration = 180_000L,
                    thumbnail = null,
                )
            val state =
                RoomState(
                    roomCode = "TEST1234",
                    hostId = "host-1",
                    users = emptyList(),
                    currentTrack = trackInfo,
                    isPlaying = true,
                    position = 50_000L,
                    lastUpdate = 1000L,
                    volume = 1f,
                )
            manager.handleReconnected(
                ReconnectedPayload(
                    roomCode = "TEST1234",
                    userId = "host-1",
                    state = state,
                    isHost = true,
                ),
            )

            assertTrue(manager.isInRoom.value)
            assertEquals(ListenTogetherRoomRole.HOST, manager.role.value)
            assertEquals("TEST1234", manager.roomState.value?.roomCode)
            assertEquals(
                "song-123",
                manager.roomState.value
                    ?.currentTrack
                    ?.id,
            )

            manager.close()
            client.close()
            callerScope.cancel()
        }
    }

    @Test
    fun `host publishes paused seek and same ID occurrence once including after reconnect`() =
        runBlocking {
            val codec = MessageCodec()
            val actions = MutableStateFlow<List<PlaybackActionPayload>>(emptyList())
            val server =
                embeddedServer(ServerCIO, host = "127.0.0.1", port = 0, watchPaths = emptyList()) {
                    install(ServerWebSockets)
                    routing {
                        webSocket("/ws") {
                            for (frame in incoming) {
                                if (frame !is Frame.Binary) continue
                                val (type, bytes) = codec.decode(frame.data)
                                when (type) {
                                    MessageTypes.CLIENT_CAPABILITIES -> {
                                        send(
                                            Frame.Binary(
                                                true,
                                                codec.encode(
                                                    MessageTypes.SERVER_CAPABILITIES,
                                                    ServerCapabilities(
                                                        supportsProtobuf = true,
                                                        supportsCompression = false,
                                                        serverVersion = "test",
                                                    ),
                                                ),
                                            ),
                                        )
                                    }

                                    MessageTypes.CREATE_ROOM -> {
                                        send(
                                            Frame.Binary(
                                                true,
                                                codec.encode(
                                                    MessageTypes.ROOM_CREATED,
                                                    RoomCreatedPayload("ROOM123", "host", "test-session"),
                                                ),
                                            ),
                                        )
                                    }

                                    MessageTypes.PLAYBACK_ACTION -> {
                                        actions.value += codec.decodePayload(type, bytes) as PlaybackActionPayload
                                    }
                                }
                            }
                        }
                    }
                }.start(wait = false)
            val port =
                server.engine
                    .resolvedConnectors()
                    .single()
                    .port
            val client = HttpClient(CIO) { install(WebSockets) }
            client.requestPipeline.intercept(HttpRequestPipeline.Before) {
                check(context.url.host == "127.0.0.1" && context.url.port == port)
                context.url.protocol = URLProtocol.WS
            }
            val song = SongItem("same", "Track", null, emptyList(), null)
            val playback =
                FakePlayback().apply {
                    connected.value = true
                    currentSong.value = song
                    queue.value = listOf(song, song)
                    readiness.value = PlaybackReadiness.READY
                    durationMs.value = 180_000L
                }
            val callerScope = CoroutineScope(coroutineContext + SupervisorJob())
            val previousServerUrl = Settings.shared.listenTogetherServerUrl
            val previousUsername = Settings.shared.listenTogetherUsername
            val manager =
                ListenTogetherManager(
                    codecDispatcher = Dispatchers.Unconfined,
                    callerScope = callerScope,
                    httpClient = client,
                    playback = playback,
                    resolver = ListenTogetherTrackResolver { null },
                    monotonicClockMs = { 0L },
                    wallClockMs = { 1_000L },
                    timestampFormatter = { it.toString() },
                    sessionPersistenceEnabled = false,
                )
            try {
                manager.setServerUrl("wss://127.0.0.1:$port/ws")
                manager.createRoom("listener")
                withTimeout(8_000L.milliseconds) { manager.isInRoom.first { it } }
                manager.publishHostPlaybackChange(trackChanged = true)
                withTimeout(5_000L.milliseconds) { actions.first { messages -> messages.any { it.action == PlaybackActions.PAUSE } } }

                playback.positionMs.value = 45_000L
                manager.sendSeek(45_000L)
                withTimeout(5_000L.milliseconds) {
                    actions.first { messages ->
                        messages.any { it.action == PlaybackActions.SEEK && it.position == 45_000L }
                    }
                }
                assertFalse(playback.playing.value)
                assertTrue(actions.value.none { it.action == PlaybackActions.PLAY })

                val enriched = song.copy(title = "Updated upcoming title")
                playback.queue.value = listOf(song, enriched)
                manager.publishHostPlaybackChange(trackChanged = false)
                withTimeout(5_000L.milliseconds) {
                    actions.first { messages ->
                        messages.any { it.action == PlaybackActions.SYNC_QUEUE && it.queue?.singleOrNull()?.title == enriched.title }
                    }
                }

                playback.queueIndex = 1
                playback.instanceId = 2L
                playback.positionMs.value = 0L
                manager.publishHostPlaybackChange(trackChanged = true)
                withTimeout(5_000L.milliseconds) {
                    actions.first { messages ->
                        messages.count { it.action == PlaybackActions.PAUSE } == 2
                    }
                }
                manager.publishHostPlaybackChange(trackChanged = true)
                manager.publishHostPlaybackChange(trackChanged = false)
                yield()

                val changes = actions.value.filter { it.action == PlaybackActions.CHANGE_TRACK }
                assertEquals(listOf("same", "same"), changes.map { it.trackId })
                assertEquals(listOf(1, 0), changes.map { it.queue.orEmpty().size })
                assertEquals(1, actions.value.count { it.action == PlaybackActions.SEEK })
                assertEquals(2, actions.value.count { it.action == PlaybackActions.PAUSE })
                assertTrue(actions.value.none { it.action == PlaybackActions.PLAY })

                val offlineNext = song.copy(id = "offline-next")
                playback.queue.value = listOf(song, song, offlineNext)
                playback.volume.value = 0.4f
                manager.handleReconnected(
                    ReconnectedPayload(
                        "ROOM123",
                        "host",
                        RoomState(
                            roomCode = "ROOM123",
                            hostId = "host",
                            users = emptyList(),
                            currentTrack = TrackInfo(song.id, song.title, "", duration = 180_000L),
                            isPlaying = false,
                            position = 0L,
                            lastUpdate = 1L,
                        ),
                        true,
                    ),
                )
                manager.publishHostPlaybackChange(trackChanged = false)
                withTimeout(5_000L.milliseconds) {
                    actions.first { messages ->
                        messages.any { it.action == PlaybackActions.SET_VOLUME && it.volume == 0.4f }
                    }
                }
                yield()
                assertEquals(2, actions.value.count { it.action == PlaybackActions.CHANGE_TRACK })
                assertEquals(
                    listOf("offline-next"),
                    actions.value
                        .last { it.action == PlaybackActions.SYNC_QUEUE }
                        .queue
                        .orEmpty()
                        .map { it.id },
                )
                val pausesBeforeStop = actions.value.count { it.action == PlaybackActions.PAUSE }
                playback.currentSong.value = null
                playback.queue.value = emptyList()
                playback.positionMs.value = 0L
                manager.publishHostPlaybackChange(trackChanged = true)
                withTimeout(5_000L.milliseconds) {
                    actions.first { messages ->
                        messages.count { it.action == PlaybackActions.PAUSE } == pausesBeforeStop + 1 &&
                            messages
                                .last { it.action == PlaybackActions.SYNC_QUEUE }
                                .queue
                                .orEmpty()
                                .isEmpty()
                    }
                }
                assertEquals(song.id, actions.value.last { it.action == PlaybackActions.PAUSE }.trackId)
                assertEquals(2, actions.value.count { it.action == PlaybackActions.CHANGE_TRACK })

                assertTrue(playback.currentSong.subscriptionCount.value > 0)
                val next = song.copy(id = "next")
                playback.readiness.value = PlaybackReadiness.BUFFERING
                playback.queue.value = listOf(next)
                playback.queueIndex = 0
                playback.instanceId = 3L
                playback.currentSong.value = next
                yield()
                playback.readiness.value = PlaybackReadiness.READY
                withTimeout(5_000L.milliseconds) {
                    actions.first { messages ->
                        messages.any { it.action == PlaybackActions.CHANGE_TRACK && it.trackId == next.id }
                    }
                }
                assertEquals(3, actions.value.count { it.action == PlaybackActions.CHANGE_TRACK })
            } finally {
                manager.close()
                client.close()
                callerScope.cancel()
                server.stop(0L, 1_000L)
                Settings.shared.listenTogetherServerUrl = previousServerUrl
                Settings.shared.listenTogetherUsername = previousUsername
            }
        }

    @Test
    fun `changing server replaces an active room without sending its session to the new server`() =
        runBlocking {
            val oldServer = HandoffTestServer()
            val newServer = HandoffTestServer()
            val oldUrl = "wss://127.0.0.1:${oldServer.port()}/ws"
            val newUrl = "wss://127.0.0.1:${newServer.port()}/ws"
            val client = loopbackClient(oldServer.port(), newServer.port())
            val caller = CoroutineScope(coroutineContext + SupervisorJob())
            val manager = handoffManager(caller, client, sessionPersistenceEnabled = true)
            try {
                manager.setServerUrl(oldUrl)
                assertEquals(ListenTogetherConnectionState.DISCONNECTED, manager.connectionState.value)
                assertTrue(manager.createRoom("Listener"))
                withTimeout(5_000L.milliseconds) { manager.isInRoom.first { it } }
                assertTrue(manager.hasPersistedSession)

                manager.setServerUrl(" $oldUrl ")
                manager.setServerUrl("http://invalid.example/ws")
                assertEquals(oldUrl, manager.serverUrl.value)
                assertTrue(manager.isInRoom.value)

                manager.setServerUrl(newUrl)
                withTimeout(5_000L.milliseconds) {
                    newServer.connections.first { it == 1 }
                    manager.connectionState.first { it == ListenTogetherConnectionState.CONNECTED }
                    oldServer.sessionDisconnects.first { it == 1 }
                }
                assertFalse(manager.hasActiveOrPendingRoom)
                assertFalse(manager.hasPersistedSession)
                assertNull(manager.roomState.value)
                assertNull(manager.userId.value)
                assertEquals(ListenTogetherRoomRole.NONE, manager.role.value)
                assertEquals("", Settings.shared.listenTogetherDesktopSession)
                assertEquals(newUrl, Settings.shared.listenTogetherServerUrl)
                assertEquals(0, newServer.reconnectRequests.value)
                assertTrue(manager.createRoom("Listener"))
                withTimeout(5_000L.milliseconds) { manager.isInRoom.first { it } }
                assertEquals(0, newServer.reconnectRequests.value)
            } finally {
                manager.close()
                client.close()
                caller.cancel()
                oldServer.stop()
                newServer.stop()
            }
        }

    @Test
    fun `changing server interrupts reconnect backoff and discards the pending join`() =
        runBlocking {
            val oldServer = HandoffTestServer(closeOnJoin = true)
            val newServer = HandoffTestServer()
            val client = loopbackClient(oldServer.port(), newServer.port())
            val caller = CoroutineScope(coroutineContext + SupervisorJob())
            val manager = handoffManager(caller, client, sessionPersistenceEnabled = false, reconnectDelayMillis = 60_000L)
            try {
                manager.setServerUrl("wss://127.0.0.1:${oldServer.port()}/ws")
                assertTrue(manager.joinRoom("ROOM1234", "Listener"))
                withTimeout(5_000L.milliseconds) {
                    manager.connectionState.first { it == ListenTogetherConnectionState.RECONNECTING }
                }
                assertTrue(manager.joinRequestPending.value)

                manager.setServerUrl("wss://127.0.0.1:${newServer.port()}/ws")
                withTimeout(5_000L.milliseconds) {
                    newServer.connections.first { it == 1 }
                    manager.connectionState.first { it == ListenTogetherConnectionState.CONNECTED }
                }
                assertFalse(manager.hasActiveOrPendingRoom)
                assertFalse(manager.joinRequestPending.value)
                assertFalse(manager.joinRequestSubmitted.value)
                assertNull(manager.pendingRoomCode)
                assertEquals(0, newServer.joinRequests.get())
                assertEquals(0, newServer.reconnectRequests.value)
                assertTrue(manager.createRoom("Listener"))
                withTimeout(5_000L.milliseconds) { manager.isInRoom.first { it } }
                assertEquals(1, oldServer.connections.value)
            } finally {
                manager.close()
                client.close()
                caller.cancel()
                oldServer.stop()
                newServer.stop()
            }
        }

    @Test
    fun `changing server cancels capability negotiation on the old transport`() =
        runBlocking {
            val capabilitiesGate = CompletableDeferred<Unit>()
            val oldServer = HandoffTestServer(capabilitiesGate = capabilitiesGate)
            val newServer = HandoffTestServer()
            val client = loopbackClient(oldServer.port(), newServer.port())
            val caller = CoroutineScope(coroutineContext + SupervisorJob())
            val manager = handoffManager(caller, client, sessionPersistenceEnabled = false)
            try {
                manager.setServerUrl("wss://127.0.0.1:${oldServer.port()}/ws")
                assertTrue(manager.createRoom("Listener"))
                withTimeout(5_000L.milliseconds) { oldServer.connections.first { it == 1 } }
                assertEquals(ListenTogetherConnectionState.CONNECTING, manager.connectionState.value)

                manager.setServerUrl("wss://127.0.0.1:${newServer.port()}/ws")
                withTimeout(5_000L.milliseconds) {
                    newServer.connections.first { it == 1 }
                    manager.connectionState.first { it == ListenTogetherConnectionState.CONNECTED }
                }
                capabilitiesGate.complete(Unit)
                withTimeout(5_000L.milliseconds) { oldServer.transportDisconnects.first { it == 1 } }
                assertFalse(manager.hasActiveOrPendingRoom)
                assertEquals(ListenTogetherConnectionState.CONNECTED, manager.connectionState.value)
                assertTrue(manager.createRoom("Listener"))
                withTimeout(5_000L.milliseconds) { manager.isInRoom.first { it } }
                assertEquals(0, newServer.reconnectRequests.value)
            } finally {
                capabilitiesGate.complete(Unit)
                manager.close()
                client.close()
                caller.cancel()
                oldServer.stop()
                newServer.stop()
            }
        }

    @Test
    fun `restored session clears persisted state after fifteen server rejections`() =
        runBlocking {
            val server = HandoffTestServer()
            val client = loopbackClient(server.port())
            val callerScope = CoroutineScope(coroutineContext + SupervisorJob())
            val oldManager = handoffManager(callerScope, client, sessionPersistenceEnabled = true)
            var restoredManager: ListenTogetherManager? = null
            try {
                oldManager.setServerUrl("wss://127.0.0.1:${server.port()}/ws")
                oldManager.createRoom("Listener")
                withTimeout(8_000L.milliseconds) { oldManager.isInRoom.first { it } }
                withTimeout(1_000L.milliseconds) {
                    snapshotFlow { Settings.shared.listenTogetherDesktopSession }.first { it.isNotBlank() }
                }

                val restored =
                    handoffManager(
                        callerScope,
                        client,
                        sessionPersistenceEnabled = true,
                        reconnectDelayMillis = 10L,
                    )
                restoredManager = restored
                withTimeout(10_000L.milliseconds) { server.reconnectRequests.first { it >= 15 } }
                withTimeout(5_000L.milliseconds) {
                    restored.connectionState.first { it == ListenTogetherConnectionState.ERROR }
                }

                assertTrue(oldManager.isInRoom.value)
                assertFalse(restored.isInRoom.value)
                assertEquals(ListenTogetherConnectionState.ERROR, restored.connectionState.value)
                assertEquals("", Settings.shared.listenTogetherDesktopSession)
            } finally {
                oldManager.close()
                restoredManager?.close()
                client.close()
                callerScope.cancel()
                server.stop()
            }
        }

    @Test
    fun `restored session keeps persisted state after transport retry exhaustion`() =
        runBlocking {
            val server = HandoffTestServer()
            val client = loopbackClient(server.port())
            val callerScope = CoroutineScope(coroutineContext + SupervisorJob())
            val oldManager = handoffManager(callerScope, client, sessionPersistenceEnabled = true)
            var restoredManager: ListenTogetherManager? = null
            try {
                oldManager.setServerUrl("wss://127.0.0.1:${server.port()}/ws")
                oldManager.createRoom("Listener")
                withTimeout(8_000L.milliseconds) { oldManager.isInRoom.first { it } }
                val persistedSession = Settings.shared.listenTogetherDesktopSession
                assertTrue(persistedSession.isNotBlank())
                oldManager.close()
                server.stop()

                val restored =
                    handoffManager(
                        callerScope,
                        client,
                        sessionPersistenceEnabled = true,
                        reconnectDelayMillis = 10L,
                    )
                restoredManager = restored
                val finalRetry =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        restored.events.first {
                            it is ListenTogetherEvent.Reconnecting && it.attempt == 15
                        }
                    }
                withTimeout(10_000L.milliseconds) { finalRetry.await() }
                withTimeout(5_000L.milliseconds) {
                    restored.connectionState.first { it == ListenTogetherConnectionState.ERROR }
                }

                assertTrue(restored.hasPersistedSession)
                assertEquals(persistedSession, Settings.shared.listenTogetherDesktopSession)
            } finally {
                oldManager.close()
                restoredManager?.close()
                client.close()
                callerScope.cancel()
                runCatching { server.stop() }
            }
        }

    @Test
    fun `leaving resets reconnect attempts before creating another room`() =
        runBlocking {
            val client = HttpClient(MockEngine { error("Offline") })
            val callerScope = CoroutineScope(coroutineContext + SupervisorJob())
            val manager =
                handoffManager(
                    callerScope,
                    client,
                    sessionPersistenceEnabled = false,
                    reconnectDelayMillis = 60_000L,
                )
            try {
                val firstRetry =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        manager.events.first { it is ListenTogetherEvent.Reconnecting }
                            as ListenTogetherEvent.Reconnecting
                    }
                manager.createRoom("Listener")
                assertEquals(1, withTimeout(5_000L.milliseconds) { firstRetry.await() }.attempt)

                manager.leaveRoomAndWait()

                val newRoomRetry =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        manager.events.first { it is ListenTogetherEvent.Reconnecting }
                            as ListenTogetherEvent.Reconnecting
                    }
                manager.createRoom("Listener")
                assertEquals(1, withTimeout(5_000L.milliseconds) { newRoomRetry.await() }.attempt)
            } finally {
                manager.close()
                client.close()
                callerScope.cancel()
            }
        }

    @Test
    fun `pending joins reject duplicates and clear after rejection or timeout`() =
        runBlocking {
            val codec = MessageCodec()
            val requests = MutableStateFlow(0)
            val firstRequest = CompletableDeferred<JoinRoomPayload>()
            val rejectRequest = CompletableDeferred<Unit>()
            val server =
                embeddedServer(ServerCIO, host = "127.0.0.1", port = 0, watchPaths = emptyList()) {
                    install(ServerWebSockets)
                    routing {
                        webSocket("/ws") {
                            for (frame in incoming) {
                                if (frame !is Frame.Binary) continue
                                val (type, payload) = codec.decode(frame.data)
                                when (type) {
                                    MessageTypes.CLIENT_CAPABILITIES -> {
                                        send(
                                            Frame.Binary(
                                                true,
                                                codec.encode(
                                                    MessageTypes.SERVER_CAPABILITIES,
                                                    ServerCapabilities(true, false, "test"),
                                                ),
                                            ),
                                        )
                                    }

                                    MessageTypes.JOIN_ROOM -> {
                                        if ((++requests.value) == 1) {
                                            firstRequest.complete(codec.decodePayload(type, payload) as JoinRoomPayload)
                                            rejectRequest.await()
                                            send(
                                                Frame.Binary(
                                                    true,
                                                    codec.encode(MessageTypes.JOIN_REJECTED, JoinRejectedPayload("rejected")),
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.start(wait = false)
            val port =
                server.engine
                    .resolvedConnectors()
                    .single()
                    .port
            val client = loopbackClient(port)
            val callerScope = CoroutineScope(coroutineContext + SupervisorJob())
            val manager =
                handoffManager(
                    callerScope,
                    client,
                    sessionPersistenceEnabled = false,
                    roomRequestTimeoutMs = 1_000L,
                )
            try {
                manager.setServerUrl("wss://127.0.0.1:$port/ws")
                val invitedCode = "ROOM1234"
                assertFalse(manager.joinRoom(invitedCode))
                assertFalse(manager.hasActiveOrPendingRoom)
                assertEquals(0, requests.value)
                manager.setUsername("Listener")
                manager.setGuestRoomStateForTesting()
                assertFalse(manager.joinRoom(invitedCode))
                assertTrue(manager.isInRoom.value)
                assertEquals(0, requests.value)
                manager.leaveRoomAndWait()
                assertFalse(manager.isInRoom.value)
                assertTrue(manager.joinRoom(invitedCode))
                assertEquals(JoinRoomPayload("ROOM1234", "Listener"), withTimeout(5_000L.milliseconds) { firstRequest.await() })
                assertTrue(manager.hasPendingRoomRequest)
                assertTrue(manager.roomRequestPending.value)
                assertTrue(manager.joinRequestPending.value)
                assertEquals("ROOM1234", manager.pendingRoomCode)

                manager.joinRoom("OTHER567", "Other")
                yield()
                assertEquals(1, requests.value)
                assertEquals("ROOM1234", manager.pendingRoomCode)

                rejectRequest.complete(Unit)
                withTimeout(5_000L.milliseconds) {
                    manager.joinRequestPending.first { !it }
                }
                assertFalse(manager.hasActiveOrPendingRoom)
                assertFalse(manager.joinRequestPending.value)
                withTimeout(5_000L.milliseconds) { manager.roomRequestPending.first { !it } }

                manager.joinRoom("OTHER567", "Other")
                withTimeout(5_000L.milliseconds) {
                    requests.first { it == 2 }
                }
                withTimeout(5_000L.milliseconds) {
                    manager.joinRequestPending.first { !it }
                }
                assertEquals(ListenTogetherConnectionState.ERROR, manager.connectionState.value)
                assertFalse(manager.joinRequestPending.value)
                withTimeout(5_000L.milliseconds) { manager.roomRequestPending.first { !it } }

                manager.leaveRoomAndWait()
                assertFalse(manager.hasActiveOrPendingRoom)
            } finally {
                manager.close()
                client.close()
                callerScope.cancel()
                server.stop(0L, 1_000L)
            }
        }

    private fun loopbackClient(vararg ports: Int): HttpClient =
        HttpClient(CIO) { install(WebSockets) }.also { client ->
            client.requestPipeline.intercept(HttpRequestPipeline.Before) {
                check(context.url.host == "127.0.0.1" && context.url.port in ports)
                context.url.protocol = URLProtocol.WS
            }
        }

    private fun handoffManager(
        callerScope: CoroutineScope,
        client: HttpClient,
        sessionPersistenceEnabled: Boolean,
        reconnectDelayMillis: Long = 1_000L,
        roomRequestTimeoutMs: Long = 5 * 60_000L,
        codecDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ) = ListenTogetherManager(
        codecDispatcher = codecDispatcher,
        callerScope = callerScope,
        httpClient = client,
        playback = FakePlayback(),
        resolver = ListenTogetherTrackResolver { null },
        monotonicClockMs = { 0L },
        wallClockMs = { 1_000L },
        timestampFormatter = { it.toString() },
        sessionPersistenceEnabled = sessionPersistenceEnabled,
        reconnectDelayMillis = { reconnectDelayMillis },
        roomRequestTimeoutMs = roomRequestTimeoutMs,
    )

    private class HandoffTestServer(
        private val reconnectAsHost: Boolean = false,
        private val reconnectGate: CompletableDeferred<Unit>? = null,
        private val alwaysPending: Boolean = false,
        private val closeOnJoin: Boolean = false,
        private val capabilitiesGate: CompletableDeferred<Unit>? = null,
        private val receiveSuggestion: (suspend (SuggestionReceivedPayload) -> Unit)? = null,
        private val dropFirstSuggestionAck: Boolean = false,
    ) {
        val suggestedTracks = MutableStateFlow<List<TrackInfo>>(emptyList())
        val kickedUsers = MutableStateFlow<List<String>>(emptyList())
        val suggestionAttempts = AtomicInteger(0)
        private val activeSession = AtomicBoolean(false)
        private val resumableSession = AtomicBoolean(false)
        val connections = MutableStateFlow(0)
        val transportDisconnects = MutableStateFlow(0)
        val joinRequests = AtomicInteger(0)
        val reconnectRequests = MutableStateFlow(0)
        val sessionDisconnects = MutableStateFlow(0)
        val leaveRequests = AtomicInteger(0)
        private val server =
            embeddedServer(ServerCIO, host = "127.0.0.1", port = 0, watchPaths = emptyList()) {
                install(ServerWebSockets)
                routing {
                    webSocket("/ws") {
                        handleSession(this)
                    }
                }
            }.start(wait = false)

        suspend fun port(): Int =
            server.engine
                .resolvedConnectors()
                .single()
                .port

        fun stop() {
            server.stop(0L, 1_000L)
        }

        private suspend fun handleSession(session: DefaultWebSocketServerSession) {
            val codec = MessageCodec()
            var ownsSession = false
            try {
                for (frame in session.incoming) {
                    if (frame !is Frame.Binary) continue
                    val envelope = codec.decodeEnvelope(frame.data)
                    val type = envelope.type
                    when (type) {
                        MessageTypes.CLIENT_CAPABILITIES -> {
                            connections.value += 1
                            capabilitiesGate?.await()
                            session.sendProtocol(
                                codec,
                                MessageTypes.SERVER_CAPABILITIES,
                                ServerCapabilities(
                                    supportsProtobuf = true,
                                    supportsCompression = false,
                                    serverVersion = "test",
                                    features = if (receiveSuggestion == null) emptyList() else ProtocolFeatures.supported,
                                ),
                            )
                        }

                        MessageTypes.CREATE_ROOM -> {
                            check(activeSession.compareAndSet(false, true))
                            resumableSession.set(false)
                            ownsSession = true
                            session.sendProtocol(
                                codec,
                                MessageTypes.ROOM_CREATED,
                                RoomCreatedPayload("ROOM123", "room-user", "handoff-session"),
                            )
                        }

                        MessageTypes.JOIN_ROOM -> {
                            joinRequests.incrementAndGet()
                            if (closeOnJoin) {
                                session.close()
                                break
                            }
                        }

                        MessageTypes.RECONNECT -> {
                            reconnectRequests.value += 1
                            if (alwaysPending) continue
                            val accepted =
                                resumableSession.compareAndSet(true, false) &&
                                    activeSession.compareAndSet(false, true)
                            if (!accepted) {
                                session.sendProtocol(
                                    codec,
                                    MessageTypes.ERROR,
                                    ErrorPayload("session_not_found", "Session is still active"),
                                )
                            } else {
                                ownsSession = true
                                reconnectGate?.await()
                                session.sendProtocol(
                                    codec,
                                    MessageTypes.RECONNECTED,
                                    ReconnectedPayload("ROOM123", "room-user", roomState(), reconnectAsHost),
                                )
                            }
                        }

                        MessageTypes.KICK_USER -> {
                            kickedUsers.value += (codec.decodePayload(type, envelope.payload) as KickUserPayload).userId
                        }

                        MessageTypes.LEAVE_ROOM -> {
                            leaveRequests.incrementAndGet()
                        }

                        MessageTypes.SUGGEST_TRACK -> {
                            val track = (codec.decodePayload(type, envelope.payload) as SuggestTrackPayload).trackInfo
                            suggestionAttempts.incrementAndGet()
                            if (dropFirstSuggestionAck && suggestedTracks.value.any { it.id == track.id }) {
                                session.send(
                                    Frame.Binary(
                                        true,
                                        codec.encode(
                                            MessageTypes.ERROR,
                                            ErrorPayload("duplicate_suggestion", "Already pending"),
                                            envelope.requestId,
                                        ),
                                    ),
                                )
                                continue
                            }
                            suggestedTracks.value += track
                            receiveSuggestion?.invoke(SuggestionReceivedPayload(track.id, "room-user", "Guest", track))
                            if (dropFirstSuggestionAck && suggestedTracks.value.size == 1) {
                                session.close()
                                break
                            }
                            session.send(
                                Frame.Binary(
                                    true,
                                    PortableMessageCodec().encode(
                                        MessageTypes.SUGGESTION_SUBMITTED,
                                        SuggestionSubmittedPayload(track.id, track),
                                        envelope.requestId,
                                    ),
                                ),
                            )
                        }
                    }
                }
            } finally {
                transportDisconnects.value += 1
                if (ownsSession) {
                    activeSession.set(false)
                    resumableSession.set(true)
                    sessionDisconnects.value += 1
                }
            }
        }

        private fun roomState(): RoomState {
            val hostId = if (reconnectAsHost) "room-user" else "server-host"
            val users =
                if (reconnectAsHost) {
                    listOf(UserInfo("room-user", "Listener", true))
                } else {
                    listOf(
                        UserInfo("server-host", "Server host", true),
                        UserInfo("room-user", "Listener", false),
                    )
                }
            return RoomState(
                roomCode = "ROOM123",
                hostId = hostId,
                users = users,
                isPlaying = false,
                position = 0L,
                lastUpdate = 1L,
            )
        }

        private suspend fun DefaultWebSocketServerSession.sendProtocol(
            codec: MessageCodec,
            type: String,
            payload: Any,
        ) {
            send(Frame.Binary(true, codec.encode(type, payload)))
        }
    }

    private class FakePlayback : ListenTogetherPlayback {
        override val connected = MutableStateFlow(false)
        override val currentSong = MutableStateFlow<SongItem?>(null)
        override val playing = MutableStateFlow(false)
        override val positionMs = MutableStateFlow(0L)
        override val durationMs = MutableStateFlow(0L)
        override val volume = MutableStateFlow(1f)
        override val queue = MutableStateFlow<List<SongItem>>(emptyList())
        private val readinessState = MutableStateFlow(PlaybackReadiness.IDLE)
        var readinessReads = 0
        override val readiness: MutableStateFlow<PlaybackReadiness>
            get() {
                readinessReads++
                return readinessState
            }
        var lastRemoteVolume: Float? = null

        override fun playbackSpeed() = 1f

        @Volatile var queueIndex = 0

        @Volatile var instanceId = 1L

        override fun currentQueueTitle(): String? = null

        override fun currentQueueIndex(): Int? = queueIndex.takeIf { it in queue.value.indices }

        override fun playbackInstanceId(): Long = instanceId

        override suspend fun playQueueFromRemote(
            songs: List<SongItem>,
            startIndex: Int,
            title: String?,
        ) = Unit

        override fun setQueueTitleFromRemote(title: String?) = Unit

        override suspend fun replaceUpcomingQueueFromRemote(songs: List<SongItem>) {
            queue.value = songs
        }

        override suspend fun addToQueueFromRemote(song: SongItem) = Unit

        override suspend fun skipToNextFromRemote() = Unit

        override suspend fun skipToPreviousFromRemote() = Unit

        override suspend fun setPlayingFromRemote(playing: Boolean) = Unit

        override suspend fun seekToFromRemote(positionMs: Long) = Unit

        override suspend fun setPlaybackSpeedFromRemote(speed: Float) = Unit

        override suspend fun setVolumeFromRemote(volume: Float) {
            lastRemoteVolume = volume
        }
    }
}
