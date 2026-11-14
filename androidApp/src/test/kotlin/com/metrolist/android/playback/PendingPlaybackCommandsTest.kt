package com.metrolist.android.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPlaybackCommandsTest {
    @Test
    fun firstPlayAndFollowingCommandsWaitForRestoreAndRunOnceInOrder() {
        val pending = PendingPlaybackCommands()
        val events = mutableListOf<String>()
        assertTrue(pending.defer { events += "play" })
        assertTrue(pending.defer { events += "seek" })
        assertTrue(pending.defer { events += "pause" })
        assertTrue(events.isEmpty())
        pending.connected()
        pending.connected()
        assertEquals(listOf("play", "seek", "pause"), events)
        assertFalse(pending.defer { error("Connected commands execute at the call site") })
    }

    @Test
    fun disconnectDuringDrainRetainsRemainingCommandsUntilReconnection() {
        val pending = PendingPlaybackCommands()
        val events = mutableListOf<String>()
        pending.defer {
            events += "first"
            pending.disconnected()
        }
        pending.defer { events += "second" }
        pending.connected()
        assertEquals(listOf("first"), events)
        pending.connected()
        assertEquals(listOf("first", "second"), events)
    }

    @Test
    fun failedBindingDoesNotReplayAbandonedCommandsOnTheNextAttempt() {
        val pending = PendingPlaybackCommands()
        val events = mutableListOf<String>()
        pending.defer { events += "abandoned" }
        pending.clear()
        pending.defer { events += "retry" }
        pending.connected()
        assertEquals(listOf("retry"), events)
    }
}
