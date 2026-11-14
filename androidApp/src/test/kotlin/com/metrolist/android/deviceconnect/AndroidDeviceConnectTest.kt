package com.metrolist.android.deviceconnect

import android.Manifest
import com.metrolist.shared.deviceconnect.DcLocalPlaybackState
import com.metrolist.shared.deviceconnect.DcPlaybackSnapshot
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDeviceConnectTest {
    @Test
    fun requiresLocalNetworkPermissionStartingAtApi37() {
        for (sdkInt in listOf(28, 36)) {
            assertNull(deviceConnectLocalNetworkPermission(sdkInt))
        }
        for (sdkInt in listOf(37, 38)) {
            assertEquals(
                Manifest.permission.ACCESS_LOCAL_NETWORK,
                deviceConnectLocalNetworkPermission(sdkInt),
            )
        }
    }

    @Test
    fun syncQueueEditsKeepAudioButDuplicateSelectionAndRepeatReloadIt() {
        val song = SongItem("duplicate", "Track", null, emptyList(), null)
        val other = song.copy(id = "other")
        val original = MusicQueueSnapshot(listOf(song, song, other), listOf(song, song, other), 0)
        val edited = original.copy(displayedItems = listOf(other, song, song), currentIndex = 1, isShuffled = true)
        val state = DcPlaybackSnapshot("session", 2, 2, 1, DcLocalPlaybackState(queue = edited))

        assertFalse(requiresDeviceConnectTrackReload(song.id, song.id, original, state, "session", 1))
        assertTrue(requiresDeviceConnectTrackReload(song.id, "counterpart-video", original, state, "session", 1))
        assertTrue(requiresDeviceConnectTrackReload(song.id, song.id, original, state.copy(trackRevision = 2), "session", 1))
        assertTrue(requiresDeviceConnectTrackReload(song.id, null, original, state, "session", 1))
        assertTrue(requiresDeviceConnectTrackReload(song.id, song.id, original, state, "session", 1, targetLoaded = false))
        assertTrue(requiresDeviceConnectTrackReload(other.id, other.id, original, state, "session", 1))
        assertTrue(
            requiresDeviceConnectTrackReload(
                song.id,
                song.id,
                original,
                state.copy(playback = DcLocalPlaybackState(queue = original.copy(currentIndex = 1))),
                null,
                null,
            ),
        )
    }

    @Test
    fun localSyncTracksOnlyReuseLoadedCoordinatorMedia() {
        val remote = SongItem("remote", "Track", null, emptyList(), null)
        for (local in listOf(remote.copy(isLocal = true), remote.copy(id = "local_path"))) {
            assertTrue(canUseDeviceConnectTrack(local, isFollowing = false, requiresReload = false))
            assertFalse(canUseDeviceConnectTrack(local, isFollowing = false, requiresReload = true))
            assertFalse(canUseDeviceConnectTrack(local, isFollowing = true, requiresReload = false))
            assertFalse(canUseDeviceConnectTrack(local, isFollowing = true, requiresReload = true))
        }
        assertTrue(canUseDeviceConnectTrack(remote, isFollowing = true, requiresReload = true))
    }
}
