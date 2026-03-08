package com.metrolist.android.playback

import androidx.media3.common.Player
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.cast.CastConnectionStatus
import com.metrolist.shared.cast.CastDevice
import com.metrolist.shared.cast.CastProtocol
import com.metrolist.shared.cast.CastState
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.model.SearchResultPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StartupShufflePersistenceTest {
    private val storage = MapSettings()
    private val scope = TestScope()
    private lateinit var database: MusicDatabase
    private val songs = listOf("first", "second", "third").map { SongItem(it, it, null, emptyList(), null) }

    @Before
    fun setUp() {
        Settings.replaceSharedForTest(Settings(storage))
        Settings.shared.persistentQueue = false
        Settings.shared.rememberShuffleAndRepeat = true
        Settings.shared.persistentShuffleAcrossQueues = true
        database =
            Room
                .inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    MusicDatabase::class.java,
                ).setDriver(AndroidSQLiteDriver())
                .build()
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun persistenceUsesReceiverProgressOnlyForItsQueueOccurrence() {
        Settings.shared.persistentQueue = true
        val session = Session()
        session.queue.setQueue(listOf(songs[0], songs[0]))
        session.remote =
            CastState(
                status = CastConnectionStatus.CONNECTED,
                device = CastDevice("receiver", "Receiver", CastProtocol.FCAST, "host", 46899),
                queueEntry =
                    session.queue.state.value.entries
                        .first(),
                isPlaying = true,
                positionMs = 60_000L,
            )
        val playing = session.coordinator.buildPersistPlayerState()!!
        assertEquals(60_000L, playing.currentPosition)
        assertTrue(playing.playWhenReady)
        session.remote = session.remote.copy(isPlaying = false, positionMs = 90_000L)
        val paused = session.coordinator.buildPersistPlayerState()!!
        assertEquals(90_000L, paused.currentPosition)
        assertFalse(paused.playWhenReady)
        session.queue.next()
        assertEquals(1_000L, session.coordinator.buildPersistPlayerState()!!.currentPosition)
    }

    @Test
    fun restartWithoutPersistentQueueRetainsShuffleForTheFirstQueue() {
        rememberShuffledQueue()
        Settings.replaceSharedForTest(Settings(storage))
        val restarted = Session()
        restarted.coordinator.restoreShuffleMode()
        assertTrue(restarted.shuffle.value)
        assertTrue(
            restarted.queue.items.value
                .isEmpty(),
        )
        restarted.coordinator.saveShuffleMode()
        assertTrue(Settings.shared.shuffleEnabled)

        restarted.coordinator.playQueue(songs, startIndex = 1, fromRemote = true)

        assertTrue(restarted.queue.isShuffled)
        assertTrue(restarted.shuffle.value)
        assertEquals(songs[1], restarted.queue.currentSong())
        assertEquals(1, restarted.queue.currentIndex.value)
        assertEquals(songs, restarted.queue.snapshot().originalItems)
        assertNull(restarted.coordinator.buildPersistPlayerState())
        assertEquals("", Settings.shared.persistentPlaybackState)
    }

    @Test
    fun explicitShuffleMakesEveryOtherOccurrenceUpcomingFromAnyStartPosition() {
        val collection = songs + songs[1].copy()
        for (persistentShuffle in listOf(false, true)) {
            Settings.shared.persistentShuffleAcrossQueues = persistentShuffle
            for (startIndex in collection.indices) {
                val session = Session()
                session.coordinator.playQueue(
                    collection,
                    startIndex = startIndex,
                    fromRemote = true,
                    shuffleModeEnabled = true,
                )

                val snapshot = session.queue.snapshot()
                assertTrue(session.shuffle.value)
                assertTrue(snapshot.isShuffled)
                assertEquals(0, snapshot.currentIndex)
                assertEquals(collection[startIndex], session.queue.currentSong())
                assertEquals(collection, snapshot.originalItems)
                assertEquals(startIndex, snapshot.originalIndices.first())
                assertEquals(collection.indices.toList(), snapshot.originalIndices.sorted())

                val played = generateSequence(session.queue.currentSong()) { session.queue.next() }.toList()
                assertEquals(collection.groupingBy { it }.eachCount(), played.groupingBy { it }.eachCount())
            }
        }
    }

    @Test
    fun disabledStartupRememberingDoesNotRestoreShuffle() {
        rememberShuffledQueue()
        Settings.shared.rememberShuffleAndRepeat = false
        val restarted = Session()
        restarted.coordinator.restoreShuffleMode()
        restarted.coordinator.playQueue(songs, fromRemote = true)
        assertFalse(restarted.shuffle.value)
        assertFalse(restarted.queue.isShuffled)
    }

    @Test
    fun disabledCrossQueuePersistenceDoesNotShuffleTheFirstQueue() {
        rememberShuffledQueue()
        Settings.shared.persistentShuffleAcrossQueues = false
        val restarted = Session()
        restarted.coordinator.restoreShuffleMode()
        assertTrue(restarted.shuffle.value)
        restarted.coordinator.playQueue(songs, fromRemote = true)
        assertFalse(restarted.queue.isShuffled)
    }

    @Test
    fun explicitUnshuffledQueueOverridesAndUpdatesRememberedShuffle() {
        rememberShuffledQueue()
        val restarted = Session()
        restarted.coordinator.restoreShuffleMode()
        restarted.coordinator.playQueue(songs, fromRemote = true, shuffleModeEnabled = false)
        restarted.coordinator.saveShuffleMode()
        assertFalse(Settings.shared.shuffleEnabled)
        val nextRestart = Session()
        nextRestart.coordinator.restoreShuffleMode()
        assertFalse(nextRestart.shuffle.value)
    }

    private fun rememberShuffledQueue() {
        val previous = Session()
        previous.queue.setQueue(songs)
        previous.queue.toggleShuffle()
        previous.coordinator.saveShuffleMode()
        assertTrue(Settings.shared.shuffleEnabled)
        assertNull(previous.coordinator.buildPersistPlayerState())
        Settings.shared.flush()
    }

    private inner class Session {
        val queue = MusicQueue()
        var remote = CastState()
        val shuffle = MutableStateFlow(false)

        @Suppress("UNCHECKED_CAST")
        val player =
            Proxy.newProxyInstance(
                AndroidPlaybackPlayer::class.java.classLoader,
                arrayOf(AndroidPlaybackPlayer::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getQueueState" -> {
                        queue.state
                    }

                    "isPlaying" -> {
                        MutableStateFlow(false)
                    }

                    "getRealtimePositionMs" -> {
                        1_000L
                    }

                    "getQueueSnapshots" -> {
                        queue.snapshots
                    }

                    "getSongQueue" -> {
                        queue.items
                    }

                    "isShuffleEnabled" -> {
                        queue.isShuffled
                    }

                    "cancelNext" -> {
                        Unit
                    }

                    "setQueue" -> {
                        queue.setQueue(args!![0] as List<SongItem>, args[1] as Int)
                    }

                    "toggleShuffle" -> {
                        queue.toggleShuffle(preserveHistory = args!![0] as Boolean)
                        queue.isShuffled
                    }

                    else -> {
                        error("Unexpected player call: ${method.name}")
                    }
                }
            } as AndroidPlaybackPlayer

        val coordinator =
            AndroidQueueCoordinator(
                RuntimeEnvironment.getApplication(),
                player,
                scope,
                MutableStateFlow(Player.REPEAT_MODE_OFF),
                shuffle,
                {},
                {},
                castState = { remote },
            ).apply {
                transitions =
                    AndroidQueueTransitions(
                        player,
                        scope,
                        AudioCacheManager(RuntimeEnvironment.getApplication(), database),
                        MusicVideoResolver(search = { _, _ -> SearchResultPage(emptyList()) }),
                        this,
                        { _, _ -> null },
                        { _, _, _, _ -> },
                        {},
                        episodeResumePosition = { _, _ -> 0L },
                    )
            }
    }
}
