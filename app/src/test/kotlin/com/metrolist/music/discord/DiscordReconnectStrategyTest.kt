package com.metrolist.music.discord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordReconnectStrategyTest {

    @Test
    fun resume_when4000_andHadSession() {
        val action = DiscordReconnectStrategy.decide(
            closeCode = 4000,
            hadSession = true,
            seq = 42,
            sessionId = "session-abc",
        )
        assertTrue(action is ReconnectAction.Resume)
        val resume = action as ReconnectAction.Resume
        assertEquals("session-abc", resume.sessionId)
        assertEquals(42, resume.seq)
    }

    @Test
    fun reIdentify_when4000_andNoSession() {
        val action = DiscordReconnectStrategy.decide(
            closeCode = 4000,
            hadSession = false,
            seq = 0,
            sessionId = null,
        )
        assertEquals(ReconnectAction.ReIdentify, action)
    }

    @Test
    fun reIdentify_when4000_andSeqZero_evenWithSession() {
        val action = DiscordReconnectStrategy.decide(
            closeCode = 4000,
            hadSession = true,
            seq = 0,
            sessionId = "session-abc",
        )
        assertEquals(
            "seq=0 means no dispatches received; resume would fail",
            ReconnectAction.ReIdentify,
            action,
        )
    }

    @Test
    fun reIdentify_forTransientCloseCodes() {
        val transient = listOf(4001, 4003, 4005, 4007, 4009, 9999)
        transient.forEach { code ->
            val action = DiscordReconnectStrategy.decide(
                closeCode = code,
                hadSession = true,
                seq = 1,
                sessionId = "session",
            )
            assertEquals("code $code", ReconnectAction.ReIdentify, action)
        }
    }

    @Test
    fun refreshAndReIdentify_for4004() {
        val action = DiscordReconnectStrategy.decide(
            closeCode = 4004,
            hadSession = true,
            seq = 1,
            sessionId = "session",
        )
        assertEquals(ReconnectAction.RefreshAndReIdentify, action)
    }

    @Test
    fun surfaceFatal_for4014() {
        val action = DiscordReconnectStrategy.decide(
            closeCode = 4014,
            hadSession = true,
            seq = 1,
            sessionId = "session",
        )
        assertEquals(ReconnectAction.SurfaceFatal, action)
    }

    @Test
    fun backoffDelay_followsExponentialScheduleAndCapsAt64Seconds() {
        val expectedBaseDelays = listOf(
            1_000L,
            2_000L,
            4_000L,
            8_000L,
            16_000L,
            32_000L,
            64_000L,
            64_000L,
        )

        expectedBaseDelays.forEachIndexed { index, baseDelay ->
            val actual = DiscordReconnectStrategy.backoffDelayMs(
                attempt = index + 1,
                closeCode = 4000,
                reason = "transient failure",
            )
            val jitter = baseDelay / 4L
            assertTrue(
                "attempt ${index + 1}: expected $actual within ${baseDelay - jitter}..${baseDelay + jitter}",
                actual in (baseDelay - jitter)..(baseDelay + jitter),
            )
        }
    }

    @Test
    fun backoffDelay_usesRetryAfterForRateLimits() {
        assertEquals(
            75_500L,
            DiscordReconnectStrategy.backoffDelayMs(
                attempt = 1,
                closeCode = 429,
                reason = "rate limited;retry_after=75.5",
            ),
        )
    }

    @Test
    fun backoffDelay_enforcesMinimumForMissingMalformedOrShortRetryAfter() {
        val reasons = listOf(
            "rate limited",
            "rate limited;retry_after=invalid",
            "rate limited;retry_after=0.5",
        )

        reasons.forEach { reason ->
            assertEquals(
                reason,
                60_000L,
                DiscordReconnectStrategy.backoffDelayMs(
                    attempt = 1,
                    closeCode = 429,
                    reason = reason,
                ),
            )
        }
    }
}
