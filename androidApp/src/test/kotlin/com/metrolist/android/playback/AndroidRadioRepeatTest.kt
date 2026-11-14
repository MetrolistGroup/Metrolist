package com.metrolist.android.playback

import android.content.ContextWrapper
import android.content.res.Resources
import androidx.media3.common.Player
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.model.RadioQueuePage
import com.metrolist.shared.model.SearchResultPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.MusicQueueSnapshot
import com.metrolist.shared.playback.QUEUE_SOURCE_AUTO_MIX
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

private fun Any?.asSongItemList(): List<SongItem> {
    val items = this as? List<*> ?: error("Expected a song list")
    return items.map { it as? SongItem ?: error("Expected a song list item") }
}

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AndroidRadioRepeatTest {
    @Test
    fun explicitRadioClearsRepeatBeforePlaybackAndAcceptsRecommendations() = checkRepeatModes(startRadio = true)

    @Test
    fun ordinaryQueuePlaybackPreservesRepeat() = checkRepeatModes(startRadio = false)

    @Test
    fun currentSongRadioRetainsPlaybackWithoutRequestingOrSeekingAudio() = checkRepeatModes(startRadio = true, currentSongId = "seed")

    @Test
    fun differentSongRadioStartsFromZero() = checkRepeatModes(startRadio = true, currentSongId = "other")

    @Test
    fun queuePlaybackCanPreparePaused() = checkRepeatModes(startRadio = false, startPlaying = false)

    private fun checkRepeatModes(
        startRadio: Boolean,
        currentSongId: String? = null,
        startPlaying: Boolean = true,
    ) {
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val previousNetwork = PlaybackNetworkStateStore.current
            val context =
                object : ContextWrapper(RuntimeEnvironment.getApplication()) {
                    @Suppress("DEPRECATION")
                    private val labels =
                        object : Resources(baseContext.assets, baseContext.resources.displayMetrics, baseContext.resources.configuration) {
                            override fun getString(
                                id: Int,
                                vararg formatArgs: Any,
                            ): String = formatArgs.first().toString()
                        }

                    override fun getResources(): Resources = labels
                }
            val database = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).setDriver(AndroidSQLiteDriver()).build()
            try {
                Settings.shared.autoLoadMore = false
                PlaybackNetworkStateStore.publish(PlaybackNetworkState("radio-test", true))
                for (mode in listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL)) {
                    val repeat = MutableStateFlow(mode)
                    val queue = MusicQueue()
                    val seed = SongItem("seed", "Seed", null, emptyList(), null)
                    val recommendation = seed.copy(id = "recommendation")
                    currentSongId?.let { queue.setQueue(listOf(seed.copy(id = "previous"), seed.copy(id = it), seed.copy(id = "next")), 1) }
                    var playbackRequests = 0
                    var queueRestores = 0
                    val player =
                        Proxy.newProxyInstance(
                            AndroidPlaybackPlayer::class.java.classLoader,
                            arrayOf(AndroidPlaybackPlayer::class.java),
                        ) { _, method, args ->
                            when (method.name) {
                                "getCurrentSong" -> {
                                    MutableStateFlow(queue.currentItem())
                                }

                                "isReady" -> {
                                    currentSongId != null
                                }

                                "getPlaybackTransitionPending" -> {
                                    MutableStateFlow(false)
                                }

                                "getRealtimePositionMs" -> {
                                    42_000L
                                }

                                "restoreQueue" -> {
                                    queueRestores++
                                    queue.restore(args!![0] as MusicQueueSnapshot)
                                }

                                "isShuffleEnabled" -> {
                                    false
                                }

                                "cancelNext" -> {
                                    Unit
                                }

                                "getSongQueue" -> {
                                    queue.items
                                }

                                "getQueueSnapshots" -> {
                                    queue.snapshots
                                }

                                "getCurrentMediaItemIndex" -> {
                                    queue.currentIndex
                                }

                                "setQueue" -> {
                                    playbackRequests++
                                    assertEquals(startPlaying, args!![2])
                                    assertEquals(0L, args[3])
                                    assertEquals(if (startRadio) Player.REPEAT_MODE_OFF else mode, repeat.value)
                                    queue.setQueue(args[0].asSongItemList())
                                }

                                "addToQueue" -> {
                                    queue.addToQueue(args?.getOrNull(0).asSongItemList())
                                }

                                else -> {
                                    error("Unexpected player call: ${method.name}")
                                }
                            }
                        } as AndroidPlaybackPlayer
                    val coordinator = AndroidQueueCoordinator(context, player, backgroundScope, repeat, MutableStateFlow(false), {}, {})
                    coordinator.transitions =
                        AndroidQueueTransitions(
                            player,
                            backgroundScope,
                            AudioCacheManager(context, database),
                            MusicVideoResolver(search = { _, _ -> SearchResultPage(emptyList()) }),
                            coordinator,
                            { _, _ -> null },
                            { _, _, _, _ -> },
                            {},
                            episodeResumePosition = { _, _ -> 0L },
                        )
                    if (startRadio) {
                        coordinator.playMix(seed)
                        val retained = currentSongId == seed.id
                        assertEquals(if (retained) 0 else 1, playbackRequests)
                        assertEquals(if (retained) 1 else 0, queueRestores)
                        assertEquals(0, queue.currentIndex.value)
                        val appended =
                            suspendCoroutineUninterceptedOrReturn<Boolean> { continuation ->
                                ReflectionHelpers.callInstanceMethod<Any?>(
                                    coordinator,
                                    "applyMixPage",
                                    from(RadioQueuePage::class.java, RadioQueuePage(listOf(seed, recommendation), emptyList(), null)),
                                    from(Long::class.javaPrimitiveType, coordinator.queueSessionId),
                                    from(
                                        Long::class.javaPrimitiveType,
                                        ReflectionHelpers.getField<Long>(coordinator, "recommendationRequestGeneration"),
                                    ),
                                    from(Boolean::class.javaPrimitiveType, false),
                                    from(String::class.java, QUEUE_SOURCE_AUTO_MIX),
                                    from(String::class.java, null),
                                    from(String::class.java, null),
                                    from(String::class.java, null),
                                    from(Int::class.javaObjectType, null),
                                    from(Continuation::class.java, continuation),
                                )
                            }
                        assertTrue(appended)
                        assertEquals(listOf("seed", "recommendation"), queue.items.value.map(SongItem::id))
                        assertEquals(
                            QUEUE_SOURCE_AUTO_MIX,
                            queue.items.value
                                .last()
                                .queueSourceTitle,
                        )
                    } else {
                        coordinator.playQueue(listOf(seed), startPlaying = startPlaying)
                        assertEquals(mode, repeat.value)
                        assertEquals(listOf(seed), queue.items.value)
                    }
                }
            } finally {
                database.close()
                PlaybackNetworkStateStore.publish(previousNetwork)
                Settings.replaceSharedForTest(null)
            }
        }
    }
}
