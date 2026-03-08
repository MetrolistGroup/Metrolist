package com.metrolist.desktop.state

import com.metrolist.desktop.state.playback.AttestationRecoveryScope
import com.metrolist.desktop.state.playback.DesktopAttestationRecoveryTracker
import com.metrolist.desktop.state.playback.canUseDesktopVideoResource
import com.metrolist.desktop.state.playback.counterpartPlaybackPosition
import com.metrolist.desktop.state.playback.desktopPlaybackSabrFirst
import com.metrolist.desktop.state.playback.desktopStreamRecoveryHints
import com.metrolist.desktop.state.playback.directPlaybackQueue
import com.metrolist.desktop.state.playback.hasFreshVideoResource
import com.metrolist.desktop.state.playback.isCurrentDesktopVideoLoad
import com.metrolist.desktop.state.playback.isTokenlessSabrAttestationFailure
import com.metrolist.desktop.state.playback.originalAudioResource
import com.metrolist.desktop.state.playback.shouldCommitVideoCounterpartResolution
import com.metrolist.desktop.state.playback.shouldRecoverStalledVideo
import com.metrolist.desktop.state.playback.shouldRequestDesktopVideo
import com.metrolist.desktop.state.playback.shouldResolveOriginalAudio
import com.metrolist.desktop.state.playback.shouldSwitchToCounterpartAudio
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.strategy.ClientSelectionRequest
import com.metrolist.innertubex.extraction.strategy.ContentAwareFallbackStrategy
import com.metrolist.innertubex.extraction.strategy.PlaybackClientCatalog
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrFormatId
import com.metrolist.shared.api.musicvideo.CounterpartPositionMap
import com.metrolist.shared.api.musicvideo.CounterpartSegment
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_VIDEO
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.PlaybackFailureCategory
import com.metrolist.shared.playback.PlaybackRecoveryAction
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DesktopVideoPlaybackPolicyTest {
    @Test
    fun expiredUrlKeepsTheSelectedClientDuringRenewal() {
        val hints = ContentHints(playbackClientOverrideId = "WEB_REMIX", isExplicit = true)
        val resource = PlaybackResource("song", "https://example.test/expired", emptyMap(), null, null, profileId = "WEB_REMIX__po")
        assertEquals(
            hints,
            desktopStreamRecoveryHints(hints, resource, PlaybackFailureCategory.URL_EXPIRED, PlaybackRecoveryAction.REFRESH_URL),
        )
    }

    @Test
    fun automaticSabrPreferenceSurvivesDesktopResolutionUnlessDirectIsForced() {
        assertTrue(desktopPlaybackSabrFirst(ContentHints(sabrFirst = true), forceDirect = false, PlaybackClientCatalog.AUTOMATIC_ID))
        assertTrue(desktopPlaybackSabrFirst(ContentHints(), forceDirect = false, PlaybackClientCatalog.SABR_FIRST_ID))
        assertFalse(desktopPlaybackSabrFirst(ContentHints(), forceDirect = false, PlaybackClientCatalog.AUTOMATIC_ID))
        assertFalse(desktopPlaybackSabrFirst(ContentHints(sabrFirst = true), forceDirect = true, PlaybackClientCatalog.AUTOMATIC_ID))
    }

    @Test
    fun retainedVideoMustRemainValidBeyondTheHandoffWindow() {
        val now = Instant.fromEpochMilliseconds(1_000_000L)

        fun resource(expiresAt: Instant?) = PlaybackResource("song", "audio", emptyMap(), null, expiresAt, videoUrl = "video")

        assertTrue(hasFreshVideoResource(resource(null), now))
        assertTrue(hasFreshVideoResource(resource(now + 61.seconds), now))
        assertFalse(hasFreshVideoResource(resource(now + 60.seconds), now))
        assertFalse(hasFreshVideoResource(resource(now - 1.seconds), now))
        assertFalse(hasFreshVideoResource(resource(null).copy(videoUrl = null), now))
    }

    @Test
    fun counterpartAudioSwitchMapsVideoIntroInBothDirections() {
        val map = CounterpartPositionMap(listOf(CounterpartSegment(sourceStartMs = 0L, targetStartMs = 12_000L, durationMs = 180_000L)))

        assertEquals(42_000L, counterpartPlaybackPosition(30_000L, map, switchToVideo = true))
        assertEquals(30_000L, counterpartPlaybackPosition(42_000L, map, switchToVideo = false))
        assertEquals(30_000L, counterpartPlaybackPosition(30_000L, null, switchToVideo = true))
    }

    @Test
    fun cancelledOrReplacedVideoLoadCannotStartItsDecoder() {
        fun canStart(
            generation: Long = 5L,
            instanceId: Long = 10L,
            songId: String? = "song",
            jobActive: Boolean = true,
            viewerOpen: Boolean = true,
        ) = isCurrentDesktopVideoLoad(5L, generation, 10L, instanceId, "song", songId, jobActive, viewerOpen)

        assertTrue(canStart())
        assertFalse(canStart(generation = 6L))
        assertFalse(canStart(instanceId = 11L))
        assertFalse(canStart(songId = "next"))
        assertFalse(canStart(songId = null))
        assertFalse(canStart(jobActive = false))
        assertFalse(canStart(viewerOpen = false))
    }

    @Test
    fun lateLyricsCounterpartCannotOverwriteANewPlayback() {
        fun canCommit(
            song: String?,
            generation: Long,
            instance: Long,
        ) = shouldCommitVideoCounterpartResolution("old", song, 5L, generation, 10L, instance)

        assertTrue(canCommit("old", 5L, 10L))
        assertFalse(canCommit("next", 5L, 10L))
        assertFalse(canCommit("old", 6L, 10L))
        assertFalse(canCommit("old", 5L, 11L))
    }

    @Test
    fun onlyCounterpartResourcesReplaceTheOriginalAudioSource() {
        fun resource(musicVideoId: String?) = PlaybackResource("song", "audio", emptyMap(), null, null, musicVideoId = musicVideoId)

        val original = resource(null)
        assertTrue(shouldSwitchToCounterpartAudio("song", resource("video")))
        assertFalse(shouldSwitchToCounterpartAudio("song", original))
        assertFalse(shouldSwitchToCounterpartAudio("other", resource("video")))
        assertSame(original, originalAudioResource("song", original))
        assertNull(originalAudioResource("other", original))
        assertTrue(shouldResolveOriginalAudio("song", resource("video"), retainedSongId = null))
        assertTrue(shouldResolveOriginalAudio("song", resource("video"), retainedSongId = "previous"))
        assertFalse(shouldResolveOriginalAudio("song", resource("video"), retainedSongId = "song"))
    }

    @Test
    fun attestationRefreshFallsBackOnlyWithinTheSamePlaybackSessionAndWindow() {
        val tracker = DesktopAttestationRecoveryTracker(retryWindowMs = 100L)
        val scope = AttestationRecoveryScope(1L, 2L, "video", "WEB_SABR__po")

        assertEquals(
            PlaybackRecoveryAction.REFRESH_TOKEN,
            tracker.action(
                PlaybackFailureCategory.SABR_ATTESTATION,
                scope,
                nowMs = 0L,
                defaultAction = PlaybackRecoveryAction.REFRESH_TOKEN,
            ),
        )
        tracker.recordRefresh(scope, nowMs = 0L)
        assertEquals(
            PlaybackRecoveryAction.RETRY_CLIENT,
            tracker.action(
                PlaybackFailureCategory.SABR_ATTESTATION,
                scope,
                nowMs = 99L,
                defaultAction = PlaybackRecoveryAction.REFRESH_TOKEN,
            ),
        )
        val nextPlayback = scope.copy(playbackGeneration = 2L)
        assertEquals(
            PlaybackRecoveryAction.REFRESH_TOKEN,
            tracker.action(
                PlaybackFailureCategory.SABR_ATTESTATION,
                nextPlayback,
                nowMs = 99L,
                defaultAction = PlaybackRecoveryAction.REFRESH_TOKEN,
            ),
        )
        assertEquals(0, tracker.trackedRefreshCount)
        tracker.recordRefresh(nextPlayback, nowMs = 99L)

        val nextSession = nextPlayback.copy(sessionGeneration = 3L)
        assertEquals(
            PlaybackRecoveryAction.REFRESH_TOKEN,
            tracker.action(
                PlaybackFailureCategory.SABR_ATTESTATION,
                nextSession,
                nowMs = 99L,
                defaultAction = PlaybackRecoveryAction.REFRESH_TOKEN,
            ),
        )
        assertEquals(0, tracker.trackedRefreshCount)
        tracker.recordRefresh(nextSession, nowMs = 99L)

        assertEquals(
            PlaybackRecoveryAction.REFRESH_TOKEN,
            tracker.action(
                PlaybackFailureCategory.SABR_ATTESTATION,
                nextSession.copy(profileId = "ANDROID_VR_SABR__po"),
                nowMs = 99L,
                defaultAction = PlaybackRecoveryAction.REFRESH_TOKEN,
            ),
        )
        assertEquals(1, tracker.trackedRefreshCount)
        assertEquals(
            PlaybackRecoveryAction.REFRESH_TOKEN,
            tracker.action(
                PlaybackFailureCategory.SABR_ATTESTATION,
                nextSession,
                nowMs = 199L,
                defaultAction = PlaybackRecoveryAction.REFRESH_TOKEN,
            ),
        )
        assertEquals(0, tracker.trackedRefreshCount)
    }

    @Test
    fun tokenlessAttestationRecoveryExcludesTheFailedProfileAndAllowsAttestedSabr() {
        val bootstrap =
            SabrBootstrap(
                videoId = "video",
                serverAbrStreamingUrl = "https://example.com/sabr",
                videoPlaybackUstreamerConfig = byteArrayOf(1),
                clientName = 101,
                clientVersion = "1.02",
                audioFormat = SabrFormatId(251),
                discardVideoFormat = SabrFormatId(160),
                discardVideoHeight = 144,
                durationMs = 74_000,
                contentLengthBytes = null,
                mimeType = "audio/webm",
            )
        val resource =
            PlaybackResource(
                "video",
                "sabr://video",
                emptyMap(),
                null,
                null,
                profileId = "VISIONOS_SABR__nopo",
                sabrBootstrap = bootstrap,
            )
        val category = PlaybackFailureCategory.SABR_ATTESTATION
        val action = PlaybackRecoveryAction.REFRESH_TOKEN
        val hints = ContentHints(playbackClientOverrideId = "VISIONOS_SABR", isExplicit = true)

        for (token in listOf(null, byteArrayOf())) {
            val tokenless = resource.copy(sabrBootstrap = bootstrap.copy(poToken = token))
            assertTrue(isTokenlessSabrAttestationFailure(category, tokenless))
            val retryHints = desktopStreamRecoveryHints(hints, tokenless, category, action)
            assertNull(retryHints.playbackClientOverrideId)
            assertTrue(retryHints.allowSabr)
            assertTrue(retryHints.isExplicit == true)
        }

        val tokenized = resource.copy(profileId = "WEB_SABR__po", sabrBootstrap = bootstrap.copy(poToken = byteArrayOf(1)))
        assertFalse(isTokenlessSabrAttestationFailure(category, tokenized))
        assertEquals("WEB_SABR", desktopStreamRecoveryHints(hints, tokenized, category, action).playbackClientOverrideId)
        assertFalse(isTokenlessSabrAttestationFailure(PlaybackFailureCategory.SABR_PROTOCOL, resource))
        assertFalse(isTokenlessSabrAttestationFailure(category, resource.copy(sabrBootstrap = null)))
        assertFalse(isTokenlessSabrAttestationFailure(category, null))
        assertTrue(desktopStreamRecoveryHints(hints, tokenized, category, PlaybackRecoveryAction.RETRY_CLIENT).allowSabr)
    }

    @Test
    fun repeatedAttestationFailureKeepsOtherSabrProfilesEligible() {
        val failed = PlaybackResource("video", "sabr://video", emptyMap(), null, null, profileId = "TVHTML5_SIMPLY_SABR__po")
        val hints = ContentHints(sabrFirst = true, playbackClientOverrideId = "TVHTML5_SIMPLY_SABR")
        val retryHints =
            desktopStreamRecoveryHints(hints, failed, PlaybackFailureCategory.SABR_ATTESTATION, PlaybackRecoveryAction.RETRY_CLIENT)
        assertFalse(retryHints.sabrFirst)
        assertTrue(retryHints.allowSabr)
        assertNull(retryHints.playbackClientOverrideId)
        val candidates =
            ContentAwareFallbackStrategy()
                .selectClients(
                    ClientSelectionRequest(
                        hints = retryHints,
                        authenticated = true,
                        availablePoTokenProviders = setOf(PoTokenProviderKind.WEBPAGE_ATTESTATION),
                        javaScriptRuntimeAvailable = true,
                        webViewAvailable = true,
                        excludedClients = setOf(failed.profileId),
                    ),
                ).candidates
                .map { it.manifest?.id }
        assertFalse("TVHTML5_SIMPLY_SABR" in candidates)
        assertTrue("TVHTML5_SIMPLY" in candidates)
        assertTrue("WEB_SABR" in candidates)
        assertTrue("MWEB_SABR" in candidates)
        assertFalse(
            desktopStreamRecoveryHints(
                hints.withStreamCapabilities(allowSabr = false),
                failed,
                PlaybackFailureCategory.SABR_ATTESTATION,
                PlaybackRecoveryAction.RETRY_CLIENT,
            ).allowSabr,
        )
    }

    @Test
    fun stalledVideoRequestsRecoveryOnlyWhileAudioStillOwnsPlayback() {
        assertTrue(shouldRecoverStalledVideo(true, true, false, false, 1_000L, 10_001L, 8_000L))
        assertFalse(shouldRecoverStalledVideo(true, false, false, false, 1_000L, 10_001L, 8_000L))
        assertFalse(shouldRecoverStalledVideo(true, true, true, false, 1_000L, 10_001L, 8_000L))
        assertFalse(shouldRecoverStalledVideo(true, true, false, true, 1_000L, 10_001L, 8_000L))
        assertFalse(shouldRecoverStalledVideo(true, true, false, false, 2_000L, 10_000L, 8_000L))
    }

    @Test
    fun synchronizedSongDoesNotResolveACounterpartFromLocalViewerPreferences() {
        for (viewerOpen in listOf(false, true)) {
            for (fullscreen in listOf(false, true)) {
                assertFalse(shouldRequestDesktopVideo(viewerOpen, fullscreen, false, synchronizedPlayback = true))
                assertTrue(shouldRequestDesktopVideo(viewerOpen, fullscreen, true, synchronizedPlayback = true))
            }
        }
    }

    @Test
    fun synchronizedVideoRecoveryRejectsAnAudioSelectionAndHiddenCounterpartIdentity() {
        val video = SongItem("video", "Video", null, emptyList(), null, musicVideoType = MUSIC_VIDEO_TYPE_VIDEO)
        val resource = PlaybackResource(video.id, "https://example.test/audio", emptyMap(), null, null)
        assertTrue(canUseDesktopVideoResource(video, resource, synchronizedPlayback = true))
        assertFalse(canUseDesktopVideoResource(video.copy(musicVideoType = null), resource, synchronizedPlayback = true))
        assertFalse(canUseDesktopVideoResource(video, resource.copy(musicVideoId = "other"), synchronizedPlayback = true))
        assertFalse(canUseDesktopVideoResource(video.copy(id = "next-video"), resource, synchronizedPlayback = true))
    }

    @Test
    fun directPlaybackKeepsTheRequestedTrackDespiteContentFilters() {
        fun song(id: String) = SongItem(id = id, title = id, thumbnail = null, artists = emptyList(), album = null)

        val target = song("target")
        val allowed = song("allowed")
        val blocked = song("blocked")

        assertEquals(
            listOf(target, allowed),
            directPlaybackQueue(target, listOf(blocked, target, allowed, allowed)) { it.id != "allowed" },
        )
    }
}
