package com.metrolist.android.playback

import androidx.media3.common.Player
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.android.models.PersistPlayerState
import com.metrolist.android.models.PlaybackPersistenceWriter
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.deviceconnect.DcLocalPlaybackState
import com.metrolist.shared.deviceconnect.DcPlaybackSnapshot
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.MusicQueueEntry
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.playback.MusicQueueState
import com.metrolist.shared.playback.QueuePlaybackRequest
import com.metrolist.shared.scrobble.ScrobbleManager
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.metrolist.shared.youtube.playback.StreamResolver
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class PendingQueueRestorePositionTest {
    @Test
    fun playingAnotherOccurrenceDoesNotReuseTheRestoredPosition() {
        for (requested in listOf(0L, 7_000L)) {
            val queue = restoredQueue()
            val first = queue.currentEntry()
            val position = PendingQueueRestorePosition().apply { restore(first.key, 42_000L) }

            queue.skipTo(2)
            assertEquals(first.song, queue.currentEntry().song)
            assertNotEquals(first.key, queue.currentEntry().key)
            assertEquals(requested, position.take(queue.currentEntry().key, requested))

            queue.skipTo(0)
            assertEquals(0L, position.take(queue.currentEntry().key, 0L))
        }
    }

    @Test
    fun movingAndSeekingTheOriginalOccurrenceKeepsItsPendingOffset() {
        val queue = restoredQueue()
        val original = queue.currentEntry()
        val position = PendingQueueRestorePosition().apply { restore(original.key, 42_000L) }
        queue.move(0, 1)
        assertEquals(original, queue.currentEntry())

        position.update(queue.currentEntry().key, 9_000L)
        assertEquals(9_000L, position.take(queue.currentEntry().key, 0L))
        assertEquals(0L, position.take(queue.currentEntry().key, 0L))
    }

    @Test
    fun aZeroSavedPositionDoesNotOverrideAnExplicitRequest() {
        val position = PendingQueueRestorePosition().apply { restore(10L, 0L) }
        assertEquals(7_000L, position.take(10L, 7_000L))
        position.restore(10L, 42_000L)
        position.update(10L, 0L)
        assertEquals(0L, position.take(10L, 7_000L))
    }

    @Test
    fun aTransientEmptyQueueKeepsTheSavedQueueAndPosition() =
        runTest {
            val state = pausedQueueState()
            var stored = ""
            var position = ""
            val writer =
                PlaybackPersistenceWriter(
                    writePosition = { position = it },
                    writeStoredValue = { stored = it },
                )
            writer.write(writer.request(state))
            val savedState = stored
            val savedPosition = position

            val lifecycle = PlaybackPersistenceLifecycle()
            lifecycle.restore {}
            val decision =
                decidePlaybackCheckpoint(
                    writer = writer,
                    lifecycle = lifecycle,
                    persistentQueue = true,
                    previous = state,
                    state = { null },
                )

            assertNull(decision.request)
            assertEquals(state, decision.state)
            assertEquals(savedState, stored)
            assertEquals(savedPosition, position)
            assertTrue(stored.isNotBlank())
            assertTrue(position.isNotBlank())
        }

    @Test
    fun anExplicitClearRemovesTheSavedQueueAndPosition() {
        var stored = ""
        var position = ""
        val writer =
            PlaybackPersistenceWriter(
                writePosition = { position = it },
                writeStoredValue = { stored = it },
            )
        writer.write(writer.request(pausedQueueState()))
        assertTrue(stored.isNotBlank())
        assertTrue(position.isNotBlank())

        writer.write(writer.requestClear())

        assertEquals("", stored)
        assertEquals("", position)
    }

    @Test
    fun anAppliedEmptyDeviceConnectSnapshotClearsSavedPlayback() =
        runTest {
            val persistence = savedPlayback()
            createDeviceConnectFixture(onQueueCleared = persistence::clear).use { fixture ->
                fixture.subject.port.apply(
                    DcPlaybackSnapshot(
                        sessionId = "session",
                        revision = 1L,
                        queueRevision = 1L,
                        trackRevision = 1L,
                        playback = DcLocalPlaybackState(finite = false),
                    ),
                    positionMs = 0L,
                    shouldCommit = { true },
                )
            }
            persistence.assertCleared()
        }

    @Test
    fun aFollowedControllerClearsSavedPlaybackOnlyAfterItsQueueEmpties() =
        runTest {
            val persistence = savedPlayback()
            val queue = queuedSnapshot()
            createDeviceConnectFixture(queue, persistence::clear).use { fixture ->
                fixture.subject.port.setControllerPlayback(DcLocalPlaybackState(queue = queue, finite = false))
                persistence.assertSaved()

                fixture.subject.port.setControllerPlayback(DcLocalPlaybackState(finite = false))
            }
            persistence.assertCleared()
        }

    @Test
    fun aFirstConfirmedEmptyControllerSnapshotClearsAfterProvisionalEmptyState() =
        runTest {
            val persistence = savedPlayback()
            createDeviceConnectFixture(queuedSnapshot(), persistence::clear).use { fixture ->
                fixture.subject.port.setControllerPlayback(DcLocalPlaybackState(finite = false), provisional = true)
                fixture.subject.port.setControllerPlayback(DcLocalPlaybackState(finite = false))
            }
            persistence.assertCleared()
        }

    @Test
    fun aConfirmedEmptyControllerSnapshotClearsWhenTheLocalQueueWasAlreadyEmpty() =
        runTest {
            val persistence = savedPlayback()
            createDeviceConnectFixture(onQueueCleared = persistence::clear).use { fixture ->
                fixture.subject.port.setControllerPlayback(DcLocalPlaybackState(finite = false), provisional = true)
                fixture.subject.port.setControllerPlayback(DcLocalPlaybackState(finite = false))
            }
            persistence.assertCleared()
        }

    @Test
    fun aFirstConfirmedEmptyControllerSnapshotClearsWithoutAProvisionalState() =
        runTest {
            val persistence = savedPlayback()
            createDeviceConnectFixture(queuedSnapshot(), persistence::clear).use { fixture ->
                fixture.subject.port.setControllerPlayback(DcLocalPlaybackState(finite = false))
            }
            persistence.assertCleared()
        }

    private class SavedPlayback(
        private val writer: PlaybackPersistenceWriter,
        private val stored: AtomicReference<String>,
        private val position: AtomicReference<String>,
    ) {
        fun clear() = writer.write(writer.requestClear())

        fun assertSaved() {
            assertTrue(stored.get().isNotBlank())
            assertTrue(position.get().isNotBlank())
        }

        fun assertCleared() {
            assertEquals("", stored.get())
            assertEquals("", position.get())
        }
    }

    private fun savedPlayback(): SavedPlayback {
        val stored = AtomicReference("")
        val position = AtomicReference("")
        val writer =
            PlaybackPersistenceWriter(
                writePosition = { position.set(it) },
                writeStoredValue = { stored.set(it) },
            )
        writer.write(writer.request(pausedQueueState()))
        return SavedPlayback(writer, stored, position)
    }

    private class DeviceConnectFixture(
        val subject: AndroidDeviceConnectPlayback,
        private val scope: CoroutineScope,
        private val database: MusicDatabase,
        private val cache: AudioCacheManager,
    ) : AutoCloseable {
        override fun close() {
            scope.cancel()
            cache.cacheDirectory().deleteRecursively()
            database.close()
            Settings.replaceSharedForTest(null)
            Dispatchers.resetMain()
        }
    }

    private fun createDeviceConnectFixture(
        queue: MusicQueueSnapshot = MusicQueueSnapshot(),
        onQueueCleared: () -> Unit,
    ): DeviceConnectFixture {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        Settings.replaceSharedForTest(Settings(MapSettings()))
        val context = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val player = fakeAndroidPlaybackPlayer(queue)
        val database =
            Room
                .inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
                .setDriver(AndroidSQLiteDriver())
                .build()
        val cache = AudioCacheManager(context, database)
        val repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
        val shuffleModeEnabled = MutableStateFlow(false)
        val videoResolver = MusicVideoResolver()
        val coordinator =
            AndroidQueueCoordinator(
                context = context,
                player = player,
                scope = scope,
                _repeatMode = repeatMode,
                _shuffleModeEnabled = shuffleModeEnabled,
                onSave = {},
                onSessionReset = {},
            )
        val transitions =
            AndroidQueueTransitions(
                player = player,
                scope = scope,
                audioCacheManager = cache,
                musicVideoResolver = videoResolver,
                queue = coordinator,
                fetchPlaybackResource = { _, _ -> null },
                onPreloadFailure = { _, _, _, _ -> },
                onAdvance = {},
                episodeResumePosition = { _, duration -> duration },
            )
        coordinator.transitions = transitions
        val streamResolver =
            object : StreamResolver {
                override suspend fun resolve(
                    videoId: String,
                    hints: ContentHints,
                    audioQuality: AudioQuality,
                    sourceSong: SongItem?,
                ): PlaybackResource = error("No stream resolution expected in this test")

                override suspend fun refresh(
                    videoId: String,
                    hints: ContentHints,
                    audioQuality: AudioQuality,
                    sourceSong: SongItem?,
                ): PlaybackResource = error("No stream resolution expected in this test")
            }
        val video =
            AndroidVideoModeController(
                context = context,
                player = player,
                scope = scope,
                musicVideoResolver = videoResolver,
                streamResolver = streamResolver,
                withPlaybackLoading = { block -> block() },
                fetchPlaybackResource = { _, _ -> null },
                onError = {},
                isDownloaded = { false },
            )
        val history =
            AndroidListeningHistory(
                player = player,
                scope = scope,
                recordPlay = { _, _ -> },
            )
        val scrobble = ScrobbleManager(scope)
        val subject =
            AndroidDeviceConnectPlayback(
                context = context,
                player = player,
                queue = coordinator,
                video = video,
                transitions = transitions,
                scrobbleManager = scrobble,
                history = history,
                repeatMode = repeatMode,
                shuffleModeEnabled = shuffleModeEnabled,
                fetchPlaybackResource = { _, _ -> null },
                isCasting = { false },
                onSave = {},
                onQueueCleared = onQueueCleared,
                onDiscordUpdate = {},
            )
        return DeviceConnectFixture(subject, scope, database, cache)
    }

    private fun fakeAndroidPlaybackPlayer(queue: MusicQueueSnapshot): AndroidPlaybackPlayer {
        val currentSong = MutableStateFlow<SongItem?>(null)
        val isPlaying = MutableStateFlow(false)
        val wantsPlaybackState = MutableStateFlow(false)
        val volume = MutableStateFlow(1f)
        val playbackSpeed = MutableStateFlow(1f)
        val playbackPitch = MutableStateFlow(0f)
        val currentPosition = MutableStateFlow(0L)
        val bufferedPosition = MutableStateFlow(0L)
        val duration = MutableStateFlow(0L)
        val currentPlaybackResource = MutableStateFlow<PlaybackResource?>(null)
        val playbackStateFlow = MutableStateFlow(Player.STATE_IDLE)
        val playbackTransitionPending = MutableStateFlow(false)
        val songQueue = MutableStateFlow(queue.displayedItems)
        val queueSnapshots = MutableStateFlow(queue)
        val queueState =
            MutableStateFlow(
                MusicQueueState(
                    entries =
                        queue.displayedItems.mapIndexed { index, song ->
                            MusicQueueEntry(index.toLong(), song)
                        },
                    currentIndex = queue.currentIndex.coerceAtLeast(0),
                ),
            )
        val currentMediaItemIndex = MutableStateFlow(queue.currentIndex.coerceAtLeast(0))
        val queuePlaybackRequests = MutableSharedFlow<QueuePlaybackRequest>()
        val onEofPlaybackInstance = MutableSharedFlow<Long>()
        val streamFailure = MutableSharedFlow<AndroidPlaybackRecoveryRequest>()
        val shuffleEnabled = MutableStateFlow(false)
        val playbackInstanceId = MutableStateFlow(0L)
        val loadError = MutableStateFlow<String?>(null)
        return Proxy.newProxyInstance(
            AndroidPlaybackPlayer::class.java.classLoader,
            arrayOf(AndroidPlaybackPlayer::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "FakeAndroidPlaybackPlayer"
                "getCurrentSong" -> currentSong
                "isPlaying" -> isPlaying
                "getIsPlaying" -> isPlaying
                "getWantsPlaybackState" -> wantsPlaybackState
                "getVolume" -> volume
                "getPlaybackSpeed" -> playbackSpeed
                "getPlaybackPitch" -> playbackPitch
                "getCurrentPosition" -> currentPosition
                "getBufferedPosition" -> bufferedPosition
                "getDuration" -> duration
                "getCurrentPlaybackResource" -> currentPlaybackResource
                "getPlaybackStateFlow" -> playbackStateFlow
                "getPlaybackTransitionPending" -> playbackTransitionPending
                "getSongQueue" -> songQueue
                "getQueueSnapshots" -> queueSnapshots
                "getQueueState" -> queueState
                "getCurrentMediaItemIndex" -> currentMediaItemIndex
                "getQueuePlaybackRequests" -> queuePlaybackRequests
                "getOnEOFPlaybackInstance" -> onEofPlaybackInstance
                "getStreamFailure" -> streamFailure
                "getShuffleEnabled" -> shuffleEnabled
                "getPlaybackInstanceId" -> playbackInstanceId
                "getLoadError" -> loadError
                "getWantsPlayback" -> false
                "getNextPlaybackPositionMs" -> 0L
                "getNextPlaybackDurationMs" -> 0L
                "isNextPlaybackRendering" -> false
                "getLatestQueuePlaybackRequestId" -> 0L
                "getCurrentPlaybackInstanceId" -> 0L
                "getPlaybackState" -> Player.STATE_IDLE
                "isReady" -> false
                "getIsReady" -> false
                "isShuffleEnabled" -> false
                "queueSnapshot" -> queue
                "playbackSpeed" -> 1f
                "getRealtimePositionMs" -> 0L
                "getAudioSessionId" -> 0
                "startNextPlayback" -> false
                "isPreloadedPlaybackActive" -> false
                "swapToPreloadedQueueNext" -> false
                "retryPendingQueuePlayback" -> false
                "refreshPlaybackResource" -> false
                "toggleShuffle" -> false
                else -> defaultValue(method.returnType)
            }
        } as AndroidPlaybackPlayer
    }

    private fun defaultValue(type: Class<*>): Any? =
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            Void.TYPE -> null
            else -> null
        }

    private fun queuedSnapshot() =
        MusicQueueSnapshot(
            displayedItems = listOf(SongItem("first", "First", null, emptyList(), null)),
            currentIndex = 0,
        )

    private fun pausedQueueState() =
        PersistPlayerState(
            playWhenReady = false,
            repeatMode = 0,
            shuffleModeEnabled = false,
            volume = 1f,
            currentPosition = 10_000L,
            currentMediaItemIndex = 0,
            playbackState = 1,
            queueSongs =
                listOf(
                    SongItem(id = "first", title = "First", thumbnail = null, artists = emptyList(), album = null),
                    SongItem(id = "second", title = "Second", thumbnail = null, artists = emptyList(), album = null),
                ),
        )

    private fun restoredQueue(): MusicQueue {
        val duplicate = SongItem("duplicate", "Duplicate", null, emptyList(), null)
        val other = duplicate.copy(id = "other")
        val prior = MusicQueue().apply { setQueue(listOf(duplicate, other, duplicate)) }
        return MusicQueue().apply { restore(prior.snapshot()) }
    }

    private fun MusicQueue.currentEntry(): MusicQueueEntry = state.value.let { it.entries[it.currentIndex] }
}
