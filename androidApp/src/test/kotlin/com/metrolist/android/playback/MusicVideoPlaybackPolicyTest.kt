package com.metrolist.android.playback

import com.metrolist.innertubex.sabr.SabrFormatId
import com.metrolist.innertubex.sabr.SabrVideoVariant
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isVideoSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicVideoPlaybackPolicyTest {
    @Test
    fun replacementClosesPersistentGapAfterFirstSeekAtDoubleSpeed() {
        val sourceSpeed = 2f
        var sourcePosition = 12_000L
        var replacementPosition = 10_000L
        repeat(100) {
            val action = warmReplacementAction(sourcePosition, replacementPosition, true, true, true, true, 1)
            if (action == WarmReplacementAction.COMMIT) return
            assertEquals(WarmReplacementAction.CATCH_UP, action)
            sourcePosition += (50 * sourceSpeed).toLong()
            replacementPosition += (50 * warmReplacementCatchUpSpeed(sourceSpeed)).toLong()
        }
        error("Replacement failed to catch up within five seconds")
    }

    @Test
    fun returningFromCounterpartVideoSelectsTheExactOriginalRecording() {
        val alternate = SongItem(id = "alternate-audio", title = "Song", thumbnail = null, artists = emptyList(), album = null)
        val original = alternate.copy(id = "original-audio")

        assertEquals(original, originalAudioForVideo("video", "video", original))
        assertEquals(null, originalAudioForVideo("other-video", "video", original))
    }

    @Test
    fun autoSabrQualityDropsOnStallAndClimbsWithHeadroom() {
        val variants =
            listOf(360 to 400_000, 480 to 800_000, 720 to 1_500_000, 1080 to 3_000_000).map { (height, bitrate) ->
                SabrVideoVariant(SabrFormatId(height), height * 16 / 9, height, bitrate, "video/mp4", null)
            }

        fun next(
            bandwidthBps: Long?,
            stalled: Boolean = false,
            allowUpswitch: Boolean = true,
            currentHeight: Int = 480,
        ) = nextAutoSabrVideoVariant(variants, currentHeight, 720, bandwidthBps, 128_000, stalled, allowUpswitch)?.height

        assertEquals(360, next(50_000_000L, stalled = true))
        assertEquals(360, next(50_000_000L, stalled = true, currentHeight = 720))
        assertEquals(null, next(100_000L))
        assertEquals(null, next(2_000_000L))
        assertEquals(720, next(3_000_000L))
        assertEquals(null, next(3_000_000L, allowUpswitch = false))
        assertEquals(null, next(50_000_000L, currentHeight = 720))
        assertEquals(null, next(50_000_000L, stalled = true, currentHeight = 360))
        assertEquals(null, next(50_000_000L, currentHeight = 1440))
        assertEquals(null, next(null))
        assertEquals(360, next(null, stalled = true))
    }

    @Test
    fun previousRestartsAfterFiveSecondsOrAtStartOfQueue() {
        assertFalse(shouldRestartCurrentItem(playbackPositionMs = 4_999L, currentQueueIndex = 1))
        assertTrue(shouldRestartCurrentItem(playbackPositionMs = 5_000L, currentQueueIndex = 1))
        assertTrue(shouldRestartCurrentItem(playbackPositionMs = 0L, currentQueueIndex = 0))
    }

    @Test
    fun disabledVideoPlaybackKeepsVideoSongsPlayableAsAudio() {
        assertFalse(shouldRequestQueueVideo(isVideoSong = true, videoPlaybackEnabled = false))
        assertFalse(shouldRequestQueueVideo(isVideoSong = false, isVideoModeActive = true, videoPlaybackEnabled = false))
        assertTrue(
            isQueuePlaybackModeCurrent(
                SongItem(id = "video", title = "Video", thumbnail = null, artists = emptyList(), album = null),
                requestedWantVideo = false,
                currentVideoViewerVisible = true,
                videoPlaybackEnabled = false,
            ),
        )
    }

    @Test
    fun everyVideoRowFollowsTheCurrentModeRegardlessOfVideoType() {
        for (type in listOf("MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_UGC", "MUSIC_VIDEO_TYPE_VIDEO", "MUSIC_VIDEO_TYPE_LIVE")) {
            val video = SongItem("video", "Video", null, emptyList(), null, musicVideoType = type)
            for (videoMode in listOf(false, true)) {
                val wantVideo = shouldRequestQueueVideo(isVideoSong = video.isVideoSong, isVideoModeActive = videoMode)
                assertEquals(videoMode, wantVideo)
                assertEquals(!videoMode, shouldResolveMusicTrackCounterpart(sourceIsVideoSong = video.isVideoSong, wantVideo = wantVideo))
            }
        }
        assertFalse(shouldRequestQueueVideo(isVideoSong = false, isVideoModeActive = false))
    }

    @Test
    fun synchronizedVideoSelectionWithoutLocalModePreferenceKeepsVideo() {
        assertTrue(shouldRequestQueueVideo(isVideoSong = true))
        assertFalse(shouldRequestQueueVideo(isVideoSong = false))
    }

    @Test
    fun videoModeActiveRequestsVideoForStandardSongs() {
        assertTrue(
            shouldRequestQueueVideo(
                isVideoSong = false,
                isVideoModeActive = true,
            ),
        )
        assertFalse(
            shouldRequestQueueVideo(
                isVideoSong = false,
                isVideoModeActive = true,
                isLocal = true,
            ),
        )
    }

    @Test
    fun podcastVideoIsRequestedOnlyWhenTheUserAsksForIt() {
        assertFalse(shouldRequestQueueVideo(isVideoSong = false, isPodcast = true))
        assertTrue(shouldRequestQueueVideo(isVideoSong = false, isPodcast = true, isVideoModeActive = true))
        assertFalse(shouldRequestQueueVideo(isVideoSong = false, isPodcast = true, isVideoModeActive = false))
    }

    @Test
    fun queuePreparationBecomesStaleWhenTheRequestedVideoModeChanges() {
        val videoSong =
            SongItem(
                id = "video",
                title = "Video",
                thumbnail = null,
                artists = listOf(ArtistTiny(null, "Artist")),
                album = null,
                musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
            )

        assertFalse(isQueuePlaybackModeCurrent(videoSong, requestedWantVideo = false, currentVideoViewerVisible = true))
        assertTrue(isQueuePlaybackModeCurrent(videoSong, requestedWantVideo = true, currentVideoViewerVisible = true))
        assertFalse(isQueuePlaybackModeCurrent(videoSong, requestedWantVideo = true, currentVideoViewerVisible = false))
        assertTrue(isQueuePlaybackModeCurrent(videoSong, requestedWantVideo = false, currentVideoViewerVisible = false))
        assertTrue(
            isQueuePlaybackModeCurrent(
                videoSong.copy(isLocal = true),
                requestedWantVideo = false,
                currentVideoViewerVisible = true,
            ),
        )
    }

    @Test
    fun videoModeTargetRejectsStalePlaybackAndQueueRequests() {
        val target = AndroidVideoModeController.PlaybackTarget("source", playbackInstanceId = 7L, queueRequestId = 19L)

        assertTrue(target.isCurrent(3L, 3L, 7L, 19L, "source"))
        assertFalse(target.isCurrent(3L, 4L, 7L, 19L, "source"))
        assertFalse(target.isCurrent(3L, 3L, 8L, 19L, "source"))
        assertFalse(target.isCurrent(3L, 3L, 7L, 20L, "source"))
        assertFalse(target.isCurrent(3L, 3L, 7L, 19L, "counterpart"))
    }

    @Test
    fun warmedReplacementCatchesUpWithCurrentPlayback() {
        assertEquals(
            14_500L,
            catchUpPlaybackPosition(
                requestedPositionMs = 12_000L,
                sourcePositionAtPrepareMs = 8_000L,
                sourcePositionNowMs = 10_500L,
            ),
        )
        assertEquals(0L, catchUpPlaybackPosition(1_000L, 10_000L, 0L))
    }

    @Test
    fun warmedReplacementAlignsItsActualClockBeforeHandoff() {
        assertTrue(shouldRetargetWarmedReplacement(7_999L, 10_000L, isPlaying = true))
        assertTrue(shouldRetargetWarmedReplacement(12_001L, 10_000L, isPlaying = true))
        assertFalse(shouldRetargetWarmedReplacement(10_100L, 10_000L, isPlaying = true))
        assertTrue(shouldRetargetWarmedReplacement(10_101L, 10_000L, isPlaying = true))
        assertTrue(shouldRetargetWarmedReplacement(10_001L, 10_000L, isPlaying = false))
        assertFalse(shouldRetargetWarmedReplacement(30_000L, 30_000L, isPlaying = false))
    }

    @Test
    fun warmHandoffCatchesUpAfterSlowRemoteSeekWithoutSeekingAgain() {
        fun action(
            source: Long,
            candidate: Long,
            retargets: Int,
            ready: Boolean = true,
            buffered: Boolean = true,
            candidatePlaying: Boolean = true,
        ) = warmReplacementAction(source, candidate, true, ready, candidatePlaying, buffered, retargets)

        assertEquals(WarmReplacementAction.RETARGET, action(12_000L, 10_000L, 0))
        assertEquals(WarmReplacementAction.WAIT, action(12_500L, 12_000L, 1, ready = false))
        assertEquals(WarmReplacementAction.CATCH_UP, action(12_500L, 12_000L, 1))
        assertEquals(WarmReplacementAction.CATCH_UP, action(13_000L, 12_750L, 1))
        assertEquals(WarmReplacementAction.WAIT, action(13_050L, 13_000L, 1, buffered = false))
        assertEquals(WarmReplacementAction.COMMIT, action(13_050L, 13_000L, 1))
        assertEquals(WarmReplacementAction.CATCH_UP, action(13_400L, 13_000L, 1))
        assertEquals(WarmReplacementAction.WAIT_FOR_SOURCE, action(13_000L, 13_400L, 1))
        assertEquals(WarmReplacementAction.WAIT, action(13_400L, 13_400L, 1, candidatePlaying = false))
        assertEquals(WarmReplacementAction.RETARGET, warmReplacementAction(500L, 499L, false, true, false, true, 0))
        assertEquals(WarmReplacementAction.FAILED, warmReplacementAction(500L, 499L, false, true, false, true, 3))
        assertEquals(WarmReplacementAction.WAIT, warmReplacementAction(120_000L, 12_000L, true, false, false, false, 1, true))
        assertEquals(WarmReplacementAction.RETARGET, warmReplacementAction(120_000L, 12_000L, true, true, true, true, 1, true))
        assertEquals(WarmReplacementAction.RETARGET, warmReplacementAction(12_000L, 120_000L, true, true, true, true, 2, true))
        assertEquals(WarmReplacementAction.FAILED, warmReplacementAction(120_000L, 12_000L, true, true, true, true, 3, true))
    }
}
