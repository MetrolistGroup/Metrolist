/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistRemovalExecutionTest {
    @Test
    fun `removal uses the resolved occurrence id`() = runBlocking {
        var removedSetVideoId: String? = null

        val result = executePlaylistRemoval(
            resolveSetVideoId = { "setVideoId-B" },
            remove = { removedSetVideoId = it },
        )

        assertTrue(result.isSuccess)
        assertEquals("setVideoId-B", removedSetVideoId)
    }

    @Test
    fun `failed removal remains retryable`() = runBlocking {
        var attempts = 0
        val remove: suspend (String) -> Unit = {
            attempts++
            if (attempts == 1) error("network down")
        }

        val first = executePlaylistRemoval({ "setVideoId-A" }, remove)
        val retry = executePlaylistRemoval({ "setVideoId-A" }, remove)

        assertTrue(first.isFailure)
        assertTrue(retry.isSuccess)
        assertEquals(2, attempts)
    }

    @Test
    fun `missing occurrence never calls remote removal`() = runBlocking {
        var calls = 0

        val result = executePlaylistRemoval(
            resolveSetVideoId = { null },
            remove = { calls++ },
        )

        assertTrue(result.isFailure)
        assertEquals(0, calls)
    }

    @Test
    fun `failed request succeeds only when the exact occurrence is confirmed absent`() = runBlocking {
        var checkedId: String? = null
        val result = executePlaylistRemoval(
            resolveSetVideoId = { "removed-copy" },
            remove = { error("already removed") },
            confirmAbsent = {
                checkedId = it
                it != "remaining-copy"
            },
        )
        assertTrue(result.isSuccess)
        assertEquals("removed-copy", checkedId)
        assertEquals(false, result.getOrThrow())
    }

    @Test
    fun `failed request remains a failure when the occurrence still exists`() = runBlocking {
        val result = executePlaylistRemoval({ "copy" }, { error("forbidden") }, { false })
        assertTrue(result.isFailure)
    }

    @Test
    fun `failed recheck never assumes the occurrence was removed`() = runBlocking {
        val result = executePlaylistRemoval({ "copy" }, { error("network") }, { error("offline") })
        assertTrue(result.isFailure)
    }
}
