package com.metrolist.android.playback

import androidx.media3.common.Player
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.deviceconnect.DcLocalPlaybackState
import com.metrolist.shared.deviceconnect.DcPlaybackSnapshot
import com.metrolist.shared.model.SearchResultPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.scrobble.ScrobbleManager
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidDeviceConnectPlaybackTest {
    @Test
    fun supersededSnapshotCannotPrepareOrCommitAfterResolution() = staleResolution(release = false)

    @Test
    fun releasedPlaybackCannotPrepareOrCommitAfterResolution() = staleResolution(release = true)

    private fun staleResolution(release: Boolean) =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val context = RuntimeEnvironment.getApplication()
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            try {
                val song = SongItem("remote", "Remote", null, emptyList(), null)
                val queueState = MutableStateFlow(MusicQueueSnapshot())
                val currentSong = MutableStateFlow<SongItem?>(null)
                val resource = MutableStateFlow<PlaybackResource?>(null)
                val wantsPlayback = MutableStateFlow(false)
                var appliedPitch: Float? = null
                var preparations = 0
                var saves = 0
                val player =
                    Proxy.newProxyInstance(
                        AndroidPlaybackPlayer::class.java.classLoader,
                        arrayOf(AndroidPlaybackPlayer::class.java),
                    ) { _, method, args ->
                        when (method.name) {
                            "getCurrentSong" -> {
                                currentSong
                            }

                            "getCurrentPlaybackResource" -> {
                                resource
                            }

                            "getWantsPlaybackState" -> {
                                wantsPlayback
                            }

                            "queueSnapshot" -> {
                                queueState.value
                            }

                            "getQueueSnapshots" -> {
                                queueState
                            }

                            "restoreQueue" -> {
                                queueState.value = args!![0] as MusicQueueSnapshot
                                Unit
                            }

                            "isShuffleEnabled" -> {
                                false
                            }

                            "cancelNext", "setPlaybackSpeed", "pause" -> {
                                Unit
                            }

                            "setPlaybackPitch" -> {
                                appliedPitch = args!![0] as Float
                                Unit
                            }

                            "prepareAndPlay" -> {
                                preparations++
                                true
                            }

                            else -> {
                                error("Unexpected player call: ${method.name}")
                            }
                        }
                    } as AndroidPlaybackPlayer
                val repeat = MutableStateFlow(Player.REPEAT_MODE_OFF)
                val shuffle = MutableStateFlow(false)
                val queue = AndroidQueueCoordinator(context, player, backgroundScope, repeat, shuffle, {}, {})
                val resolver = MusicVideoResolver(search = { _, _ -> SearchResultPage(emptyList()) })
                val video = AndroidVideoModeController(context, player, backgroundScope, resolver, { it() }, { _, _ -> null }, {})
                val transitions =
                    AndroidQueueTransitions(
                        player,
                        backgroundScope,
                        AudioCacheManager(context, database),
                        resolver,
                        queue,
                        { _, _ -> null },
                        { _, _, _, _ -> },
                        {},
                        episodeResumePosition = { _, _ -> 0L },
                    )
                queue.transitions = transitions
                val requested = CompletableDeferred<Unit>()
                val resolved = CompletableDeferred<PlaybackResource>()
                val playback =
                    AndroidDeviceConnectPlayback(
                        context,
                        player,
                        queue,
                        video,
                        transitions,
                        ScrobbleManager(backgroundScope),
                        AndroidListeningHistory(player, backgroundScope, { _, _ -> }, writerScope = backgroundScope),
                        repeat,
                        shuffle,
                        fetchPlaybackResource = { _, _ ->
                            requested.complete(Unit)
                            resolved.await()
                        },
                        isCasting = { false },
                        onSave = { saves++ },
                        onDiscordUpdate = {},
                    )
                val desiredQueue = MusicQueue().apply { setQueue(listOf(song)) }.snapshot()
                val snapshot = DcPlaybackSnapshot("session", 1, 1, 1, DcLocalPlaybackState(queue = desiredQueue, pitch = 0.75f))
                var current = true
                val applying = launch { playback.port.apply(snapshot, 0) { current } }
                runCurrent()
                assertTrue(requested.isCompleted)
                assertEquals(0.75f, appliedPitch)
                assertEquals(desiredQueue, queueState.value)
                assertFalse(playback.syncTargetLoaded)
                if (release) playback.release() else current = false
                resolved.complete(PlaybackResource(song.id, "https://example.invalid/audio", emptyMap(), null, null))
                applying.join()
                assertEquals(0, preparations)
                assertEquals(0, saves)
                assertFalse(playback.syncTargetLoaded)
                assertFalse(queue.isPlaybackLoading.value)
                assertEquals(null, ReflectionHelpers.getField<String?>(playback, "appliedSyncSessionId"))
                assertEquals(null, ReflectionHelpers.getField<Long?>(playback, "appliedSyncTrackRevision"))
            } finally {
                database.close()
                Dispatchers.resetMain()
                Settings.replaceSharedForTest(null)
            }
        }
}
