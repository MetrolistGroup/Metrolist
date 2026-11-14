package com.metrolist.shared.listentogether

import com.metrolist.listentogether.codec.MessageCodec
import com.metrolist.shared.listentogether.network.BufferCompletePayload
import com.metrolist.shared.listentogether.network.ErrorPayload
import com.metrolist.shared.listentogether.network.HostChangedPayload
import com.metrolist.shared.listentogether.network.JoinRequestPayload
import com.metrolist.shared.listentogether.network.MessageTypes
import com.metrolist.shared.listentogether.network.PingPayload
import com.metrolist.shared.listentogether.network.PlaybackActionPayload
import com.metrolist.shared.listentogether.network.PlaybackActions
import com.metrolist.shared.listentogether.network.PongPayload
import com.metrolist.shared.listentogether.network.PortableMessageCodec
import com.metrolist.shared.listentogether.network.ProtocolFeatures
import com.metrolist.shared.listentogether.network.ReconnectedPayload
import com.metrolist.shared.listentogether.network.RoomState
import com.metrolist.shared.listentogether.network.ServerClock
import com.metrolist.shared.listentogether.network.ServerRestartingPayload
import com.metrolist.shared.listentogether.network.SuggestionApprovedPayload
import com.metrolist.shared.listentogether.network.SuggestionReceivedPayload
import com.metrolist.shared.listentogether.network.SuggestionRejectedPayload
import com.metrolist.shared.listentogether.network.SuggestionSubmittedPayload
import com.metrolist.shared.listentogether.network.SyncStatePayload
import com.metrolist.shared.listentogether.network.TrackInfo
import com.metrolist.shared.listentogether.network.roomStateHash
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ListenTogetherManagerSyncTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Test
    fun queuedTrackChangesUseThePlayerSnapshotWithoutWaitingForMetadata() =
        runTest(dispatcher) {
            val current = song("current")
            val next = song("next").copy(artists = listOf(ArtistTiny("artist-id", "Artist")))
            val playback =
                FakePlayback().apply {
                    currentSong.value = current
                    queue.value = listOf(current)
                    liveQueueSnapshot = MusicQueueSnapshot(displayedItems = listOf(current, next), currentIndex = 0)
                }
            val test = newManager(playback, ListenTogetherTrackResolver { error("Queued tracks must not fetch metadata") })
            try {
                test.manager.handleReconnected(ReconnectedPayload("ROOM1234", "guest", roomState(current, listOf(track(next))), false))
                test.manager.handleBinary(
                    MessageCodec().encode(
                        MessageTypes.SYNC_PLAYBACK,
                        PlaybackActionPayload(
                            action = PlaybackActions.CHANGE_TRACK,
                            trackInfo = track(next).copy(title = "Host title"),
                            queue = emptyList(),
                            replaceQueue = true,
                            revision = 1L,
                        ),
                    ),
                )
                assertEquals(next.id, playback.currentSong.value?.id)
                assertEquals("Host title", playback.currentSong.value?.title)
                assertEquals(next.artists, playback.currentSong.value?.artists)
                assertEquals(1, playback.playQueueCalls)
                assertEquals(
                    emptyList(),
                    test.manager.roomState.value
                        ?.queue,
                )
                test.manager.handleBinary(
                    MessageCodec().encode(
                        MessageTypes.SYNC_PLAYBACK,
                        PlaybackActionPayload(PlaybackActions.PLAY, next.id, position = 0L, revision = 2L),
                    ),
                )
                assertFalse(playback.playing.value)
                test.manager.handleBinary(MessageCodec().encode(MessageTypes.BUFFER_COMPLETE, BufferCompletePayload(next.id)))
                assertTrue(playback.playing.value)
            } finally {
                test.close()
            }
        }

    @Test
    fun hostAndGuestQueuesConvergeAfterEveryMutationAndTrackChange() =
        runTest(dispatcher) {
            val current = song("current")
            val first = song("first")
            val second = song("second")
            val hostPlayback =
                FakePlayback().apply {
                    currentSong.value = current
                    queue.value = listOf(current, first)
                }
            val guestPlayback =
                FakePlayback().apply {
                    currentSong.value = current
                    queue.value = listOf(current, first)
                }
            val host = newManager(hostPlayback)
            val guest = newManager(guestPlayback, ListenTogetherTrackResolver { it.toSongItem() })
            try {
                host.manager.handleReconnected(hostReconnected(current, listOf(track(first))))
                guest.manager.handleReconnected(ReconnectedPayload("ROOM1234", "guest", roomState(current, listOf(track(first))), false))
                val codec = PortableMessageCodec()
                var revision = 0L

                suspend fun apply(
                    action: String,
                    upcoming: List<SongItem>,
                    activeSong: SongItem = current,
                ) {
                    val payload =
                        PlaybackActionPayload(
                            action,
                            activeSong.id,
                            trackInfo = track(activeSong),
                            queue = upcoming.map(::track),
                            revision = ++revision,
                            replaceQueue = true,
                        )
                    if (action == PlaybackActions.CHANGE_TRACK) {
                        hostPlayback.queue.value = listOf(activeSong) + upcoming
                        hostPlayback.currentSong.value = activeSong
                    }
                    val bytes = codec.encode(MessageTypes.SYNC_PLAYBACK, payload)
                    host.manager.handleBinary(bytes)
                    guest.manager.handleBinary(bytes)
                    yield()
                    assertEquals(listOf(activeSong.id) + upcoming.map { it.id }, guestPlayback.queue.value.map { it.id }, action)
                    assertEquals(hostPlayback.queue.value.map { it.id }, guestPlayback.queue.value.map { it.id }, action)
                }
                apply(PlaybackActions.QUEUE_ADD, listOf(first, second))
                apply(PlaybackActions.SYNC_QUEUE, listOf(second, first))
                apply(PlaybackActions.QUEUE_REMOVE, listOf(first))
                apply(PlaybackActions.QUEUE_ADD, listOf(second, first))
                apply(PlaybackActions.CHANGE_TRACK, listOf(first), second)
                apply(PlaybackActions.SYNC_QUEUE, listOf(first, first), second)
                apply(PlaybackActions.QUEUE_CLEAR, emptyList(), second)
            } finally {
                host.close()
                guest.close()
            }
        }

    @Test
    fun hostKeepsUnpublishedQueueEditsWhenAnApprovedSuggestionArrives() =
        runTest(dispatcher) {
            val current = song("current")
            val first = song("first")
            val added = song("added")
            val suggested = song("suggested")
            val playback =
                FakePlayback().apply {
                    currentSong.value = current
                    queue.value = listOf(current, first)
                }
            val test = newManager(playback)
            try {
                test.manager.handleReconnected(hostReconnected(current, listOf(track(first))))
                playback.queue.value = listOf(current, first, added)
                test.manager.handleBinary(canonicalQueueSnapshot(PlaybackActions.QUEUE_ADD, listOf(track(suggested), track(first)), 1L))
                assertEquals(listOf("current", "suggested", "first", "added"), playback.queue.value.map { it.id })
                assertEquals(listOf(track(suggested), track(first)), managerField(test.manager, "lastQueueSnapshot"))
            } finally {
                test.close()
            }
        }

    @Test
    fun upcomingProjectionReadsThePlayerSnapshotBeforeMirroredQueueFlowsCatchUp() {
        val current = song("same")
        val next = song("next")
        val playback =
            FakePlayback().apply {
                currentSong.value = current
                queue.value = listOf(current, current, next)
                liveQueueSnapshot = MusicQueueSnapshot(displayedItems = listOf(current, current, next), currentIndex = 1)
            }
        val test = newManager(playback)
        try {
            val projection = ListenTogetherManager::class.java.getDeclaredMethod("currentUpcomingTrackInfo").apply { isAccessible = true }
            val first = projection.invoke(test.manager)
            playback.liveQueueSnapshot = MusicQueueSnapshot(displayedItems = listOf(current, current), currentIndex = 1)
            assertEquals(emptyList<TrackInfo>(), projection.invoke(test.manager))
            assertEquals(listOf(next.id), (first as List<*>).map { (it as TrackInfo).id })
        } finally {
            test.close()
        }
    }

    @Test
    fun retryResetsExhaustedAttemptsWhileKeepingGuestPlaybackLocked() =
        runTest(dispatcher) {
            val playback = FakePlayback()
            val test = newManager(playback)
            try {
                test.manager.handleReconnected(ReconnectedPayload("ROOM1234", "guest", roomState(song("room"), emptyList()), false))
                @Suppress("UNCHECKED_CAST")
                val connection = managerField(test.manager, "_connectionState") as MutableStateFlow<ListenTogetherConnectionState>
                connection.value = ListenTogetherConnectionState.ERROR
                ListenTogetherManager::class.java
                    .getDeclaredField("reconnectAttempts")
                    .apply { isAccessible = true }
                    .set(test.manager, 15)
                test.manager.retryConnection()
                assertTrue(test.manager.isGuestPlaybackLocked)
                assertEquals(ListenTogetherConnectionState.RECONNECTING, test.manager.connectionState.value)
                assertEquals(1, managerField(test.manager, "reconnectAttempts"))
            } finally {
                test.close()
            }
        }

    @Test
    fun invalidPongsCannotPoisonSubsequentOutstandingClockSamples() =
        runTest(dispatcher) {
            val test = newManager(FakePlayback())
            try {
                val pingMethod = ListenTogetherManager::class.java.getDeclaredMethod("pingPayload").apply { isAccessible = true }
                val ping = pingMethod.invoke(test.manager) as PingPayload
                val clock = managerField(test.manager, "serverClock") as ServerClock

                suspend fun pong(value: PongPayload) = test.manager.handleBinary(MessageCodec().encode(MessageTypes.PONG, value))
                pong(PongPayload(ping.clientTime, 1_000, 1_000, Long.MAX_VALUE))
                pong(PongPayload(ping.clientTime, 0, 0, ping.sequence))
                assertEquals(0, clock.sampleCount())
                pong(PongPayload(ping.clientTime, 1_000, 1_000, ping.sequence))
                assertEquals(1, clock.sampleCount())
                pong(PongPayload(ping.clientTime, 1_000, 1_000, ping.sequence))
                assertEquals(1, clock.sampleCount())
                test.manager.handleBinary(MessageCodec().encode(MessageTypes.SERVER_RESTARTING, ServerRestartingPayload(0, null)))
                assertEquals(0L, managerField(test.manager, "restartDelayMs"))
            } finally {
                test.close()
            }
        }

    @Test
    fun untaggedHostEchoPreservesNewerEditsButRemoteAdditionStillApplies() =
        runTest(dispatcher) {
            val playback = FakePlayback()
            val current = song("current")
            val first = song("first")
            val second = song("second")
            playback.currentSong.value = current
            playback.queue.value = listOf(current, first)
            val test = newManager(playback)
            try {
                test.manager.handleReconnected(hostReconnected(current, listOf(track(first))))
                @Suppress("UNCHECKED_CAST")
                val sent = managerField(test.manager, "pendingHostQueues") as ArrayDeque<List<TrackInfo>>
                sent.addLast(listOf(track(first)))
                playback.queue.value = listOf(current, first, second)
                test.manager.handleBinary(canonicalQueueSnapshot(PlaybackActions.SYNC_QUEUE, listOf(track(first)), 0L))
                assertEquals(listOf(current, first, second), playback.queue.value)
                test.manager.handleBinary(
                    MessageCodec().encode(
                        MessageTypes.SYNC_PLAYBACK,
                        PlaybackActionPayload(PlaybackActions.QUEUE_ADD, trackInfo = track(song("remote"))),
                    ),
                )
                assertEquals(listOf("current", "first", "second", "remote"), playback.queue.value.map { it.id })
            } finally {
                test.close()
            }
        }

    @Test
    fun revisionlessTrackReplacementClearsTheUpcomingQueueAndKeepsItsTitle() =
        runTest(dispatcher) {
            for (sameTrack in listOf(false, true)) {
                val playback = FakePlayback()
                val current = song("current")
                val replacement = if (sameTrack) current else song("replacement")
                playback.currentSong.value = current
                playback.queue.value = listOf(current, song("old-next"))
                val test = newManager(playback, ListenTogetherTrackResolver { replacement })
                try {
                    test.manager.handleReconnected(
                        ReconnectedPayload("ROOM1234", "guest", roomState(current, listOf(track(song("old-next")))), false),
                    )
                    test.manager.handleBinary(
                        MessageCodec().encode(
                            MessageTypes.SYNC_PLAYBACK,
                            PlaybackActionPayload(
                                PlaybackActions.CHANGE_TRACK,
                                replacement.id,
                                trackInfo = track(replacement),
                                queue = emptyList(),
                                queueTitle = "Station",
                                replaceQueue = true,
                            ),
                        ),
                    )
                    yield()
                    assertEquals(
                        emptyList(),
                        test.manager.roomState.value
                            ?.queue,
                    )
                    assertEquals(listOf(replacement.id), playback.queue.value.map { it.id })
                    assertEquals("Station", playback.queueTitle)
                } finally {
                    test.close()
                }
            }
        }

    @Test
    fun failedResolutionExposesTrackAndHashesCommittedLocalPlaybackUntilRetry() =
        runTest(dispatcher) {
            val playback = FakePlayback()
            val local = song("local")
            val room = song("room")
            playback.currentSong.value = local
            playback.queue.value = listOf(local, song("local-next"))
            var resolve = false
            val test = newManager(playback, ListenTogetherTrackResolver { room.takeIf { resolve } })
            try {
                test.manager.handleReconnected(
                    ReconnectedPayload("ROOM1234", "guest", roomState(room, emptyList()).copy(queueTitle = "Mix"), false),
                )
                yield()
                assertEquals(
                    room.id,
                    test.manager.failedRoomTrack.value
                        ?.id,
                )
                ListenTogetherManager::class.java
                    .getDeclaredField("serverFeatures")
                    .apply {
                        isAccessible = true
                    }.set(test.manager, setOf(ProtocolFeatures.DESYNC_CANARY))
                val ping =
                    ListenTogetherManager::class.java
                        .getDeclaredMethod("pingPayload")
                        .apply {
                            isAccessible = true
                        }.invoke(test.manager) as PingPayload
                assertEquals(roomStateHash(local.id, listOf(track(song("local-next")))), ping.stateHash)
                resolve = true
                test.manager.retryRoomTrack()
                yield()
                assertNull(test.manager.failedRoomTrack.value)
                assertEquals(room, playback.currentSong.value)
                assertEquals("Mix", playback.queueTitle)
            } finally {
                test.close()
            }
        }

    @Test
    fun suggestionOutcomesAndHostPromotionEmitNotices() =
        runTest(dispatcher) {
            val playback = FakePlayback()
            val current = song("current")
            playback.currentSong.value = current
            val test = newManager(playback)
            val notices = mutableListOf<ListenTogetherText>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    test.manager.events.collect { if (it is ListenTogetherEvent.Notice) notices += it.key }
                }
            try {
                test.manager.handleReconnected(ReconnectedPayload("ROOM1234", "guest", roomState(current, emptyList()), false))

                suspend fun message(
                    type: String,
                    payload: Any,
                ) = test.manager.handleBinary(MessageCodec().encode(type, payload))
                message(MessageTypes.SUGGESTION_SUBMITTED, SuggestionSubmittedPayload("one", track(current)))
                assertEquals(setOf("one"), test.manager.submittedSuggestions.value.keys)
                message(MessageTypes.SUGGESTION_REJECTED, SuggestionRejectedPayload("one", null))
                assertTrue(
                    test.manager.submittedSuggestions.value
                        .isEmpty(),
                )
                message(MessageTypes.HOST_CHANGED, HostChangedPayload("guest", "Guest"))
                yield()
                assertTrue(ListenTogetherText.SUGGESTION_SUBMITTED in notices)
                assertTrue(ListenTogetherText.SUGGESTION_REJECTED in notices)
                assertTrue(ListenTogetherText.BECAME_HOST in notices)
                message(MessageTypes.JOIN_REQUEST, JoinRequestPayload("new", "New"))
                test.manager.approveJoin("new")
                test.manager.rejectJoin("new")
                assertEquals(
                    listOf("new"),
                    test.manager.pendingJoinRequests.value
                        .map { it.userId },
                )
            } finally {
                collector.cancel()
                test.close()
            }
        }

    private fun managerField(
        manager: ListenTogetherManager,
        name: String,
    ): Any? =
        ListenTogetherManager::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(manager)

    @Test
    fun metadataOnlyQueueUpdatesPreserveLocalFieldsAndDuplicateOccurrences() =
        runTest(dispatcher) {
            val playback = FakePlayback()
            val current = song("current")
            val first = song("next", "First source").copy(artists = listOf(ArtistTiny("artist-id", "Artist")))
            val second = first.copy(queueSourceTitle = "Second source")
            playback.currentSong.value = current
            playback.queue.value = listOf(current, first, second)
            val testManager = newManager(playback)
            try {
                testManager.manager.handleReconnected(
                    ReconnectedPayload("ROOM1234", "guest", roomState(current, listOf(track(first), track(second))), false),
                )
                val metadata =
                    track(first).copy(
                        title = "Updated",
                        duration = 240_000L,
                        thumbnail = "https://i.ytimg.com/vi/next/default.jpg",
                    )
                testManager.manager.handleBinary(canonicalQueueSnapshot(PlaybackActions.SYNC_QUEUE, listOf(metadata, metadata), 1L))
                val upcoming = playback.queue.value.drop(1)
                assertEquals(listOf("Updated", "Updated"), upcoming.map { it.title })
                assertEquals(listOf("First source", "Second source"), upcoming.map { it.queueSourceTitle })
                assertTrue(upcoming.all { it.artists == first.artists && it.duration == 240L && it.thumbnail == metadata.thumbnail })
            } finally {
                testManager.close()
            }
        }

    @Test
    fun `upcoming queue projection is reused until queue or current track changes`() {
        val playback = FakePlayback()
        val first = song("first")
        val second = song("second")
        playback.queue.value = listOf(first, second)
        playback.currentSong.value = first
        val testManager = newManager(playback)
        try {
            val method = ListenTogetherManager::class.java.getDeclaredMethod("currentUpcomingTrackInfo").apply { isAccessible = true }
            val initial = method.invoke(testManager.manager)
            playback.positionMs.value = 1_000L
            assertSame(initial, method.invoke(testManager.manager))
            val updated = second.copy(title = "Updated metadata")
            playback.queue.value = listOf(first, updated)
            val changed = method.invoke(testManager.manager) as List<*>
            assertEquals("Updated metadata", (changed.single() as TrackInfo).title)
            playback.currentSong.value = updated
            assertEquals(emptyList<TrackInfo>(), method.invoke(testManager.manager))
        } finally {
            testManager.close()
        }
    }

    @Test
    fun `an empty guest room pauses local playback without discarding its queue`() =
        runTest(dispatcher) {
            val playback = FakePlayback()
            val current = song("local")
            playback.currentSong.value = current
            playback.queue.value = listOf(current)
            playback.playing.value = true
            val testManager = newManager(playback)
            val reconnected =
                ReconnectedPayload(
                    roomCode = "ROOM1234",
                    userId = "guest",
                    state = roomState(current, emptyList()).copy(currentTrack = null, isPlaying = false, position = 0L),
                    isHost = false,
                )
            try {
                testManager.manager.handleReconnected(reconnected)
                assertTrue(testManager.manager.isGuestPlaybackLocked)
                assertFalse(playback.playing.value)
                assertEquals(current, playback.currentSong.value)
                assertEquals(listOf(current), playback.queue.value)
                assertEquals(0, playback.playQueueCalls)

                testManager.manager.handleReconnected(reconnected.copy(state = roomState(current, emptyList())))
                assertTrue(playback.playing.value)
                assertEquals(10_000L, playback.lastRemotePosition)
            } finally {
                testManager.close()
            }
        }

    @Test
    fun `host commands preserve local playback during and after failed room resolution`() =
        runTest(dispatcher) {
            val playback = FakePlayback()
            val local = song("local")
            val localQueue = listOf(local, song("local-next"))
            playback.currentSong.value = local
            playback.queue.value = localQueue
            playback.positionMs.value = 4_000L
            playback.playing.value = true
            val resolution = CompletableDeferred<SongItem?>()
            val testManager = newManager(playback, ListenTogetherTrackResolver { resolution.await() })
            try {
                testManager.manager.handleReconnected(
                    ReconnectedPayload("ROOM1234", "guest", roomState(song("room"), emptyList()), false),
                )
                assertFalse(playback.playing.value)

                suspend fun sendHostCommands() {
                    for (action in listOf(
                        PlaybackActions.PLAY,
                        PlaybackActions.PAUSE,
                        PlaybackActions.SEEK,
                        PlaybackActions.SKIP_NEXT,
                        PlaybackActions.SKIP_PREV,
                        PlaybackActions.SYNC_QUEUE,
                    )) {
                        testManager.manager.handleBinary(
                            MessageCodec().encode(
                                MessageTypes.SYNC_PLAYBACK,
                                PlaybackActionPayload(
                                    action,
                                    trackId = "room",
                                    position = 50_000L,
                                    queue = listOf(track(song("room-next"))),
                                ),
                            ),
                        )
                    }
                    assertEquals(local, playback.currentSong.value)
                    assertEquals(localQueue, playback.queue.value)
                    assertEquals(4_000L, playback.positionMs.value)
                    assertFalse(playback.playing.value)
                    assertEquals(0, playback.skipCalls)
                }

                sendHostCommands()
                resolution.complete(null)
                yield()
                sendHostCommands()
                assertEquals(0, playback.playQueueCalls)
                assertTrue(playback.playingChanges.none { it })
            } finally {
                testManager.close()
            }
        }

    @Test
    fun `failed room playback stays paused until a sync reloads the track`() =
        runTest(dispatcher) {
            val playback = FakePlayback()
            val room = song("room")
            playback.readiness.value = PlaybackReadiness.FAILED
            val testManager = newManager(playback, ListenTogetherTrackResolver { room })
            try {
                testManager.manager.handleReconnected(
                    ReconnectedPayload("ROOM1234", "guest", roomState(room, emptyList()), false),
                )
                assertEquals(1, playback.playQueueCalls)
                testManager.manager.handleBinary(
                    MessageCodec().encode(
                        MessageTypes.SYNC_PLAYBACK,
                        PlaybackActionPayload(PlaybackActions.PLAY, room.id, 50_000L),
                    ),
                )
                assertFalse(playback.playing.value)
                assertNull(playback.lastRemotePosition)

                testManager.manager.handleBinary(
                    MessageCodec().encode(
                        MessageTypes.SYNC_PLAYBACK,
                        PlaybackActionPayload(PlaybackActions.CHANGE_TRACK, room.id, trackInfo = track(room)),
                    ),
                )
                assertEquals(2, playback.playQueueCalls)
                assertFalse(playback.playing.value)

                testManager.manager.handleBinary(
                    MessageCodec().encode(
                        MessageTypes.SYNC_STATE,
                        SyncStatePayload(track(room), true, 20_000L, 1_000L),
                    ),
                )
                assertEquals(3, playback.playQueueCalls)
                assertFalse(playback.playing.value)

                playback.readiness.value = PlaybackReadiness.READY
                testManager.manager.handleBinary(
                    MessageCodec().encode(
                        MessageTypes.SYNC_STATE,
                        SyncStatePayload(track(room), true, 20_000L, 1_000L),
                    ),
                )
                assertTrue(playback.playing.value)
                assertEquals(20_000L, playback.lastRemotePosition)
            } finally {
                testManager.close()
            }
        }

    @Test
    fun `empty sync pauses playback and cancels an in-flight room load`() =
        runTest(dispatcher) {
            val playback = FakePlayback()
            val current = song("current")
            playback.currentSong.value = current
            playback.queue.value = listOf(current)
            val resolution = CompletableDeferred<SongItem?>()
            val testManager = newManager(playback, ListenTogetherTrackResolver { resolution.await() })
            val codec = MessageCodec()
            val emptyState = SyncStatePayload(null, false, 0L, 2_000L)
            try {
                testManager.manager.handleReconnected(
                    ReconnectedPayload("ROOM1234", "guest", roomState(current, emptyList()), false),
                )
                assertTrue(playback.playing.value)
                testManager.manager.handleBinary(codec.encode(MessageTypes.SYNC_STATE, emptyState))
                assertFalse(playback.playing.value)

                testManager.manager.handleBinary(
                    codec.encode(MessageTypes.SYNC_STATE, SyncStatePayload(track(song("new")), true, 0L, 3_000L)),
                )
                testManager.manager.handleBinary(codec.encode(MessageTypes.SYNC_STATE, emptyState.copy(lastUpdate = 4_000L)))
                resolution.complete(song("new"))
                yield()
                assertFalse(playback.playing.value)
                assertEquals(current, playback.currentSong.value)
                assertEquals(listOf(current), playback.queue.value)
                assertEquals(0, playback.playQueueCalls)
            } finally {
                testManager.close()
            }
        }

    @Test
    fun `host queue import starts playback and leave clears pending imports`() =
        runTest(dispatcher) {
            val playback = FakePlayback()
            val current = song("current")
            playback.currentSong.value = current
            val testManager = newManager(playback)
            try {
                testManager.manager.handleReconnected(ReconnectedPayload("ROOM1234", "host", roomState(current, emptyList()), true))
                playback.playing.value = false
                val imported = listOf(song("first"), song("second"))
                testManager.manager.prepareQueueImport("Imported", imported)
                testManager.manager.replaceWithPendingQueueImport()
                withTimeout(1_000.milliseconds) {
                    while (testManager.manager.pendingQueueImport.value != null) yield()
                }
                assertEquals(imported, playback.queue.value)
                assertTrue(playback.playing.value)
                playback.queueLoadGate = CompletableDeferred()
                playback.playing.value = false
                testManager.manager.prepareQueueImport("Pending", listOf(song("cancelled")))
                testManager.manager.replaceWithPendingQueueImport()
                testManager.manager.leaveRoomAndWait()
                playback.queueLoadGate?.complete(Unit)
                yield()
                assertNull(testManager.manager.pendingQueueImport.value)
                assertEquals(imported, playback.queue.value)
                assertFalse(playback.playing.value)
            } finally {
                testManager.close()
            }
        }

    @Test
    fun `same-track change does not restart an already loaded guest`() =
        runTest(dispatcher) {
            val playback = FakePlayback()
            val current = song("current")
            val next = song("next", "Playlist")
            playback.currentSong.value = current
            playback.queue.value = listOf(current, next)
            var resolverCalls = 0
            val testManager = newManager(playback) { resolverCalls++ }
            val state = roomState(current, listOf(track(next)))

            testManager.manager.handleReconnected(
                ReconnectedPayload(
                    roomCode = "ROOM1234",
                    userId = "guest",
                    state = state,
                    isHost = false,
                ),
            )
            testManager.manager.handleBinary(
                MessageCodec().encode(
                    MessageTypes.SYNC_PLAYBACK,
                    PlaybackActionPayload(
                        action = PlaybackActions.CHANGE_TRACK,
                        trackInfo = track(current),
                        queue = listOf(track(next)),
                        position = 25_000L,
                        revision = 1L,
                    ),
                ),
            )

            assertEquals(0, resolverCalls)
            assertEquals(0, playback.playQueueCalls)
            assertEquals(25_000L, playback.lastRemotePosition)
            assertEquals(false, playback.playing.value)
            assertEquals(listOf("current", "next"), playback.queue.value.map { it.id })
            testManager.close()
        }

    @Test
    fun `host transfer to another participant preserves guest loading`() =
        runTest(dispatcher) {
            val playback = FakePlayback()
            val old = song("old")
            playback.currentSong.value = old
            playback.queue.value = listOf(old)
            playback.readiness.value = PlaybackReadiness.BUFFERING
            val resolverStarted = CompletableDeferred<Unit>()
            val resolverRelease = CompletableDeferred<SongItem>()
            val testManager =
                newManager(
                    playback,
                    resolver =
                        ListenTogetherTrackResolver {
                            resolverStarted.complete(Unit)
                            resolverRelease.await()
                        },
                )
            val target = song("target")
            testManager.manager.handleReconnected(
                ReconnectedPayload(
                    roomCode = "ROOM1234",
                    userId = "guest",
                    state = roomState(target, emptyList()),
                    isHost = false,
                ),
            )
            resolverStarted.await()

            testManager.manager.handleBinary(
                MessageCodec().encode(
                    MessageTypes.HOST_CHANGED,
                    HostChangedPayload("new-host", "New Host"),
                ),
            )

            assertEquals(ListenTogetherRoomRole.GUEST, testManager.manager.role.value)
            assertEquals(0, playback.playQueueCalls)
            resolverRelease.complete(target)
            playback.readiness.value = PlaybackReadiness.READY
            withTimeout(1_000L.milliseconds) {
                while (playback.currentSong.value?.id != target.id || playback.lastRemotePosition == null) {
                    delay(
                        10L.milliseconds,
                    )
                }
            }

            assertEquals(target.id, playback.currentSong.value?.id)
            assertEquals(10_000L, playback.lastRemotePosition)
            assertTrue(playback.playing.value)
            testManager.close()
        }

    @Test
    fun `host transfer to this guest cancels in-flight loading`() =
        runTest(dispatcher) {
            val playback = FakePlayback()
            val old = song("old")
            playback.currentSong.value = old
            playback.queue.value = listOf(old)
            val resolverStarted = CompletableDeferred<Unit>()
            val resolverRelease = CompletableDeferred<SongItem>()
            var resolverCalls = 0
            val testManager =
                newManager(
                    playback,
                    resolver =
                        ListenTogetherTrackResolver {
                            resolverCalls++
                            if (resolverCalls == 1) {
                                resolverStarted.complete(Unit)
                                resolverRelease.await()
                            } else {
                                null
                            }
                        },
                )
            val target = song("target")
            testManager.manager.handleReconnected(
                ReconnectedPayload(
                    roomCode = "ROOM1234",
                    userId = "guest",
                    state = roomState(target, emptyList()),
                    isHost = false,
                ),
            )
            resolverStarted.await()

            testManager.manager.handleBinary(
                MessageCodec().encode(
                    MessageTypes.HOST_CHANGED,
                    HostChangedPayload("guest", "Guest"),
                ),
            )
            resolverRelease.complete(target)
            runCurrent()

            assertEquals(ListenTogetherRoomRole.HOST, testManager.manager.role.value)
            assertEquals(0, playback.playQueueCalls)
            assertEquals(old.id, playback.currentSong.value?.id)
            testManager.close()
        }

    @Test
    fun `same-track change waits for readiness before acknowledging`() =
        runTest(dispatcher) {
            val playback = FakePlayback()
            val current = song("current")
            playback.currentSong.value = current
            playback.readiness.value = PlaybackReadiness.BUFFERING
            val testManager = newManager(playback)
            testManager.manager.setGuestRoomStateForTesting()

            testManager.manager.handleBinary(
                MessageCodec().encode(
                    MessageTypes.SYNC_PLAYBACK,
                    PlaybackActionPayload(
                        action = PlaybackActions.CHANGE_TRACK,
                        trackInfo = track(current),
                        position = 25_000L,
                    ),
                ),
            )

            assertEquals(null, playback.lastRemotePosition)
            playback.readiness.value = PlaybackReadiness.READY
            withTimeout(1_000L.milliseconds) {
                while (playback.lastRemotePosition == null) delay(10L.milliseconds)
            }

            assertEquals(25_000L, playback.lastRemotePosition)
            assertEquals(false, playback.playing.value)
            testManager.close()
        }

    @Test
    fun `same-track barrier keeps the latest play intent before readiness`() =
        runTest(dispatcher) {
            val playback = FakePlayback()
            val current = song("current")
            val next = song("next")
            playback.currentSong.value = current
            playback.queue.value = listOf(current)
            playback.readiness.value = PlaybackReadiness.BUFFERING
            val testManager = newManager(playback)
            testManager.manager.handleReconnected(
                ReconnectedPayload(
                    roomCode = "ROOM1234",
                    userId = "guest",
                    state = roomState(current, emptyList()).copy(isPlaying = false, position = 0L),
                    isHost = false,
                ),
            )
            playback.lastRemotePosition = null
            playback.playingChanges.clear()
            val codec = MessageCodec()

            testManager.manager.handleBinary(
                codec.encode(
                    MessageTypes.SYNC_PLAYBACK,
                    PlaybackActionPayload(
                        action = PlaybackActions.CHANGE_TRACK,
                        trackId = current.id,
                        trackInfo = track(current),
                        position = 10_000L,
                    ),
                ),
            )
            testManager.manager.handleBinary(
                codec.encode(
                    MessageTypes.SYNC_PLAYBACK,
                    PlaybackActionPayload(
                        action = PlaybackActions.CHANGE_TRACK,
                        trackId = current.id,
                        trackInfo = track(current),
                        position = 15_000L,
                    ),
                ),
            )
            testManager.manager.handleBinary(
                codec.encode(
                    MessageTypes.SYNC_PLAYBACK,
                    PlaybackActionPayload(
                        action = PlaybackActions.SEEK,
                        trackId = current.id,
                        position = 18_000L,
                    ),
                ),
            )
            testManager.manager.handleBinary(
                codec.encode(
                    MessageTypes.SYNC_PLAYBACK,
                    PlaybackActionPayload(
                        action = PlaybackActions.PLAY,
                        trackId = current.id,
                        position = 20_000L,
                    ),
                ),
            )
            testManager.manager.handleBinary(
                canonicalQueueSnapshot(
                    action = PlaybackActions.SYNC_QUEUE,
                    queue = listOf(track(next)),
                    revision = 1L,
                ),
            )

            assertFalse(playback.playing.value)
            assertNull(playback.lastRemotePosition)
            assertEquals(listOf("current", "next"), playback.queue.value.map { it.id })
            playback.readiness.value = PlaybackReadiness.READY
            withTimeout(1_000L.milliseconds) {
                while (playback.lastRemotePosition != 20_000L) delay(10L.milliseconds)
            }
            assertFalse(playback.playing.value)

            testManager.manager.handleBinary(codec.encode(MessageTypes.BUFFER_COMPLETE, BufferCompletePayload(current.id)))

            assertTrue(playback.playing.value)
            assertEquals(listOf(false, true), playback.playingChanges)
            testManager.close()
        }

    @Test
    fun `same-track barrier keeps a pause intent before readiness`() =
        runTest(dispatcher) {
            val playback = FakePlayback()
            val current = song("current")
            playback.currentSong.value = current
            playback.readiness.value = PlaybackReadiness.BUFFERING
            val testManager = newManager(playback)
            testManager.manager.setGuestRoomStateForTesting()
            val codec = MessageCodec()

            testManager.manager.handleBinary(
                codec.encode(
                    MessageTypes.SYNC_PLAYBACK,
                    PlaybackActionPayload(
                        action = PlaybackActions.CHANGE_TRACK,
                        trackId = current.id,
                        trackInfo = track(current),
                        position = 10_000L,
                    ),
                ),
            )
            testManager.manager.handleBinary(
                codec.encode(
                    MessageTypes.SYNC_PLAYBACK,
                    PlaybackActionPayload(
                        action = PlaybackActions.PAUSE,
                        trackId = current.id,
                        position = 30_000L,
                    ),
                ),
            )

            assertFalse(playback.playing.value)
            assertNull(playback.lastRemotePosition)
            playback.readiness.value = PlaybackReadiness.READY
            withTimeout(1_000L.milliseconds) {
                while (playback.lastRemotePosition != 30_000L) delay(10L.milliseconds)
            }
            testManager.manager.handleBinary(codec.encode(MessageTypes.BUFFER_COMPLETE, BufferCompletePayload(current.id)))

            assertFalse(playback.playing.value)
            assertEquals(listOf(false, false), playback.playingChanges)
            testManager.close()
        }

    @Test
    fun `replacement cancels a pending same-track barrier`() =
        runTest(dispatcher) {
            val playback = FakePlayback()
            val current = song("current")
            playback.currentSong.value = current
            playback.readiness.value = PlaybackReadiness.BUFFERING
            val testManager = newManager(playback)
            testManager.manager.setGuestRoomStateForTesting()
            val codec = MessageCodec()

            testManager.manager.handleBinary(
                codec.encode(
                    MessageTypes.SYNC_PLAYBACK,
                    PlaybackActionPayload(
                        action = PlaybackActions.CHANGE_TRACK,
                        trackId = current.id,
                        trackInfo = track(current),
                        position = 10_000L,
                    ),
                ),
            )
            testManager.manager.handleBinary(
                codec.encode(
                    MessageTypes.SYNC_PLAYBACK,
                    PlaybackActionPayload(
                        action = PlaybackActions.CHANGE_TRACK,
                        trackId = "replacement",
                        trackInfo = track(song("replacement")),
                    ),
                ),
            )
            playback.readiness.value = PlaybackReadiness.READY
            runCurrent()

            assertNull(playback.lastRemotePosition)
            testManager.close()
        }

    @Test
    fun `room exit cancels a pending same-track barrier`() =
        runTest(dispatcher) {
            val playback = FakePlayback()
            val current = song("current")
            playback.currentSong.value = current
            playback.readiness.value = PlaybackReadiness.BUFFERING
            val testManager = newManager(playback)
            testManager.manager.setGuestRoomStateForTesting()
            val codec = MessageCodec()

            testManager.manager.handleBinary(
                codec.encode(
                    MessageTypes.SYNC_PLAYBACK,
                    PlaybackActionPayload(
                        action = PlaybackActions.CHANGE_TRACK,
                        trackId = current.id,
                        trackInfo = track(current),
                        position = 10_000L,
                    ),
                ),
            )
            testManager.manager.handleBinary(
                codec.encode(
                    MessageTypes.ERROR,
                    ErrorPayload("session_not_found", "Session expired"),
                ),
            )
            playback.readiness.value = PlaybackReadiness.READY
            runCurrent()

            assertFalse(testManager.manager.isInRoom.value)
            assertNull(playback.lastRemotePosition)
            testManager.close()
        }

    @Test
    fun `host applies a canonical queue without an active song`() =
        runTest(dispatcher) {
            val playback = FakePlayback()
            val old = song("old")
            val playlistNext = song("playlist-next", "Playlist")
            playback.queue.value = listOf(old, playlistNext)
            playback.staleQueueIndex = 0
            val testManager = newManager(playback)
            testManager.manager.handleReconnected(
                ReconnectedPayload(
                    roomCode = "ROOM1234",
                    userId = "host",
                    state =
                        RoomState(
                            roomCode = "ROOM1234",
                            hostId = "host",
                            users = emptyList(),
                            currentTrack = null,
                            isPlaying = false,
                            position = 0L,
                            lastUpdate = 1_000L,
                            queue = listOf(track(playlistNext)),
                        ),
                    isHost = true,
                ),
            )
            val suggestion = track(song("suggested"))

            testManager.manager.handleBinary(canonicalQueueAdd(suggestion, playlistNext))

            assertEquals(listOf("suggested", "playlist-next"), playback.queue.value.map { it.id })
            testManager.close()
        }

    @Test
    fun `queue mutations during loading are applied after playback is ready`() =
        runTest(dispatcher) {
            val playback = FakePlayback()
            val old = song("old")
            val playlistNext = song("playlist-next", "Playlist")
            playback.currentSong.value = old
            playback.queue.value = listOf(old, playlistNext)
            val resolverReady = CompletableDeferred<Unit>()
            val testManager =
                newManager(
                    playback,
                    resolver =
                        ListenTogetherTrackResolver {
                            resolverReady.await()
                            song("new")
                        },
                )
            val state = roomState(song("new"), listOf(track(playlistNext)))
            testManager.manager.handleReconnected(
                ReconnectedPayload(
                    roomCode = "ROOM1234",
                    userId = "guest",
                    state = state,
                    isHost = false,
                ),
            )
            val suggestion = track(song("suggested"))

            testManager.manager.handleBinary(canonicalQueueAdd(suggestion, playlistNext))
            testManager.manager.handleBinary(
                canonicalQueueSnapshot(
                    action = PlaybackActions.QUEUE_REMOVE,
                    queue = listOf(track(playlistNext)),
                    revision = 2L,
                    trackInfo = suggestion,
                ),
            )
            testManager.manager.handleBinary(
                canonicalQueueSnapshot(
                    action = PlaybackActions.SYNC_QUEUE,
                    queue = listOf(track(playlistNext).copy(title = "Enriched while loading")),
                    revision = 3L,
                ),
            )
            resolverReady.complete(Unit)
            yield()

            assertEquals(listOf("new", "playlist-next"), playback.queue.value.map { it.id })
            assertEquals(
                "Enriched while loading",
                playback.queue.value
                    .last()
                    .title,
            )
            testManager.close()
        }

    @Test
    fun `manual approval applies a playlist suggestion once`() =
        runTest(dispatcher) {
            val previous = Settings.shared.listenTogetherAutoApproveSongSuggestions
            Settings.shared.listenTogetherAutoApproveSongSuggestions = false
            try {
                val playback = FakePlayback()
                val current = song("current")
                val playlistNext = song("playlist-next", "Playlist")
                playback.currentSong.value = current
                playback.queue.value = listOf(current, playlistNext)
                var resolverCalls = 0
                val testManager = newManager(playback) { resolverCalls++ }
                testManager.manager.handleReconnected(hostReconnected(current, listOf(track(playlistNext))))

                val suggestion = track(song("suggested"))
                testManager.manager.handleBinary(
                    MessageCodec().encode(
                        MessageTypes.SUGGESTION_RECEIVED,
                        SuggestionReceivedPayload("suggestion", "guest", "Guest", suggestion),
                    ),
                )
                testManager.manager.approveSuggestion("suggestion")
                testManager.manager.handleBinary(canonicalQueueAdd(suggestion, playlistNext))
                testManager.manager.handleBinary(
                    MessageCodec().encode(
                        MessageTypes.SUGGESTION_APPROVED,
                        SuggestionApprovedPayload("suggestion", suggestion),
                    ),
                )

                assertEquals(0, resolverCalls)
                assertEquals(0, playback.addToQueueCalls)
                assertEquals(listOf("current", "suggested", "playlist-next"), playback.queue.value.map { it.id })
                assertEquals(
                    "Playlist",
                    playback.queue.value
                        .last()
                        .queueSourceTitle,
                )
                testManager.close()
            } finally {
                Settings.shared.listenTogetherAutoApproveSongSuggestions = previous
            }
        }

    @Test
    fun `automatic approval waits for the canonical queue action`() =
        runTest(dispatcher) {
            val previous = Settings.shared.listenTogetherAutoApproveSongSuggestions
            Settings.shared.listenTogetherAutoApproveSongSuggestions = true
            try {
                val playback = FakePlayback()
                val current = song("current")
                val playlistNext = song("playlist-next", "Playlist")
                playback.currentSong.value = current
                playback.queue.value = listOf(current, playlistNext)
                var resolverCalls = 0
                val testManager = newManager(playback) { resolverCalls++ }
                testManager.manager.handleReconnected(hostReconnected(current, listOf(track(playlistNext))))

                val suggestion = track(song("suggested"))
                testManager.manager.handleBinary(
                    MessageCodec().encode(
                        MessageTypes.SUGGESTION_RECEIVED,
                        SuggestionReceivedPayload("suggestion", "guest", "Guest", suggestion),
                    ),
                )
                testManager.manager.handleBinary(canonicalQueueAdd(suggestion, playlistNext))

                assertEquals(0, resolverCalls)
                assertEquals(0, playback.addToQueueCalls)
                assertEquals(listOf("current", "suggested", "playlist-next"), playback.queue.value.map { it.id })
                testManager.close()
            } finally {
                Settings.shared.listenTogetherAutoApproveSongSuggestions = previous
            }
        }

    @Test
    fun automaticApprovalFeedbackRequiresConfirmationAndIsEmittedOnce() =
        runTest(dispatcher) {
            val previous = Settings.shared.listenTogetherAutoApproveSongSuggestions
            Settings.shared.listenTogetherAutoApproveSongSuggestions = true
            val test = newManager(FakePlayback())
            val events = mutableListOf<ListenTogetherEvent.SuggestionAutoApproved>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    test.manager.events.collect { if (it is ListenTogetherEvent.SuggestionAutoApproved) events += it }
                }
            try {
                val current = song("current")
                test.manager.handleReconnected(hostReconnected(current, emptyList()))
                val suggestion = track(song("suggested"))

                suspend fun message(
                    type: String,
                    payload: Any,
                ) = test.manager.handleBinary(MessageCodec().encode(type, payload))

                suspend fun receive(id: String) =
                    message(
                        MessageTypes.SUGGESTION_RECEIVED,
                        SuggestionReceivedPayload(id, "guest", "Guest", suggestion),
                    )

                suspend fun approve(id: String) = message(MessageTypes.SUGGESTION_APPROVED, SuggestionApprovedPayload(id, suggestion))

                receive("auto")
                message(MessageTypes.ERROR, ErrorPayload("server_error", "Failed"))
                yield()
                assertTrue(events.isEmpty())
                test.manager.setAutoApproveSongSuggestions(false)
                approve("auto")
                approve("auto")
                approve("unknown")
                yield()
                assertEquals(listOf(ListenTogetherSuggestion("auto", "guest", "Guest", suggestion)), events.map { it.suggestion })

                receive("manual")
                test.manager.approveSuggestion("manual")
                approve("manual")
                test.manager.setAutoApproveSongSuggestions(true)
                receive("rejected")
                message(MessageTypes.SUGGESTION_REJECTED, SuggestionRejectedPayload("rejected", null))
                approve("rejected")
                receive("manually-rejected")
                test.manager.rejectSuggestion("manually-rejected")
                approve("manually-rejected")
                receive("manually-approved")
                test.manager.approveSuggestion("manually-approved")
                approve("manually-approved")
                yield()
                assertEquals(1, events.size)

                receive("old-host")
                message(MessageTypes.HOST_CHANGED, HostChangedPayload("guest", "Guest"))
                message(MessageTypes.HOST_CHANGED, HostChangedPayload("host", "Host"))
                approve("old-host")
                receive("old-room")
                test.manager.leaveRoom()
                test.manager.handleReconnected(hostReconnected(current, emptyList()))
                approve("old-room")
                yield()
                assertEquals(1, events.size)
            } finally {
                collector.cancel()
                test.close()
                Settings.shared.listenTogetherAutoApproveSongSuggestions = previous
            }
        }

    private fun canonicalQueueAdd(
        suggestion: TrackInfo,
        playlistNext: SongItem,
    ): ByteArray =
        canonicalQueueSnapshot(
            action = PlaybackActions.QUEUE_ADD,
            queue = listOf(suggestion, track(playlistNext)),
            revision = 1L,
            trackInfo = suggestion,
        )

    private fun canonicalQueueSnapshot(
        action: String,
        queue: List<TrackInfo>,
        revision: Long,
        trackInfo: TrackInfo? = null,
    ): ByteArray =
        MessageCodec().encode(
            MessageTypes.SYNC_PLAYBACK,
            PlaybackActionPayload(
                action = action,
                trackInfo = trackInfo,
                queue = queue,
                revision = revision,
            ),
        )

    private fun newManager(
        playback: FakePlayback,
        resolver: ListenTogetherTrackResolver? = null,
        onResolve: () -> Unit = {},
    ): TestManager {
        val scope = CoroutineScope(Job() + dispatcher)
        val client = HttpClient(MockEngine { error("No request expected") })
        val manager =
            ListenTogetherManager(
                callerScope = scope,
                codecDispatcher = dispatcher,
                httpClient = client,
                playback = playback,
                resolver =
                    resolver ?: ListenTogetherTrackResolver {
                        onResolve()
                        null
                    },
                monotonicClockMs = { 0L },
                wallClockMs = { 1_000L },
                timestampFormatter = { "time" },
                sessionPersistenceEnabled = false,
            )
        return TestManager(manager, client, scope)
    }

    private class TestManager(
        val manager: ListenTogetherManager,
        private val client: HttpClient,
        private val scope: CoroutineScope,
    ) {
        fun close() {
            manager.close()
            client.close()
            scope.cancel()
        }
    }

    private fun hostReconnected(
        current: SongItem,
        queue: List<TrackInfo>,
    ) = ReconnectedPayload(
        roomCode = "ROOM1234",
        userId = "host",
        state = roomState(current, queue),
        isHost = true,
    )

    private fun roomState(
        current: SongItem,
        queue: List<TrackInfo>,
    ) = RoomState(
        roomCode = "ROOM1234",
        hostId = "host",
        users = emptyList(),
        currentTrack = track(current),
        isPlaying = true,
        position = 10_000L,
        lastUpdate = 1_000L,
        volume = 1f,
        queue = queue,
    )

    private fun track(song: SongItem) =
        TrackInfo(
            id = song.id,
            title = song.title,
            artist = "Artist",
            album = null,
            duration = 180_000L,
            thumbnail = null,
        )

    private fun song(
        id: String,
        queueSourceTitle: String? = null,
    ) = SongItem(
        id = id,
        title = id,
        thumbnail = null,
        artists = emptyList(),
        album = null,
        queueSourceTitle = queueSourceTitle,
    )

    private class FakePlayback : ListenTogetherPlayback {
        override val connected = MutableStateFlow(false)
        override val currentSong = MutableStateFlow<SongItem?>(null)
        override val playing = MutableStateFlow(false)
        override val positionMs = MutableStateFlow(0L)
        override val durationMs = MutableStateFlow(180_000L)
        override val volume = MutableStateFlow(1f)
        override val queue = MutableStateFlow<List<SongItem>>(emptyList())
        override val readiness = MutableStateFlow(PlaybackReadiness.READY)
        var playQueueCalls = 0
        var queueLoadGate: CompletableDeferred<Unit>? = null
        var addToQueueCalls = 0
        var skipCalls = 0
        var staleQueueIndex: Int? = null
        var liveQueueSnapshot: MusicQueueSnapshot? = null
        var lastRemotePosition: Long? = null
        val playingChanges = mutableListOf<Boolean>()

        override fun currentPositionMs() = positionMs.value

        var queueTitle: String? = null

        override fun currentQueueTitle(): String? = queueTitle

        override fun currentQueueSnapshot(): MusicQueueSnapshot = liveQueueSnapshot ?: super.currentQueueSnapshot()

        override fun currentQueueIndex(): Int? =
            if (currentSong.value == null) {
                staleQueueIndex
            } else {
                queue.value.indexOfFirst { it.id == currentSong.value?.id }.takeIf { it >= 0 }
            }

        override fun playbackSpeed() = 1f

        override suspend fun playQueueFromRemote(
            songs: List<SongItem>,
            startIndex: Int,
            title: String?,
        ) {
            queueTitle = title
            playQueueCalls++
            queueLoadGate?.await()
            queue.value = songs
            currentSong.value = songs.getOrNull(startIndex)
        }

        override fun setQueueTitleFromRemote(title: String?) {
            queueTitle = title
        }

        override suspend fun replaceUpcomingQueueFromRemote(songs: List<SongItem>) {
            val index = currentQueueIndex()
            queue.value = if (currentSong.value == null) songs else queue.value.take((index ?: -1) + 1) + songs
        }

        override suspend fun addToQueueFromRemote(song: SongItem) {
            addToQueueCalls++
            queue.value += song
        }

        override suspend fun skipToNextFromRemote() {
            skipCalls++
        }

        override suspend fun skipToPreviousFromRemote() {
            skipCalls++
        }

        override suspend fun setPlayingFromRemote(playing: Boolean) {
            playingChanges += playing
            this.playing.value = playing
        }

        override suspend fun seekToFromRemote(positionMs: Long) {
            lastRemotePosition = positionMs
            this.positionMs.value = positionMs
        }

        override suspend fun setPlaybackSpeedFromRemote(speed: Float) = Unit

        override suspend fun setVolumeFromRemote(volume: Float) {
            this.volume.value = volume
        }
    }
}
