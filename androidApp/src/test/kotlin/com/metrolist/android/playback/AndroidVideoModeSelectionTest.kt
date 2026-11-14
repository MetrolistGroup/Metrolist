package com.metrolist.android.playback

import android.app.Application
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.model.SearchResultPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.player.shouldShowMusicVideoSurface
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
class AndroidVideoModeSelectionTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun ordinaryRowsKeepTheCurrentModeAndExplicitVideoSelectionCanBeToggledOff() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                val currentSong = MutableStateFlow<SongItem?>(null)
                var audioRequests = 0
                var replacements = 0
                val player =
                    Proxy.newProxyInstance(
                        AndroidPlaybackPlayer::class.java.classLoader,
                        arrayOf(AndroidPlaybackPlayer::class.java),
                    ) { _, method, args ->
                        when (method.name) {
                            "getCurrentPlaybackResource" -> {
                                MutableStateFlow<PlaybackResource?>(null)
                            }

                            "getWantsPlaybackState" -> {
                                MutableStateFlow(false)
                            }

                            "getCurrentSong" -> {
                                currentSong
                            }

                            "getCurrentPlaybackInstanceId", "getLatestQueuePlaybackRequestId" -> {
                                1L
                            }

                            "getRealtimePositionMs" -> {
                                0L
                            }

                            "isPlaying" -> {
                                MutableStateFlow(false)
                            }

                            "getWantsPlayback" -> {
                                false
                            }

                            "prepareAndPlay" -> {
                                assertEquals(currentSong.value, args!![0])
                                assertFalse((args[1] as PlaybackResource).hasVideo)
                                assertFalse(args[3] as Boolean)
                                replacements++
                                true
                            }

                            else -> {
                                error("Unexpected player call: ${method.name}")
                            }
                        }
                    } as AndroidPlaybackPlayer
                val controller =
                    AndroidVideoModeController(
                        RuntimeEnvironment.getApplication(),
                        player,
                        backgroundScope,
                        MusicVideoResolver(
                            counterpartLookup = { null },
                            search = { _, _ -> SearchResultPage(emptyList()) },
                            settings = Settings.shared,
                        ),
                        withPlaybackLoading = { it() },
                        fetchPlaybackResource = { song, wantVideo ->
                            assertFalse(wantVideo)
                            audioRequests++
                            PlaybackResource(song.id, "https://audio.test/${song.id}", emptyMap(), null, null)
                        },
                        onError = { error(it) },
                    )
                val video = SongItem("4CmYE-fbVWc", "Nothing on Me", null, emptyList(), null, musicVideoType = "MUSIC_VIDEO_TYPE_OMV")
                val track = video.copy(id = "obGydj8nf7o", musicVideoType = "MUSIC_VIDEO_TYPE_ATV")

                for (type in listOf("MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC", "MUSIC_VIDEO_TYPE_VIDEO")) {
                    val row = video.copy(musicVideoType = type)
                    currentSong.value = row
                    controller.onQueuePlaybackStarted(row, hasVideo = false)
                    assertFalse(controller.isVideoViewerVisible.value)
                    assertFalse(Settings.shared.videoViewerVisible)
                }
                controller.selectVideoForQueue(track)
                assertFalse(controller.isVideoViewerVisible.value)
                controller.selectVideoForQueue(video)
                assertTrue(Settings.shared.videoViewerVisible)
                assertTrue(controller.isVideoViewerVisible.value)
                assertFalse(controller.isVideoModeActive.value)
                assertEquals(0, audioRequests)
                assertEquals(0, replacements)

                currentSong.value = video
                controller.onQueuePlaybackStarted(video, hasVideo = true)
                assertTrue(
                    shouldShowMusicVideoSurface(controller.isVideoModeActive.value, controller.isVideoViewerVisible.value, hasVideo = true),
                )

                controller.toggleVideoMode()
                runCurrent()
                assertEquals(1, audioRequests)
                assertEquals(1, replacements)
                controller.onQueuePlaybackStarted(video, hasVideo = false)
                assertFalse(controller.isVideoModeActive.value)
                assertFalse(controller.isVideoViewerVisible.value)
                assertFalse(Settings.shared.videoViewerVisible)

                controller.selectVideoForQueue(video)
                controller.onQueuePlaybackStarted(track, hasVideo = false)
                assertTrue(controller.isVideoViewerVisible.value)
                assertTrue(Settings.shared.videoViewerVisible)
                controller.clearVideoMode(resetViewerPreference = true)
            } finally {
                Settings.replaceSharedForTest(null)
            }
        }
}
