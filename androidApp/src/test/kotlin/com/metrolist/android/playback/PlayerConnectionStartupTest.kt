package com.metrolist.android.playback

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import androidx.media3.common.Player
import com.metrolist.android.models.PersistPlayerState
import com.metrolist.android.models.playbackPersistenceJson
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.SongLikeState
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
class PlayerConnectionStartupTest {
    @Test
    fun serviceShutdownReleasesTheBindingAndTheNextCommandCanBindAgain() {
        val previousSettings = Settings.sharedOverrideForTest()
        Settings.replaceSharedForTest(Settings(MapSettings()))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        MusicService.running.value = false
        try {
            val context = BindingContext()
            val connection = PlayerConnection(context, scope)
            connection.ensureConnected()
            assertEquals(0, context.unbinds)
            MusicService.running.value = true
            assertEquals(1, context.binds)
            MusicService.running.value = false
            assertEquals(1, context.unbinds)
            assertFalse(connection.isConnected.value)
            connection.ensureConnected()
            assertEquals(2, context.binds)
        } finally {
            scope.cancel()
            MusicService.running.value = false
            Settings.replaceSharedForTest(previousSettings)
        }
    }

    @Test
    fun dislikeWhileBindingDoesNotSkipTheRestoredSuccessor() =
        runBlocking {
            val previousSettings = Settings.sharedOverrideForTest()
            val settings = Settings(MapSettings())
            Settings.replaceSharedForTest(settings)
            SongLikeState.refreshFromSettings()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val song = SongItem("first", "First", null, emptyList(), null)
                val next = song.copy(id = "next")
                val last = song.copy(id = "last")
                val persisted = PersistPlayerState(false, 0, false, 1f, 0, 0, Player.STATE_IDLE, queueSongs = listOf(song, next, last))
                settings.persistentQueue = true
                settings.persistentPlaybackState = playbackPersistenceJson.encodeToString(PersistPlayerState.serializer(), persisted)
                val context = BindingContext()
                val connection = PlayerConnection(context, scope)
                withTimeout(5_000) { connection.currentSong.first { it != null } }
                assertEquals(song, connection.currentSong.value)

                assertTrue(connection.toggleDislike(song))
                assertEquals(1, context.binds)
                val (_, restored) = persisted.restoredPlaybackState(false, BlockedContent.snapshot()::isBlocked)
                val queue = MusicQueue().apply { restore(restored) }
                assertEquals(next, queue.currentItem())
                var skips = 0
                val player =
                    Proxy.newProxyInstance(
                        AndroidPlaybackPlayer::class.java.classLoader,
                        arrayOf(AndroidPlaybackPlayer::class.java),
                    ) { _, method, _ ->
                        when (method.name) {
                            "getCurrentSong" -> {
                                MutableStateFlow(queue.currentItem())
                            }

                            "getWantsPlayback" -> {
                                false
                            }

                            "getSongQueue" -> {
                                queue.items
                            }

                            "getCurrentMediaItemIndex" -> {
                                queue.currentIndex
                            }

                            "skipToNext" -> {
                                skips++
                                queue.next()
                                Unit
                            }

                            else -> {
                                error("Unexpected player call: ${method.name}")
                            }
                        }
                    } as AndroidPlaybackPlayer
                val service = Robolectric.buildService(MusicService::class.java).get()
                ReflectionHelpers.setField(service, "player", player)
                ReflectionHelpers.setField(
                    service,
                    "queueCoordinator",
                    AndroidQueueCoordinator(context, player, scope, MutableStateFlow(0), MutableStateFlow(false), {}, {}),
                )
                ReflectionHelpers.setField(connection, "musicService", service)
                val pending = ReflectionHelpers.getField<PendingPlaybackCommands>(connection, "pendingCommands")
                pending.connected()

                assertEquals(0, skips)
                assertEquals(next, queue.currentItem())
                connection.skipToNext()
                assertEquals(1, skips)
                assertEquals(last, queue.currentItem())

                queue.setQueue(listOf(song, next, last))
                connection.onSongDisliked(song.id)
                assertEquals(2, skips)
                assertEquals(next, queue.currentItem())
            } finally {
                scope.cancel()
                settings.dislikedSongIds = ""
                SongLikeState.refreshFromSettings()
                Settings.replaceSharedForTest(previousSettings)
                if (previousSettings != null) SongLikeState.refreshFromSettings()
            }
        }

    @Test
    fun browsingRestoresPausedMetadataWithoutBindingAndFirstCommandsBindOnlyOnce() =
        runBlocking {
            val settings = Settings(MapSettings())
            Settings.replaceSharedForTest(settings)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val song = SongItem("saved", "Saved", null, emptyList(), null, duration = 120)
                settings.persistentQueue = true
                settings.rememberShuffleAndRepeat = true
                settings.persistentPlaybackState =
                    playbackPersistenceJson.encodeToString(
                        PersistPlayerState.serializer(),
                        PersistPlayerState(
                            true,
                            Player.REPEAT_MODE_ALL,
                            false,
                            0.75f,
                            17_000,
                            0,
                            Player.STATE_READY,
                            queueTitle = "Saved queue",
                            queueSongs = listOf(song),
                        ),
                    )
                val context = BindingContext()
                val connection = PlayerConnection(context, scope)
                withTimeout(5_000) { connection.currentSong.first { it != null } }
                assertEquals(0, context.binds)
                assertFalse(connection.isConnected.value)
                assertFalse(connection.isPlaying.value)
                assertNull(connection.videoPlayer)
                assertEquals(listOf(song), connection.queueSongs.value)
                assertEquals(17_000L, connection.currentPosition.value)
                assertEquals(Player.REPEAT_MODE_ALL, connection.repeatMode.value)
                connection.togglePlayPause()
                connection.seekTo(30_000)
                connection.skipToNext()
                assertEquals(1, context.binds)
                assertFalse(connection.isConnected.value)
            } finally {
                scope.cancel()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun dismissingThePreviewClearsPersistenceWithoutStartingPlayback() =
        runBlocking {
            val settings = Settings(MapSettings())
            Settings.replaceSharedForTest(settings)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val song = SongItem("saved", "Saved", null, emptyList(), null)
                settings.persistentQueue = true
                settings.persistentPlaybackState =
                    playbackPersistenceJson.encodeToString(
                        PersistPlayerState.serializer(),
                        PersistPlayerState(false, 0, false, 1f, 0, 0, Player.STATE_IDLE, queueSongs = listOf(song)),
                    )
                val context = BindingContext()
                val connection = PlayerConnection(context, scope)
                withTimeout(5_000) { connection.currentSong.first { it != null } }
                connection.prepareDismissal().invoke()
                assertEquals(0, context.binds)
                assertNull(connection.currentSong.value)
                assertEquals("", settings.persistentPlaybackState)
                assertEquals(emptyList<SongItem>(), connection.queueSongs.value)
            } finally {
                scope.cancel()
                Settings.replaceSharedForTest(null)
            }
        }

    private class BindingContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        var binds = 0
        var unbinds = 0

        override fun unbindService(conn: ServiceConnection) {
            unbinds++
        }

        override fun bindService(
            service: Intent,
            conn: ServiceConnection,
            flags: Int,
        ): Boolean {
            binds++
            return true
        }
    }
}
