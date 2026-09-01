/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The remote lookup is the last line of defence before a destructive call: if it never yields a
 * setVideoId, the removal must be abandoned rather than guessing an occurrence.
 */
class SetVideoIdResolverTest {

    private var remoteCalls = 0
    private var delays = 0

    private fun resolve(
        fromDb: suspend () -> String?,
        fromRemote: suspend () -> String?,
    ): String? = runBlocking {
        resolveSetVideoIdForRemoval(
            songId = "song-1",
            fromDb = fromDb,
            fromRemote = {
                remoteCalls++
                fromRemote()
            },
            onDelay = { delays++ },
        )
    }

    @Test
    fun `local set video id is used without any remote lookup`() {
        val result = resolve(fromDb = { "setVideoId-A" }, fromRemote = { "setVideoId-REMOTE" })

        assertEquals("setVideoId-A", result)
        assertEquals(0, remoteCalls)
        assertEquals(0, delays)
    }

    @Test
    fun `falls back to a single remote lookup when the local id is missing`() {
        val result = resolve(fromDb = { null }, fromRemote = { "setVideoId-REMOTE" })

        assertEquals("setVideoId-REMOTE", result)
        assertEquals(1, remoteCalls)
        assertEquals(0, delays)
    }

    @Test
    fun `retries the remote lookup until it succeeds`() {
        val result = resolve(
            fromDb = { null },
            fromRemote = { if (remoteCalls < 3) null else "setVideoId-REMOTE" },
        )

        assertEquals("setVideoId-REMOTE", result)
        assertEquals(3, remoteCalls)
        assertEquals(2, delays)
    }

    @Test
    fun `retries after a failing remote lookup`() {
        val result = resolve(
            fromDb = { null },
            fromRemote = {
                if (remoteCalls < 2) error("network down") else "setVideoId-REMOTE"
            },
        )

        assertEquals("setVideoId-REMOTE", result)
        assertEquals(2, remoteCalls)
        assertEquals(1, delays)
    }

    @Test
    fun `gives up after the attempt budget instead of removing an unknown occurrence`() {
        val result = resolve(fromDb = { null }, fromRemote = { null })

        assertNull(result)
        assertEquals(SET_VIDEO_ID_REMOTE_ATTEMPTS, remoteCalls)
        assertEquals(SET_VIDEO_ID_REMOTE_ATTEMPTS - 1, delays)
    }

    @Test
    fun `gives up when every remote lookup throws`() {
        val result = resolve(fromDb = { null }, fromRemote = { error("network down") })

        assertNull(result)
        assertEquals(SET_VIDEO_ID_REMOTE_ATTEMPTS, remoteCalls)
    }
}
