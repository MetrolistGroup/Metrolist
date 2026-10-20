package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamClientFailureMemoryTest {
    @Test
    fun `failed client stays excluded for the following tracks`() {
        val memory = StreamClientFailureMemory(ttlMs = 60_000L)
        memory.mark("VISIONOS", nowMs = 1_000L)

        assertEquals(setOf("VISIONOS"), memory.recent(nowMs = 1_001L))
        assertEquals(setOf("VISIONOS"), memory.recent(nowMs = 60_999L))
    }

    @Test
    fun `expired failure is retried again and clear resets everything`() {
        val memory = StreamClientFailureMemory(ttlMs = 60_000L)
        memory.mark("VISIONOS", nowMs = 1_000L)
        assertTrue(memory.recent(nowMs = 61_001L).isEmpty())

        memory.mark("ANDROID_VR", nowMs = 100_000L)
        assertEquals(setOf("ANDROID_VR"), memory.recent(nowMs = 100_001L))
        memory.clear()
        assertTrue(memory.recent(nowMs = 100_002L).isEmpty())
    }
}
