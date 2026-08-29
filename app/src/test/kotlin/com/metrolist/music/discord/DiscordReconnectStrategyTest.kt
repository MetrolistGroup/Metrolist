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
}
