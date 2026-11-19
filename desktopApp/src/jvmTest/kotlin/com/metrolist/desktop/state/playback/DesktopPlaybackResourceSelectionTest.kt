package com.metrolist.desktop.state.playback

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.StreamExtractor
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_UNSPECIFIED_VIDEO
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.DefaultStreamResolver
import com.metrolist.shared.youtube.playback.LocalAudioSource
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DesktopPlaybackResourceSelectionTest {
    private var previousSettings: Settings? = null

    @BeforeTest
    fun isolateSettings() {
        previousSettings = Settings.sharedOverrideForTest()
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun restoreSettings() {
        Settings.replaceSharedForTest(previousSettings)
    }

    private val song = SongItem("video", "Video", null, emptyList(), null, musicVideoType = MUSIC_VIDEO_TYPE_UNSPECIFIED_VIDEO)
    private val local = PlaybackResource("video", "file:/download", emptyMap(), null, null)
    private val remote = PlaybackResource("video", "https://example.test/audio", emptyMap(), null, null)

    @Test
    fun storedVideoAudioWinsOnlineWithoutFetchingVideo() =
        runBlocking {
            var extractions = 0
            val extractor =
                object : StreamExtractor {
                    override suspend fun prewarm() = Unit

                    override suspend fun extract(
                        videoId: String,
                        hints: ContentHints,
                        excludedClients: Set<String>,
                        audioQuality: AudioQuality,
                        clientPlaybackNonce: String,
                    ): ExtractedStream {
                        extractions++
                        return ExtractedStream(
                            videoId = videoId,
                            audioUrl = remote.url,
                            headers = emptyMap(),
                            loudnessDb = null,
                            expiresAt = null,
                            contentLengthBytes = null,
                            itag = 251,
                            mimeType = "audio/webm",
                            codecs = "opus",
                            bitrate = null,
                            sampleRate = null,
                            clientName = "client",
                            profileId = "profile",
                            requireBoundedRange = false,
                            rangeChunkSizeBytes = 0L,
                        )
                    }
                }
            val http = HttpClient(MockEngine { respondOk() })
            val resolver =
                DefaultStreamResolver(
                    extractor,
                    InnerTube(http),
                    networkStateFlow = null,
                    localAudioSource = LocalAudioSource { _, _ -> local },
                    networkState = { PlaybackNetworkState("test", true) },
                )
            try {
                for (stored in listOf(local, local.copy(url = "file:/cache"))) {
                    assertSame(stored, select(storedAudio = { stored }) { hints, _ -> resolver.resolve(song.id, hints, sourceSong = song) })
                }
                assertEquals(0, extractions)
                // Explicit video requests must still be able to bypass stored audio.
                assertEquals(remote.url, resolver.resolve(song.id, ContentHints(wantVideo = true), sourceSong = song).url)
                assertEquals(1, extractions)
            } finally {
                resolver.close()
                http.close()
            }
        }

    @Test
    fun storedAudioAlsoWinsWhileVideoIsVisibleOrPlaybackIsSynchronized() =
        runBlocking {
            for (viewer in listOf(false, true)) {
                for (fullscreen in listOf(false, true)) {
                    for (synchronized in listOf(false, true)) {
                        assertSame(
                            local,
                            select(
                                showVideoViewer = viewer,
                                showVideoFullscreen = fullscreen,
                                synchronizedPlayback = synchronized,
                                storedAudio = { local },
                            ) { _, _ -> error("Stored audio must not be replaced by a remote stream") },
                        )
                    }
                }
            }
        }

    @Test
    fun forcedClientSkipsStoredAudio() =
        runBlocking {
            var storedCalls = 0
            val hints = mutableListOf<ContentHints>()
            assertSame(
                remote,
                select(forcedClientId = "WEB_CREATOR", storedAudio = {
                    storedCalls++
                    local
                }) { hint, _ ->
                    hints += hint
                    remote
                },
            )
            assertEquals(0, storedCalls)
            assertEquals(listOf(true), hints.map { it.wantVideo })
        }

    @Test
    fun missingStoredAudioRetainsVideoFallbackAndErrorReporting() =
        runBlocking {
            val calls = mutableListOf<Pair<Boolean, Boolean>>()
            assertSame(
                remote,
                select(storedAudio = { null }) { hints, reportError ->
                    calls += hints.wantVideo to reportError
                    remote.takeUnless { hints.wantVideo }
                },
            )
            assertEquals(listOf(true to false, false to true), calls)
        }

    @Test
    fun disabledVideoRequestsAudioAndReportsErrors() =
        runBlocking {
            val calls = mutableListOf<Pair<Boolean, Boolean>>()
            assertSame(
                remote,
                select(videoPlaybackEnabled = false, storedAudio = { null }) { hints, reportError ->
                    calls += hints.wantVideo to reportError
                    remote
                },
            )
            assertEquals(listOf(false to true), calls)
        }

    @Test
    fun cancellationDoesNotFallThroughToNetwork() =
        runBlocking {
            var networkCalls = 0
            assertFailsWith<CancellationException> {
                select(storedAudio = { throw CancellationException() }) { _, _ ->
                    networkCalls++
                    remote
                }
            }
            assertEquals(0, networkCalls)
        }

    private suspend fun select(
        showVideoViewer: Boolean = false,
        showVideoFullscreen: Boolean = false,
        synchronizedPlayback: Boolean = false,
        forcedClientId: String? = null,
        videoPlaybackEnabled: Boolean = true,
        storedAudio: suspend () -> PlaybackResource?,
        fetch: suspend (ContentHints, Boolean) -> PlaybackResource?,
    ) = selectDesktopPlaybackResource(
        song = song,
        showVideoViewer = showVideoViewer,
        showVideoFullscreen = showVideoFullscreen,
        synchronizedPlayback = synchronizedPlayback,
        videoPlaybackEnabled = videoPlaybackEnabled,
        forcedClientId = forcedClientId,
        showError = true,
        storedAudio = storedAudio,
        fetchByHints = fetch,
    )
}
