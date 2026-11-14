package com.metrolist.shared.discord

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscordConnectionGenerationTest {
    @Test
    fun newerConnectionInvalidatesOlderCallbacks() {
        val generation = DiscordConnectionGeneration()
        val first = generation.next()

        assertTrue(generation.isCurrent(first))
        val second = generation.next()

        assertFalse(generation.isCurrent(first))
        assertTrue(generation.isCurrent(second))
    }

    @Test
    fun reconnectBackoffSaturatesAndResetsAfterReadiness() {
        val backoff = DiscordReconnectBackoff()

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 64_000L, 64_000L), List(8) { backoff.nextDelayMs(false) })
        assertEquals(60_000L, backoff.nextDelayMs(true))

        backoff.reset()
        assertEquals(1_000L, backoff.nextDelayMs(false))
    }
}
