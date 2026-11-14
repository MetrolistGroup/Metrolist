package com.metrolist.android.playback

import android.app.Application
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteQueuePreparationTest {
    @Test
    fun failedCancelledAndObsoleteLoadsKeepTheCommittedQueueAndStation() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                val songs = listOf(song("first"), song("repeat"), song("repeat"))
                val queue =
                    MusicQueue().apply {
                        setQueue(songs, 2)
                        toggleShuffle()
                    }
                val before = queue.snapshot()
                val entries = queue.state.value
                val player =
                    Proxy.newProxyInstance(
                        AndroidPlaybackPlayer::class.java.classLoader,
                        arrayOf(AndroidPlaybackPlayer::class.java),
                    ) { _, method, _ ->
                        when (method.name) {
                            "getSongQueue" -> queue.items
                            "getQueueSnapshots" -> queue.snapshots
                            "getCurrentMediaItemIndex" -> queue.currentIndex
                            "isShuffleEnabled" -> queue.isShuffled
                            else -> error("Unexpected player mutation: ${method.name}")
                        }
                    } as AndroidPlaybackPlayer
                var saves = 0
                val coordinator =
                    AndroidQueueCoordinator(
                        RuntimeEnvironment.getApplication(),
                        player,
                        this,
                        MutableStateFlow(0),
                        MutableStateFlow(true),
                        onSave = { saves++ },
                        onSessionReset = {},
                    )
                coordinator.setTitle("Local station")
                val remote = listOf(song("room"), song("next"))
                assertFalse(coordinator.playQueueFromRemote(remote, 0, "Room") { _, _ -> false })
                val started = CompletableDeferred<Unit>()
                val cancelled =
                    async {
                        coordinator.playQueueFromRemote(remote, 0, "Room") { _, _ ->
                            started.complete(Unit)
                            CompletableDeferred<Unit>().await()
                            true
                        }
                    }
                started.await()
                cancelled.cancelAndJoin()
                val gate = CompletableDeferred<Unit>()
                val obsolete =
                    async {
                        coordinator.playQueueFromRemote(remote, 0, "Old room") { _, _ ->
                            gate.await()
                            true
                        }
                    }
                runCurrent()
                assertFalse(coordinator.playQueueFromRemote(remote, 0, "New room") { _, _ -> false })
                gate.complete(Unit)
                assertFalse(obsolete.await())
                assertEquals(before, queue.snapshot())
                assertEquals(entries, queue.state.value)
                assertEquals("Local station", coordinator.queueTitle.value)
                assertEquals(0, saves)
            } finally {
                Settings.replaceSharedForTest(null)
            }
        }

    private fun song(id: String) = SongItem(id, id, null, emptyList(), null)
}
