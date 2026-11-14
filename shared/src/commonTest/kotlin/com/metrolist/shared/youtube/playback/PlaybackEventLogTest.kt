package com.metrolist.shared.youtube.playback

import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.PlaybackFailure
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlaybackEventLogTest {
    @BeforeTest
    @AfterTest
    fun clearEvents() {
        PlaybackEventLog.clear()
    }

    @Test
    fun exportCorrelatesStreamOpensWithPlayerCommits() {
        PlaybackEventLog.info(PlaybackEventKind.SABR, "Opened audio stream.", videoId = "search-video")
        PlaybackEventLog.info(
            PlaybackEventKind.PLAYER,
            "Android main player preparation committed.",
            videoId = "album-audio",
            details = mapOf("audioVideoId" to "album-audio", "playbackInstanceId" to "42"),
        )

        val exported = PlaybackEventLog.exportText()
        assertContains(exported, "SABR videoId=search-video")
        assertContains(exported, "PLAYER videoId=album-audio")
        assertContains(exported, "audioVideoId=album-audio")
        assertContains(exported, "playbackInstanceId=42")
    }

    @Test
    fun redactsEntireMediaUrl() {
        val url =
            "https://rr1---sn-abc.googlevideo.com/videoplayback" +
                "?id=video&itag=251&pot=SECRETTOKEN&n=sigvalue&ns=namespace&spc=playbackcontext" +
                "&ip=192.0.2.1&bui=visitorbinding&futureCredential=secret"
        val redacted = PlaybackUrlRedaction.redact(url)

        assertEquals("<redacted-url:${url.length}chars>", redacted)
        assertFalse(redacted.contains("googlevideo"))
        assertFalse(redacted.contains("SECRETTOKEN"))
    }

    @Test
    fun exportExcludesRawThrowableMessagesAndSuppressedMessages() {
        val secret = "private-title-and-token"
        val error =
            IllegalStateException(
                "https://example.test/media?pot=$secret\n    at $secret",
                UnsupportedOperationException(secret),
            )
        error.addSuppressed(IllegalArgumentException(secret))

        PlaybackEventLog.error(PlaybackEventKind.PLAYER, "Playback failed.", error = error)

        val exported = PlaybackEventLog.exportText()
        assertContains(exported, "IllegalStateException")
        assertContains(exported, "IllegalArgumentException")
        assertContains(exported, "UnsupportedOperationException")
        assertFalse(exported.contains("exceptionStack="))
        assertFalse(exported.contains(secret))
        assertFalse(exported.contains("https://example.test"))
    }

    @Test
    fun resourceDetailsRedactMediaUrlsAndCookieValues() {
        val details =
            PlaybackResource(
                videoId = "abc123",
                url = "https://rr1---sn-abc.googlevideo.com/videoplayback?id=video&itag=251&pot=SECRETTOKEN&n=sigvalue",
                headers = mapOf("User-Agent" to "Metrolist/test", "Cookie" to "SAPISID=secret"),
                loudnessDb = -7.5,
                expiresAt = null,
            ).toPlaybackLogDetails()
        assertEquals("<redacted-url:95chars>", details.getValue("url"))
        assertFalse(details.getValue("url").contains("SECRETTOKEN"))
        assertFalse(details.values.any { it.contains("SAPISID=secret") })
    }

    @Test
    fun resourceDetailsExcludeOriginAndRefererTargets() {
        val secret = "private-account-path"
        val details =
            PlaybackResource(
                videoId = "abc123",
                url = "https://media.example.test/videoplayback?itag=251",
                headers =
                    mapOf(
                        "Origin" to "https://music.example.test/$secret?token=value",
                        "Referer" to "https://music.example.test/$secret",
                    ),
                loudnessDb = null,
                expiresAt = null,
            ).toPlaybackLogDetails()

        assertFalse(details.values.any { it.contains(secret) })
    }

    @Test
    fun songDetailsExcludeDisplayMetadata() {
        val details =
            SongItem(
                id = "abc123",
                title = "Private title",
                thumbnail = "https://example.test/private-artwork",
                artists = listOf(ArtistTiny(id = "artist", name = "Private artist")),
                album = AlbumTiny(id = "album", name = "Private album"),
            ).toPlaybackLogDetails()

        assertFalse(details.containsKey("songTitle"))
        assertFalse(details.containsKey("artists"))
        assertFalse(details.containsKey("album"))
        assertFalse(details.values.any { it.contains("Private") })
    }

    @Test
    fun playbackFailureDetailsExcludeExceptionMessages() {
        val secret = "private-media-error"
        val details =
            PlaybackFailure(
                stage = "audio_stream",
                message = secret,
                cause = IllegalStateException(secret),
            ).toPlaybackLogDetails()

        assertFalse(details.values.any { it.contains(secret) })
    }
}
