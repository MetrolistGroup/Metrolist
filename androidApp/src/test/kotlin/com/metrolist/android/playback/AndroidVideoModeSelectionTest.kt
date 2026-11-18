package com.metrolist.android.playback

import android.app.Application
import com.metrolist.shared.api.musicvideo.CounterpartPositionMap
import com.metrolist.shared.api.musicvideo.CounterpartSegment
import com.metrolist.shared.api.musicvideo.MusicVideoCounterpart
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.model.ArtistTiny
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
import org.junit.Assert.assertSame
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
    fun downloadedRecordingSurvivesSongVideoSongSwitchesAndMissingDownloadsStillResolveCounterparts() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                for (removeDownload in listOf(false, true)) {
                    Settings.shared.videoViewerVisible = false
                    val source =
                        SongItem(
                            "video",
                            "Song",
                            null,
                            listOf(ArtistTiny(null, "Artist")),
                            null,
                            duration = 185L,
                            musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
                        )
                    val counterpart = source.copy(id = "audio-counterpart", duration = 180L, musicVideoType = "MUSIC_VIDEO_TYPE_ATV")
                    val downloadedAudio = PlaybackResource(source.id, "file://downloaded-audio", emptyMap(), null, null)
                    val videoResource = downloadedAudio.copy(url = "https://media.test/audio", videoUrl = "https://media.test/video")
                    val counterpartAudio = downloadedAudio.copy(videoId = counterpart.id, url = "https://media.test/counterpart")
                    val map = CounterpartPositionMap(listOf(CounterpartSegment(5_000L, 0L, 180_000L)))
                    var lookups = 0
                    val resolver =
                        MusicVideoResolver(
                            counterpartLookup = {
                                lookups++
                                MusicVideoCounterpart(it.id, counterpart.id, counterpart.musicVideoType, map)
                            },
                            songLookup = { counterpart },
                            search = { _, _ -> error("An explicit counterpart should not search") },
                            settings = Settings.shared,
                        )
                    val initial =
                        resolveAndroidAudioTrackSelection(
                            source,
                            20_000L,
                            wantVideo = false,
                            cachedAudio = downloadedAudio,
                            sourceIsDownloaded = true,
                            resolveMusicTrack = resolver::resolveMusicTrack,
                        )
                    assertEquals(source, initial.song)
                    assertSame(downloadedAudio, initial.cachedAudio)
                    assertEquals(0, lookups)
                    val currentSong = MutableStateFlow<SongItem?>(initial.song)
                    val currentResource = MutableStateFlow(initial.cachedAudio)
                    var positionMs = initial.positionMs
                    var playbackInstanceId = 1L
                    var downloadAvailable = true
                    val preparations = mutableListOf<Triple<SongItem, PlaybackResource, Long>>()
                    val player =
                        Proxy.newProxyInstance(
                            AndroidPlaybackPlayer::class.java.classLoader,
                            arrayOf(AndroidPlaybackPlayer::class.java),
                        ) { _, method, args ->
                            when (method.name) {
                                "getCurrentPlaybackResource" -> {
                                    currentResource
                                }

                                "getWantsPlaybackState", "isPlaying" -> {
                                    MutableStateFlow(false)
                                }

                                "getCurrentSong" -> {
                                    currentSong
                                }

                                "getCurrentPlaybackInstanceId" -> {
                                    playbackInstanceId
                                }

                                "getLatestQueuePlaybackRequestId" -> {
                                    1L
                                }

                                "getRealtimePositionMs" -> {
                                    positionMs
                                }

                                "getWantsPlayback" -> {
                                    false
                                }

                                "prepareAndPlay" -> {
                                    assertTrue((args!![4] as () -> Boolean)())
                                    val song = args[0] as SongItem
                                    val resource = args[1] as PlaybackResource
                                    assertEquals(source.id != song.id, args[7])
                                    positionMs = args[2] as Long
                                    preparations += Triple(song, resource, positionMs)
                                    currentSong.value = song
                                    currentResource.value = resource
                                    playbackInstanceId++
                                    true
                                }

                                else -> {
                                    error("Unexpected player call: ${method.name}")
                                }
                            }
                        } as AndroidPlaybackPlayer
                    val requests = mutableListOf<Pair<String, Boolean>>()
                    val controller =
                        AndroidVideoModeController(
                            RuntimeEnvironment.getApplication(),
                            player,
                            backgroundScope,
                            resolver,
                            withPlaybackLoading = { it() },
                            fetchPlaybackResource = { song, wantVideo ->
                                requests += song.id to wantVideo
                                when {
                                    wantVideo -> {
                                        videoResource
                                    }

                                    song.id == counterpart.id -> {
                                        counterpartAudio
                                    }

                                    else -> {
                                        assertTrue(downloadAvailable)
                                        downloadedAudio
                                    }
                                }
                            },
                            onError = { error(it) },
                            isDownloaded = {
                                assertEquals(source.id, it)
                                downloadAvailable
                            },
                        )
                    controller.onQueuePlaybackStarted(initial.song, hasVideo = false, resource = initial.cachedAudio)
                    controller.setVideoMode(true)
                    runCurrent()
                    assertTrue(controller.isVideoModeActive.value)
                    assertEquals(source.id, currentSong.value?.id)
                    downloadAvailable = !removeDownload
                    positionMs = 40_000L
                    controller.setVideoMode(false)
                    runCurrent()

                    val expectedSong = if (removeDownload) counterpart else source
                    assertEquals(listOf(source.id to true, expectedSong.id to false), requests)
                    assertEquals(listOf(source.id, expectedSong.id), preparations.map { it.first.id })
                    assertEquals(listOf(20_000L, if (removeDownload) 35_000L else 40_000L), preparations.map { it.third })
                    assertSame(if (removeDownload) counterpartAudio else downloadedAudio, preparations.last().second)
                    assertEquals(if (removeDownload) 1 else 0, lookups)
                    assertFalse(controller.isVideoModeActive.value)
                    assertFalse(controller.isVideoViewerVisible.value)
                    controller.clearVideoMode(resetViewerPreference = true)
                }
            } finally {
                Settings.replaceSharedForTest(null)
            }
        }

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
                        isDownloaded = { false },
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
