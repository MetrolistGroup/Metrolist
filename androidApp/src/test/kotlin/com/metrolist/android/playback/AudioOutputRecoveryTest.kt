package com.metrolist.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputRecoveryTest {
    private val speaker = AudioOutputSignal(1, bluetooth = false)
    private val earbuds = AudioOutputSignal(2, bluetooth = true)

    @Test
    fun stoppingOrSelectingAnotherTrackCancelsDeferredUserRecoveryWithoutRearmingTheBudget() {
        val pending =
            AudioOutputRecoveryState(
                routeId = 2,
                bluetooth = true,
                attempted = true,
                lastAttemptMs = 0,
                sessionSuspect = true,
                pausedAtMs = 1_000,
                userRecoveryReason = "user_seek",
                a2dpNotPlayingSinceMs = 0,
            )
        val canceled = pending.cancelUserRecovery()
        assertEquals(null, canceled.pausedAtMs)
        assertEquals(null, canceled.userRecoveryReason)
        assertEquals(null, canceled.a2dpNotPlayingSinceMs)
        assertTrue(canceled.sessionSuspect)
        assertTrue(canceled.attempted)
        assertEquals(0L, canceled.lastAttemptMs)
        assertFalse(decideAudioOutputRecovery(canceled, null, true, 120_000).rebuild)
    }

    @Test
    fun longResumeRebuildsOnlySuspectBluetoothSessions() {
        for (suspect in listOf(false, true)) {
            for (playing in listOf(true, false, null)) {
                for (pauseMs in listOf(999L, 1_000L)) {
                    val initial = AudioOutputRecoveryState(routeId = 2, bluetooth = true, sessionSuspect = suspect)
                    val paused =
                        decideAudioOutputRecovery(initial, null, false, 0, playing, userAction = AudioOutputUserAction.PAUSE).state
                    val resumed =
                        decideAudioOutputRecovery(paused, null, true, pauseMs, playing, userAction = AudioOutputUserAction.RESUME)
                    assertEquals(pauseMs >= 1_000 && suspect, resumed.rebuild)
                    assertEquals(null, resumed.state.pausedAtMs)
                }
            }
        }
        val wired = AudioOutputRecoveryState(routeId = 1)
        val paused = decideAudioOutputRecovery(wired, null, false, 0, userAction = AudioOutputUserAction.PAUSE).state
        assertFalse(decideAudioOutputRecovery(paused, null, true, 10_000, userAction = AudioOutputUserAction.RESUME).rebuild)
    }

    @Test
    fun onlyActiveNotPlayingOrFailureEvidenceMakesTheSessionSuspect() {
        val initial = AudioOutputRecoveryState(routeId = 2, bluetooth = true)
        val paused = decideAudioOutputRecovery(initial, earbuds, false, 0, a2dpPlaying = false, a2dpStateChanged = true)
        assertFalse(paused.state.sessionSuspect)
        val briefStop = decideAudioOutputRecovery(initial, null, true, 0, a2dpPlaying = false, a2dpStateChanged = true)
        assertFalse(briefStop.rebuild)
        assertTrue(briefStop.state.sessionSuspect)
        val playingAgain = decideAudioOutputRecovery(briefStop.state, earbuds, true, 42, a2dpPlaying = true)
        assertTrue(playingAgain.state.sessionSuspect)
        val stalled = decideAudioOutputRecovery(initial, earbuds.copy(stalled = true), true, 0, a2dpPlaying = true)
        assertTrue(stalled.state.sessionSuspect)
    }

    @Test
    fun healthyOrUnobservableBluetoothSeeksDoNotRebuild() {
        val healthy = AudioOutputRecoveryState(routeId = 2, bluetooth = true)
        for (playing in listOf(true, null)) {
            val seek = decideAudioOutputRecovery(healthy, null, true, 0, playing, userAction = AudioOutputUserAction.SEEK)
            assertFalse(seek.rebuild)
            assertEquals(null, seek.state.userRecoveryReason)
        }
    }

    @Test
    fun seekDefersUntilPlayingAndManualRetriesRespectBackoffWithoutRearmingAutomaticRecovery() {
        val initial = AudioOutputRecoveryState(routeId = 2, bluetooth = true, sessionSuspect = true)
        val seeking = decideAudioOutputRecovery(initial, null, false, 0, userAction = AudioOutputUserAction.SEEK)
        assertEquals("user_seek", seeking.state.userRecoveryReason)
        val first = decideAudioOutputRecovery(seeking.state, null, true, 1_000)
        assertTrue(first.rebuild)
        assertEquals("user_seek", first.reason)
        val repeated = decideAudioOutputRecovery(first.state, null, true, 2_000, userAction = AudioOutputUserAction.SEEK)
        assertFalse(repeated.rebuild)
        assertFalse(decideAudioOutputRecovery(repeated.state, null, true, 60_999).rebuild)
        val retry = decideAudioOutputRecovery(repeated.state, null, true, 61_000)
        assertTrue(retry.rebuild)
        assertFalse(decideAudioOutputRecovery(retry.state, earbuds.copy(stalled = true), true, 500_000).rebuild)
    }

    @Test
    fun newAudioSessionClearsSuspicionAndUserRequestsButPreservesAutomaticBudgetAndBackoff() {
        val old =
            AudioOutputRecoveryState(
                routeId = 2,
                bluetooth = true,
                attempted = true,
                lastAttemptMs = 0,
                audioSessionId = 41,
                sessionSuspect = true,
                pausedAtMs = 1_000,
                userRecoveryReason = "user_seek",
                a2dpNotPlayingSinceMs = 0,
            )
        val replacement = decideAudioOutputRecovery(old, null, true, 120_000, a2dpPlaying = true, audioSessionId = 42)
        assertFalse(replacement.rebuild)
        assertFalse(replacement.state.sessionSuspect)
        assertEquals(null, replacement.state.pausedAtMs)
        assertEquals(null, replacement.state.userRecoveryReason)
        assertEquals(null, replacement.state.a2dpNotPlayingSinceMs)
        assertTrue(replacement.state.attempted)
        assertEquals(0L, replacement.state.lastAttemptMs)
        val paused =
            decideAudioOutputRecovery(replacement.state, null, false, 130_000, false, 42, AudioOutputUserAction.PAUSE).state
        assertFalse(decideAudioOutputRecovery(paused, null, true, 132_000, false, 42, AudioOutputUserAction.RESUME).rebuild)
    }

    @Test
    fun initialRouteAndHealthyOutputDoNotRebuild() {
        for (route in listOf(speaker, earbuds)) {
            var state = AudioOutputRecoveryState()
            for (second in 0L..120L) {
                val decision = decideAudioOutputRecovery(state, route, eligible = true, nowMs = second * 1_000)
                assertFalse(decision.rebuild)
                state = decision.state
            }
        }
    }

    @Test
    fun bluetoothRouteChangeRebuildsOnceIncludingAfterReplacementAndPause() {
        val initial = decideAudioOutputRecovery(AudioOutputRecoveryState(), speaker, true, 0).state
        val changed = decideAudioOutputRecovery(initial, earbuds, true, 1_000)
        assertTrue(changed.rebuild)
        var state = changed.state
        for (route in listOf(null, earbuds, null, earbuds.copy(stalled = true), earbuds)) {
            val decision = decideAudioOutputRecovery(state, route, true, 180_000)
            assertFalse(decision.rebuild)
            state = decision.state
        }
    }

    @Test
    fun replacementInitialRouteCannotRearmRecoveryEvenIfItsRouteDiffers() {
        val state = AudioOutputRecoveryState(routeId = 2, bluetooth = true, attempted = true, lastAttemptMs = 0)
        val initial = decideAudioOutputRecovery(state, earbuds.copy(routeId = 3, initialRoute = true), true, 120_000)
        assertFalse(initial.rebuild)
        assertTrue(initial.state.attempted)
        assertFalse(decideAudioOutputRecovery(initial.state, earbuds.copy(routeId = 3, stalled = true), true, 240_000).rebuild)
    }

    @Test
    fun transientStallDuringBufferingDoesNotLeavePendingRecoveryAfterOutputResumes() {
        val initial = decideAudioOutputRecovery(AudioOutputRecoveryState(), earbuds, true, 0).state
        val buffering = decideAudioOutputRecovery(initial, earbuds.copy(stalled = true), false, 5_000)
        assertFalse(buffering.rebuild)
        assertFalse(buffering.state.pending)
        assertFalse(decideAudioOutputRecovery(buffering.state, null, true, 6_000).rebuild)
        assertFalse(decideAudioOutputRecovery(buffering.state, earbuds, true, 7_000).rebuild)
    }

    @Test
    fun bufferingFocusSuppressionCrossfadeAndMutedOutputDeferPendingRouteRecovery() {
        val initial = decideAudioOutputRecovery(AudioOutputRecoveryState(), speaker, true, 0).state
        var state = decideAudioOutputRecovery(initial, earbuds, false, 1_000).state
        repeat(5) {
            val deferred = decideAudioOutputRecovery(state, null, eligible = false, nowMs = 10_000)
            assertFalse(deferred.rebuild)
            assertTrue(deferred.state.pending)
            state = deferred.state
        }
        assertTrue(decideAudioOutputRecovery(state, null, eligible = true, nowMs = 11_000).rebuild)
    }

    @Test
    fun changingRoutesDuringBackoffCannotRebuildUntilBackoffEnds() {
        val initial = decideAudioOutputRecovery(AudioOutputRecoveryState(), earbuds, true, 0).state
        val recovered = decideAudioOutputRecovery(initial, earbuds.copy(stalled = true), true, 1_000)
        assertTrue(recovered.rebuild)
        val disconnected = decideAudioOutputRecovery(recovered.state, speaker, true, 2_000)
        assertFalse(disconnected.rebuild)
        val reconnected = decideAudioOutputRecovery(disconnected.state, earbuds, true, 3_000)
        assertFalse(reconnected.rebuild)
        assertFalse(decideAudioOutputRecovery(reconnected.state, null, true, 60_999).rebuild)
        val retried = decideAudioOutputRecovery(reconnected.state, null, true, 61_000)
        assertTrue(retried.rebuild)
        assertFalse(decideAudioOutputRecovery(retried.state, earbuds.copy(stalled = true), true, 900_000).rebuild)
    }

    @Test
    fun stalledPcmRebuildsOnlyBluetoothAndOnlyOncePerRoute() {
        for (route in listOf(speaker, earbuds)) {
            val initial = decideAudioOutputRecovery(AudioOutputRecoveryState(), route, true, 0).state
            val stalled = decideAudioOutputRecovery(initial, route.copy(stalled = true), true, 5_000)
            assertEquals(route.bluetooth, stalled.rebuild)
            assertFalse(decideAudioOutputRecovery(stalled.state, route.copy(stalled = true), true, 500_000).rebuild)
        }
    }

    @Test
    fun timestampStallRequiresQueuedPcmAndResetsOnProgressPauseFlushAndUnavailableTimestamp() {
        val probe = PcmOutputStallProbe()
        assertFalse(probe.observe(0, 100, 1_000, true))
        assertFalse(probe.observe(3_999, 100, 2_000, true))
        assertTrue(probe.observe(4_000, 100, 3_000, true))
        assertFalse(probe.observe(5_000, 200, 4_000, true))
        assertFalse(probe.observe(6_000, 200, 4_000, false))
        assertFalse(probe.observe(10_000, 200, 4_000, true))
        assertFalse(probe.observe(11_000, null, 4_000, true))
        assertFalse(probe.observe(15_000, 200, 4_000, true))
        probe.reset()
        assertFalse(probe.observe(20_000, 0, 0, true))
        assertFalse(probe.observe(25_000, 0, 0, true))
        assertFalse(probe.observe(30_000, 0, 4_000, true))
        assertTrue(probe.observe(34_000, 0, 4_000, true))
    }

    @Test
    fun advancingTimestampDoesNotMistakeSilentContentOrUnsignedWrapForStalledOutput() {
        val probe = PcmOutputStallProbe()
        assertFalse(probe.observe(0, 0xffffff00L, 0x100001000L, true))
        assertFalse(probe.observe(5_000, 0x100000100L, 0x100002000L, true))
        for (second in 6L..120L) {
            assertFalse(probe.observe(second * 1_000, second * 48_000, (second + 1) * 48_000, true))
        }
    }

    @Test
    fun a2dpStopRequiresFiveSecondsOfAcceptedPcmAndUsesTheRouteBudget() {
        val pcm = earbuds.copy(pcmAccepted = true)
        var state = AudioOutputRecoveryState()
        for (time in listOf(0L, 42L, 1_000L, 4_999L)) {
            val decision = decideAudioOutputRecovery(state, pcm, true, time, a2dpPlaying = false)
            assertFalse(decision.rebuild)
            state = decision.state
        }
        val stopped = decideAudioOutputRecovery(state, pcm, true, 5_000, a2dpPlaying = false)
        assertTrue(stopped.rebuild)
        assertEquals("a2dp_not_playing", stopped.reason)
        assertFalse(decideAudioOutputRecovery(stopped.state, pcm, true, 500_000, a2dpPlaying = false).rebuild)
    }

    @Test
    fun shortReconfigurationPauseBufferingMissingPermissionAndBackpressureResetA2dpTimer() {
        val pcm = earbuds.copy(pcmAccepted = true)
        val initial = decideAudioOutputRecovery(AudioOutputRecoveryState(), pcm, true, 0, a2dpPlaying = false).state
        for (
        (signal, eligible, playing) in
        listOf(
            Triple(pcm, true, true),
            Triple(pcm, true, null),
            Triple(pcm, false, false),
            Triple(earbuds, true, false),
            Triple(speaker, true, false),
        )
        ) {
            val interrupted = decideAudioOutputRecovery(initial, signal, eligible, 42, a2dpPlaying = playing)
            assertFalse(interrupted.rebuild)
            assertEquals(null, interrupted.state.a2dpNotPlayingSinceMs)
            val resumed = decideAudioOutputRecovery(interrupted.state, pcm.copy(initialRoute = true), true, 10_000, a2dpPlaying = false)
            assertFalse(resumed.rebuild)
        }
        val poll = decideAudioOutputRecovery(initial, null, true, 4_000, a2dpPlaying = false)
        assertEquals(0L, poll.state.a2dpNotPlayingSinceMs)
        assertFalse(poll.rebuild)
    }
}
