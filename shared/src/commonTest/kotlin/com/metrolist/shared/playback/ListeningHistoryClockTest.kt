package com.metrolist.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class ListeningHistoryClockTest {
    @Test
    fun excludedIntervalsCannotQualifyAListenAndAllowedTimeSurvivesPauses() {
        var now = 0L
        val clock = ListeningHistoryClock { now }
        clock.update(playing = true, historyPaused = true)
        now += 60_000
        clock.update(playing = true, historyPaused = false)
        now += 1_000
        assertEquals(1_000L, clock.elapsedMs())
        clock.update(playing = false, historyPaused = false)
        now += 60_000
        clock.update(playing = true, historyPaused = false)
        now += 20_000
        clock.update(playing = true, historyPaused = true)
        now += 60_000
        clock.update(playing = false, historyPaused = false)
        now += 60_000
        assertEquals(21_000L, clock.elapsedMs())
        clock.update(playing = true, historyPaused = false)
        now += 10_000
        assertEquals(31_000L, clock.elapsedMs())
    }

    @Test
    fun bothCrossfadeSessionsExcludeTheSamePrivacyInterval() {
        var now = 0L
        val outgoing = ListeningHistoryClock { now }
        val incoming = ListeningHistoryClock { now }
        outgoing.update(true, false)
        now += 10_000
        incoming.update(true, false)
        now += 2_000
        listOf(outgoing, incoming).forEach { it.update(true, true) }
        now += 60_000
        listOf(outgoing, incoming).forEach { it.update(true, false) }
        now += 3_000
        assertEquals(15_000L, outgoing.elapsedMs())
        assertEquals(5_000L, incoming.elapsedMs())
    }
}
