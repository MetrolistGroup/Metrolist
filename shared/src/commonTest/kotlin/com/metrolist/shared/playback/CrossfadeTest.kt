package com.metrolist.shared.playback

import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.LoudnessLevel
import kotlin.math.nextDown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossfadeTest {
    @Test
    fun overlappingPlayersRetainAudibleIncomingAudioThroughoutTheTransition() {
        assertEquals(1f to 0f, playbackCrossfadeGains(0f))
        assertEquals(0.5f to 0.5f, playbackCrossfadeGains(0.5f))
        assertEquals(0f to 1f, playbackCrossfadeGains(1f))
        for (step in 1 until 20) {
            val (outgoing, incoming) = playbackCrossfadeGains(step / 20f)
            assertTrue(outgoing > 0f && incoming > 0f)
            assertEquals(1f, outgoing + incoming, 0.00001f)
        }
    }

    @Test
    fun albumIdentityUsesIdsOrBothAlbumAndArtistIdentity() {
        val song = SongItem("one", "One", null, listOf(ArtistTiny("artist-a", "A")), AlbumTiny(null, "Greatest Hits"))
        assertTrue(songsShareAlbum(song, song.copy(id = "two")))
        assertFalse(songsShareAlbum(song, song.copy(id = "two", artists = listOf(ArtistTiny("artist-b", "B")))))
        assertFalse(songsShareAlbum(song.copy(artists = emptyList()), song.copy(artists = emptyList())))
        assertFalse(songsShareAlbum(song, song.copy(album = AlbumTiny("known", "Greatest Hits"))))
        assertTrue(songsShareAlbum(song.copy(album = AlbumTiny("known", "A")), song.copy(album = AlbumTiny("known", "B"))))
        assertFalse(songsShareAlbum(song.copy(album = AlbumTiny("first", "A")), song.copy(album = AlbumTiny("second", "A"))))
    }

    @Test
    fun progressFollowsMediaPositionAcrossPlaybackSpeeds() {
        assertEquals(0f, playbackCrossfadeProgress(175_000L, 180_000L, 175_000L))
        assertEquals(0.5f, playbackCrossfadeProgress(175_000L, 180_000L, 177_500L))
        assertEquals(1f, playbackCrossfadeProgress(175_000L, 180_000L, 180_000L))
    }

    @Test
    fun progressIsBoundedForLateAndInvalidWindows() {
        assertEquals(0f, playbackCrossfadeProgress(5_000L, 10_000L, 0L))
        assertEquals(1f, playbackCrossfadeProgress(5_000L, 10_000L, 15_000L))
        assertEquals(1f, playbackCrossfadeProgress(10_000L, 10_000L, 10_000L))
    }

    @Test
    fun outgoingEndCompletesTheCrossfadeWhenMediaPositionStopsBeforeTheDeclaredDuration() {
        val stoppedPosition = 179_984L
        val stalled = playbackCrossfadeProgress(175_000L, 180_000L, stoppedPosition)
        assertTrue(stalled < 1f)
        val completed = playbackCrossfadeProgress(175_000L, 180_000L, stoppedPosition, outgoingHasEnded = true)
        assertEquals(1f, completed)
        assertEquals(0f to 1f, playbackFadeThroughGains(completed))
    }

    @Test
    fun crossfadeWaitsUntilTheIncomingPlayerIsRendering() {
        assertFalse(shouldStartPlaybackCrossfade(9_000L, 10_000L, 2_000L, incomingIsRendering = false))
        assertTrue(shouldStartPlaybackCrossfade(9_000L, 10_000L, 2_000L, incomingIsRendering = true))
        assertFalse(shouldStartPlaybackCrossfade(7_000L, 10_000L, 2_000L, incomingIsRendering = true))
        assertFalse(shouldStartPlaybackCrossfade(0L, 10_000L, 2_000L, incomingIsRendering = true))
    }

    @Test
    fun crossfadeDurationHonorsGaplessPreferenceAndBothTrackLengths() {
        assertEquals(0L, playbackCrossfadeDurationMs(true, true, true, 10_000L, 180_000L, 180_000L))
        assertEquals(0L, playbackCrossfadeDurationMs(false, false, false, 10_000L, 180_000L, 180_000L))
        assertEquals(4_000L, playbackCrossfadeDurationMs(true, false, true, 10_000L, 4_000L, 12_000L))
        assertEquals(15_000L, playbackCrossfadeDurationMs(true, false, false, 30_000L, 90_000L, 90_000L))
    }

    @Test
    fun gainsStaySmoothAndIncomingReachesFullVolumeOnlyAtTheEnd() {
        assertEquals(1f to 0f, playbackFadeThroughGains(0f))
        for (invalidProgress in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            assertEquals(1f to 0f, playbackFadeThroughGains(invalidProgress))
        }
        val midpoint = playbackFadeThroughGains(0.5f)
        assertEquals(0f to 0f, midpoint)
        assertTrue(playbackFadeThroughGains(1f.nextDown()).second < 1f)
        assertEquals(0f to 1f, playbackFadeThroughGains(1f))
    }

    @Test
    fun gainsFadeOutThenInWithAGentleStopAtTheMidpoint() {
        val startStep = playbackFadeThroughGains(0.05f)
        val endStep = playbackFadeThroughGains(0.95f)
        assertTrue(startStep.second < 0.05f)
        assertTrue(endStep.first < 0.05f)
        assertEquals(0.5f, playbackFadeThroughGains(0.2475f).first, 0.00001f)
        assertEquals(0.5f, playbackFadeThroughGains(0.7525f).second, 0.00001f)
        var previousOutgoing = 1f
        var previousIncoming = 0f
        for (i in 1..20) {
            val (outgoing, incoming) = playbackFadeThroughGains(i / 20f)
            assertTrue(outgoing <= previousOutgoing)
            assertTrue(incoming >= previousIncoming)
            assertTrue(outgoing == 0f || incoming == 0f)
            previousOutgoing = outgoing
            previousIncoming = incoming
        }
    }

    @Test
    fun queuePromotionStartsAsSoonAsTheIncomingFadeBegins() {
        assertFalse(shouldPromotePlaybackCrossfade(0f))
        assertFalse(shouldPromotePlaybackCrossfade(Float.NaN))
        assertTrue(shouldPromotePlaybackCrossfade(Float.MIN_VALUE))
        assertTrue(shouldPromotePlaybackCrossfade(playbackFadeThroughGains(0.75f).second))
    }

    @Test
    fun requestedVolumeSleepFadeAndCrossfadeComposeWithoutOverwritingEachOther() {
        assertEquals(0.2f, playbackOutputGain(requestedVolume = 0.5f, sleepTimerGain = 0.8f, transitionGain = 0.5f))
        assertEquals(0.4f, playbackOutputGain(requestedVolume = 1f, sleepTimerGain = 0.8f, transitionGain = 0.5f))
    }

    @Test
    fun normalizationUsesLegacyTargetsAndAttenuationLimit() {
        val expected = listOf(1f, 0.6309573f, 0.4466836f, 0.2511886f)
        LoudnessLevel.entries.forEachIndexed { index, level ->
            assertEquals(expected[index], playbackNormalizationGain(0.0, true, level), absoluteTolerance = 0.000001f)
            assertEquals(0.17782794f, playbackNormalizationGain(100.0, true, level), absoluteTolerance = 0.000001f)
            assertEquals(1.4125376f, playbackNormalizationGain(-100.0, true, level), 0.000001f)
            assertEquals(1f, playbackNormalizationGain(6.0, false, level))
        }
    }

    @Test
    fun missingOrInvalidLoudnessUsesUnityWithoutLeakingPreviousGain() {
        for (loudness in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(1f, playbackNormalizationGain(loudness, true))
        }
        assertTrue(playbackNormalizationGain(6.0, true) < 1f)
        assertEquals(1f, playbackNormalizationGain(null, true))
        assertTrue(playbackNormalizationGain(Double.MAX_VALUE, true).isFinite())
        assertEquals(1.4125376f, playbackNormalizationGain(-Double.MAX_VALUE, true), 0.000001f)
    }

    @Test
    fun eachTrackKeepsItsGainAcrossCrossfadeEndpointsAndSleepFade() {
        val outgoing = playbackNormalizationGain(0.0, true)
        val incoming = playbackNormalizationGain(-6.0, true)
        val volume = 0.8f
        val sleep = 0.5f
        for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val (outFade, inFade) = playbackFadeThroughGains(progress)
            assertEquals(volume * sleep * outgoing * outFade, playbackOutputGain(volume, sleep, outFade, outgoing), 0.000001f)
            assertEquals(volume * sleep * incoming * inFade, playbackOutputGain(volume, sleep, inFade, incoming), 0.000001f)
        }
        assertEquals(
            playbackOutputGain(volume, sleep, 1f, incoming),
            playbackOutputGain(volume, sleep, playbackFadeThroughGains(1f).second, incoming),
        )
        assertEquals(0.3565004f, playbackOutputGain(0.4f, 1f, 1f, incoming), absoluteTolerance = 0.000001f)
    }
}
