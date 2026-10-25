/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.EOFException
import java.io.IOException

class PlaybackErrorTest {
    @Test
    fun `cause chain keeps each nested playback failure`() {
        val rootCause = EOFException("stream ended")
        val sourceCause = IOException("read failed", rootCause)
        val error = IllegalStateException("playback failed", sourceCause)

        assertEquals(
            listOf(error, sourceCause, rootCause),
            error.causeChain(),
        )
    }

    @Test
    fun `offline warning replaces misleading stream errors`() {
        assertEquals(
            listOf("Connect to the internet"),
            playbackErrorMessages(
                isOnline = false,
                offlineMessage = "Connect to the internet",
                guidance = null,
                rawErrorMessages = listOf("This track is unavailable"),
            ),
        )
    }
}
