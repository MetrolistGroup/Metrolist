package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_LIVE
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_VIDEO
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isLive
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.extraction.models.toContentHints
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTubePlayerInformationTest {
    private val previousSettings = Settings.sharedOverrideForTest()

    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(previousSettings)
    }

    @Test
    fun pastLiveRecordingKeepsItsDurationAndNormalClientFallback() =
        runBlocking {
            for (androidFallback in listOf(false, true)) {
                val info = playerInformation(PAST_LIVE_RECORDING, androidFallback)
                assertFalse(info.isLive)
                assertEquals(400L, info.durationSeconds)
                assertEquals(MUSIC_VIDEO_TYPE_VIDEO, info.musicVideoType)
                val song = info.song()
                assertFalse(song.isLive)
                assertNull(song.toContentHints().isLive)
                assertNull(song.toContentHints().playbackClientOverrideId)
            }
        }

    @Test
    fun activeLiveStreamKeepsLivePlaybackWithoutAFiniteDuration() =
        runBlocking {
            for (fixture in listOf(
                ACTIVE_LIVE_STREAM,
                ACTIVE_LIVE_STREAM.replace("\"isLive\": true", "\"isLiveNow\": true"),
                ACTIVE_LIVE_STREAM.replace("\"lengthSeconds\": \"0\"", "\"lengthSeconds\": \"900\""),
                playerResponse(
                    "\"isLiveContent\": true, \"lengthSeconds\": \"0\"",
                    "\"microformat\": {\"playerMicroformatRenderer\": {\"liveBroadcastDetails\": {\"isLiveNow\": true}}}",
                ),
                playerResponse(
                    "\"lengthSeconds\": \"0\"",
                    "\"streamingData\": {\"hlsManifestUrl\": \"https://example.com/live.m3u8\"}",
                ),
            )) {
                val info = playerInformation(fixture)
                assertTrue(info.isLive)
                assertNull(info.durationSeconds)
                assertEquals(MUSIC_VIDEO_TYPE_LIVE, info.musicVideoType)
                assertTrue(info.song().isLive)
                assertEquals(true, info.song().toContentHints().isLive)
                assertEquals("ANDROID", info.song().toContentHints().playbackClientOverrideId)
            }
        }

    @Test
    fun normalSongKeepsItsDurationAndAudioType() =
        runBlocking {
            val info = playerInformation(NORMAL_SONG)
            assertFalse(info.isLive)
            assertEquals(240L, info.durationSeconds)
            assertEquals("MUSIC_VIDEO_TYPE_ATV", info.musicVideoType)
            assertFalse(info.song().isLive)
            assertNull(info.song().toContentHints().playbackClientOverrideId)
        }

    @Test
    fun liveContentAndHlsDoNotOverrideVodEvidence() =
        runBlocking {
            for (details in listOf(
                "\"isLiveContent\": true",
                "\"isLiveContent\": true, \"isLive\": false",
                "\"isLiveContent\": true, \"lengthSeconds\": \"240\"",
            )) {
                val info = playerInformation(playerResponse(details))
                assertFalse(info.isLive)
            }
            for (details in listOf(
                "\"isLiveContent\": true, \"isLive\": false",
                "\"isLiveContent\": true, \"lengthSeconds\": \"240\"",
            )) {
                val info =
                    playerInformation(
                        playerResponse(details, "\"streamingData\": {\"hlsManifestUrl\": \"https://example.com/vod.m3u8\"}"),
                    )
                assertFalse(info.isLive)
            }
        }

    private suspend fun playerInformation(
        fixture: String,
        androidFallback: Boolean = false,
    ): SongPlayerInformation {
        var requests = 0
        HttpClient(
            MockEngine {
                requests++
                respond(
                    if (androidFallback && requests == 1) """{"playabilityStatus":{"status":"ERROR"}}""" else fixture,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) { install(ContentNegotiation) { json(repositoryJson) } }.use { client ->
            val info = assertNotNull(YouTubeRepository(InnerTube(client)).getSongPlayerInformation("video"))
            assertEquals(if (androidFallback) 2 else 1, requests)
            assertEquals("Track", info.title)
            assertEquals("Artist", info.artist)
            return info
        }
    }

    private fun SongPlayerInformation.song() =
        SongItem(
            id = "video",
            title = title.orEmpty(),
            thumbnail = thumbnail,
            artists = emptyList(),
            album = null,
            duration = durationSeconds,
            musicVideoType = musicVideoType,
        )

    companion object {
        // Synthetic player responses contain only the fields used by the metadata request.
        private fun playerResponse(
            details: String,
            extra: String = "\"streamingData\": {}",
        ) = """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"video","title":"Track","author":"Artist",$details},$extra}"""

        private val PAST_LIVE_RECORDING =
            playerResponse(
                """"isLiveContent": true, "isLive": false, "lengthSeconds": "400", "musicVideoType": "MUSIC_VIDEO_TYPE_LIVE"""",
            )
        private val ACTIVE_LIVE_STREAM = playerResponse(""""isLiveContent": true, "isLive": true, "lengthSeconds": "0"""")
        private val NORMAL_SONG =
            playerResponse(""""isLiveContent": false, "lengthSeconds": "240", "musicVideoType": "MUSIC_VIDEO_TYPE_ATV"""")
    }
}
