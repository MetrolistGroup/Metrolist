package com.metrolist.android.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class AudioOutputHandoffTest {
    @Test
    fun delayedOutgoingCommandsCannotOverlapThePromotedOutput() {
        val outgoing = FakeOutput(playing = true, volume = 1f, deferCommands = true)
        val incoming = FakeOutput(playing = true, volume = 0f)
        var checks = 0
        val checkOutput: () -> Unit = {
            assertFalse("Two native outputs must never be audible", outgoing.audible && incoming.audible)
            checks++
        }
        outgoing.afterCommand = checkOutput
        incoming.afterCommand = checkOutput

        replaceAudioOutput(
            outgoing.player,
            incoming.player,
            releaseOutgoing = outgoing::release,
            promoteIncoming = {
                incoming.player.volume = 1f
                incoming.player.playWhenReady = true
            },
        )

        assertTrue(checks >= 5)
        assertTrue(outgoing.released)
        assertFalse(outgoing.audible)
        assertTrue(incoming.audible)
        assertEquals(0f, outgoing.requestedVolume, 0f)
        assertFalse(outgoing.requestedPlaying)
    }

    @Test
    fun anUnfinishedReleaseCannotUnmuteTheCandidate() {
        val outgoing = FakeOutput(playing = true, volume = 1f, deferCommands = true)
        val incoming = FakeOutput(playing = true, volume = 0f)
        var promoted = false
        try {
            replaceAudioOutput(
                outgoing.player,
                incoming.player,
                releaseOutgoing = { throw IllegalStateException("release failed") },
                promoteIncoming = {
                    promoted = true
                    incoming.player.volume = 1f
                },
            )
        } catch (_: IllegalStateException) {
            assertFalse(promoted)
            assertFalse(incoming.audible)
            assertTrue(outgoing.audible)
        }
        assertFalse(promoted)
    }

    private class FakeOutput(
        playing: Boolean,
        volume: Float,
        private val deferCommands: Boolean = false,
    ) {
        var requestedPlaying = playing
        var requestedVolume = volume
        private var nativePlaying = playing
        private var nativeVolume = volume
        var released = false
            private set
        var afterCommand: () -> Unit = {}
        val audible: Boolean get() = !released && nativePlaying && nativeVolume > 0f
        val player =
            Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
                when (method.name) {
                    "setVolume" -> {
                        requestedVolume = args[0] as Float
                        if (!deferCommands) nativeVolume = requestedVolume
                        afterCommand()
                        null
                    }

                    "pause" -> {
                        requestedPlaying = false
                        if (!deferCommands) nativePlaying = false
                        afterCommand()
                        null
                    }

                    "setPlayWhenReady" -> {
                        check(!released)
                        requestedPlaying = args[0] as Boolean
                        if (!deferCommands) nativePlaying = requestedPlaying
                        afterCommand()
                        null
                    }

                    else -> {
                        error("Unexpected player call: ${method.name}")
                    }
                }
            } as Player

        fun release() {
            released = true
            nativePlaying = false
            nativeVolume = 0f
            afterCommand()
        }
    }
}
