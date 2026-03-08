package com.metrolist.android.playback

import androidx.media3.common.Player
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrFormatId
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SabrCachePolicyTest {
    @Test
    fun currentSabrTrackIsDeferredUntilCompletionIncludingSingleSongQueues() {
        val resource = sabrResource("current")
        for (state in listOf(Player.STATE_BUFFERING, Player.STATE_READY)) {
            assertEquals(setOf("current"), activeSabrCacheTargets(resource, "current", state, null, null))
        }
        for (state in listOf(Player.STATE_ENDED, Player.STATE_IDLE)) {
            assertTrue(activeSabrCacheTargets(resource, "current", state, null, null).isEmpty())
        }
    }

    @Test
    fun preloadedSabrTrackAndItsQueueIdentityStayDeferredWhenCurrentTrackEnds() {
        assertEquals(
            setOf("counterpart", "next"),
            activeSabrCacheTargets(sabrResource("current"), "current", Player.STATE_ENDED, sabrResource("counterpart"), "next"),
        )
    }

    @Test
    fun ownershipChangesReleasePreviousTracksWithoutExcludingTheNewCurrentTrack() {
        assertEquals(setOf("next"), activeSabrCacheTargets(sabrResource("next"), "next", Player.STATE_READY, null, null))
        val direct = PlaybackResource("current", "https://example.com/audio", emptyMap(), null, null)
        assertTrue(activeSabrCacheTargets(direct, "current", Player.STATE_READY, direct.copy(videoId = "next"), "next").isEmpty())
    }

    @Test
    fun counterpartsRemainOwnedUnderBothQueueAndStreamIds() {
        assertEquals(
            setOf("queue", "resolved", "counterpart"),
            activeSabrCacheTargets(sabrResource("resolved").copy(musicVideoId = "counterpart"), "queue", Player.STATE_READY, null, null),
        )
    }

    @Test
    fun deferredWriteSurvivesOwnershipChangesFailuresAndCancellationUntilStored() =
        runTest {
            val song = SongItem("song", "Song", null, emptyList(), null)
            val deferred = linkedMapOf<String, SongItem>()
            var active = setOf(song.id)
            var resolutions = 0
            var writes = 0
            var takeOwnershipDuringResolve = false
            var succeeds = false
            var cancel = false

            suspend fun attempt() =
                cacheWhenSabrReleased(
                    song,
                    deferred,
                    { active },
                    resolve = {
                        resolutions++
                        if (takeOwnershipDuringResolve) active = setOf(song.id)
                        sabrResource(song.id)
                    },
                    store = {
                        writes++
                        if (cancel) throw CancellationException()
                        succeeds
                    },
                )
            attempt()
            assertEquals(0, resolutions)
            assertEquals(listOf(song), deferred.values.toList())
            active = emptySet()
            takeOwnershipDuringResolve = true
            attempt()
            assertEquals(0, writes)
            active = emptySet()
            takeOwnershipDuringResolve = false
            attempt()
            assertTrue(deferred.containsKey(song.id))
            cancel = true
            try {
                attempt()
                throw AssertionError("Cancellation must propagate")
            } catch (_: CancellationException) {
                assertTrue(deferred.containsKey(song.id))
            }
            cancel = false
            succeeds = true
            attempt()
            assertEquals(3, writes)
            assertFalse(deferred.containsKey(song.id))
        }

    private fun sabrResource(id: String): PlaybackResource =
        PlaybackResource(
            videoId = id,
            url = "sabr://$id",
            headers = emptyMap(),
            loudnessDb = null,
            expiresAt = null,
            sabrBootstrap =
                SabrBootstrap(
                    videoId = id,
                    serverAbrStreamingUrl = "https://example.com/sabr",
                    videoPlaybackUstreamerConfig = byteArrayOf(1),
                    clientName = 1,
                    clientVersion = "test",
                    audioFormat = SabrFormatId(251),
                    discardVideoFormat = SabrFormatId(136),
                    discardVideoHeight = 720,
                    durationMs = 30_000L,
                    contentLengthBytes = 1_000L,
                    mimeType = "audio/webm",
                ),
        )
}
