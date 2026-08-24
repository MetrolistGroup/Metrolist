package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistUploadsTest {
    @Test
    fun `a copy that was not there before means the upload landed`() {
        assertEquals(
            UploadOutcome.Landed("svi_new"),
            reconcileUpload(
                knownSetVideoIds = listOf("svi_old"),
                remoteSetVideoIds = listOf("svi_old", "svi_new"),
            ),
        )
    }

    @Test
    fun `a playlist holding no more copies than before means it never arrived`() {
        assertEquals(
            UploadOutcome.DidNotLand,
            reconcileUpload(
                knownSetVideoIds = listOf("svi_old"),
                remoteSetVideoIds = listOf("svi_old"),
            ),
        )
    }

    @Test
    fun `a song already in the playlist is not mistaken for this upload`() {
        // Presence proves nothing: "add anyway" exists so a playlist can hold a song twice.
        assertEquals(
            UploadOutcome.DidNotLand,
            reconcileUpload(
                knownSetVideoIds = listOf("svi_old"),
                remoteSetVideoIds = listOf("svi_old"),
            ),
        )
    }

    @Test
    fun `a first copy of a song the playlist did not hold is claimed`() {
        assertEquals(
            UploadOutcome.Landed("svi_first"),
            reconcileUpload(
                knownSetVideoIds = emptyList(),
                remoteSetVideoIds = listOf("svi_first"),
            ),
        )
    }

    @Test
    fun `a second copy of a song the playlist already held twice is claimed`() {
        assertEquals(
            UploadOutcome.Landed("svi_c"),
            reconcileUpload(
                knownSetVideoIds = listOf("svi_a", "svi_b"),
                remoteSetVideoIds = listOf("svi_a", "svi_b", "svi_c"),
            ),
        )
    }

    @Test
    fun `two copies appearing at once land without naming either`() {
        // Pointing the row at the wrong one would have it name an item it did not create.
        assertEquals(
            UploadOutcome.Landed(null),
            reconcileUpload(
                knownSetVideoIds = emptyList(),
                remoteSetVideoIds = listOf("svi_a", "svi_b"),
            ),
        )
    }

    @Test
    fun `a copy this device never recorded does not hide the new one`() {
        // Another device may have added a copy this one never confirmed; the count still grew by
        // one more than it can account for, so the upload landed.
        assertEquals(
            UploadOutcome.Landed(null),
            reconcileUpload(
                knownSetVideoIds = listOf("svi_a"),
                remoteSetVideoIds = listOf("svi_a", "svi_elsewhere", "svi_new"),
            ),
        )
    }

    @Test
    fun `a playlist that lost copies is not read as a landing`() {
        assertEquals(
            UploadOutcome.DidNotLand,
            reconcileUpload(
                knownSetVideoIds = listOf("svi_a", "svi_b"),
                remoteSetVideoIds = listOf("svi_a"),
            ),
        )
    }
}
