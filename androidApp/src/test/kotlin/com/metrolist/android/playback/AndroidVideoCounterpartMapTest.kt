package com.metrolist.android.playback

import android.app.Application
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.StreamExtractor
import com.metrolist.shared.api.musicvideo.CounterpartPositionMap
import com.metrolist.shared.api.musicvideo.CounterpartSegment
import com.metrolist.shared.api.musicvideo.MusicVideoCounterpart
import com.metrolist.shared.api.musicvideo.MusicVideoResolver
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.DefaultStreamResolver
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
class AndroidVideoCounterpartMapTest {
    @Test
    fun resolvedQueueCounterpartMapsBackToAudioAndDoesNotReuseAnotherPairsMap() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val http = HttpClient(MockEngine { respondOk() })
            val counterpart =
                MusicVideoResolver(
                    counterpartLookup = {
                        MusicVideoCounterpart(
                            it.id,
                            "mapped-video",
                            "MUSIC_VIDEO_TYPE_OMV",
                            CounterpartPositionMap(listOf(CounterpartSegment(0, 4_492, 131_989))),
                        )
                    },
                )
            val resolver =
                DefaultStreamResolver(
                    extractor = VideoExtractor(),
                    innerTube = InnerTube(http),
                    musicVideoResolver = counterpart,
                    networkStateFlow = null,
                    resolveDispatcher = StandardTestDispatcher(testScheduler),
                )
            try {
                val song = SongItem("audio-track", "Track", null, emptyList(), null, musicVideoType = "MUSIC_VIDEO_TYPE_ATV")
                val resolved = resolver.resolve(song.id, ContentHints(wantVideo = true), sourceSong = song)
                val currentSong = MutableStateFlow<SongItem?>(song)
                val currentResource = MutableStateFlow<PlaybackResource?>(resolved)
                var requestedPosition: Long? = null
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

                            "getCurrentPlaybackInstanceId", "getLatestQueuePlaybackRequestId" -> {
                                1L
                            }

                            "getRealtimePositionMs" -> {
                                30_000L
                            }

                            "getWantsPlayback" -> {
                                false
                            }

                            "prepareAndPlay" -> {
                                assertEquals(song.id, (args!![0] as SongItem).id)
                                requestedPosition = args[2] as Long
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
                        counterpart,
                        { it() },
                        { target, wantVideo ->
                            check(!wantVideo)
                            PlaybackResource(target.id, "https://audio.test", emptyMap(), null, null)
                        },
                        { error(it) },
                    )
                controller.onQueuePlaybackStarted(song, resolved.hasVideo, resolved)
                controller.onSongChanged(song)
                controller.setVideoMode(false)
                runCurrent()
                assertEquals(25_508L, requestedPosition)

                val differentPair = resolved.copy(musicVideoId = "different-video", musicVideoPositionMap = null)
                currentResource.value = differentPair
                controller.onQueuePlaybackStarted(song, differentPair.hasVideo, differentPair)
                controller.setVideoMode(false)
                runCurrent()
                assertEquals(30_000L, requestedPosition)
            } finally {
                resolver.close()
                http.close()
                Settings.replaceSharedForTest(null)
            }
        }

    private class VideoExtractor : StreamExtractor {
        override suspend fun prewarm() = Unit

        override suspend fun extract(
            videoId: String,
            hints: ContentHints,
            excludedClients: Set<String>,
            audioQuality: AudioQuality,
            clientPlaybackNonce: String,
        ) = ExtractedStream(
            videoId = videoId,
            audioUrl = "https://audio.test",
            videoUrl = "https://video.test",
            headers = emptyMap(),
            loudnessDb = null,
            expiresAt = null,
            contentLengthBytes = null,
            itag = 140,
            mimeType = "audio/mp4",
            codecs = "mp4a",
            bitrate = null,
            sampleRate = null,
            clientName = "client",
            profileId = "profile",
            requireBoundedRange = false,
            rangeChunkSizeBytes = 0L,
            streamDiagnostics = null,
        )
    }
}
