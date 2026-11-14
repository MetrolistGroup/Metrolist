package com.metrolist.shared.listentogether

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListenTogetherControlsTest {
    @Test
    fun localPreferencesRemainEditableForEveryRole() {
        for (role in ListenTogetherRoomRole.entries) {
            for (inRoom in listOf(false, true)) {
                assertTrue(listenTogetherControls(enabled = true, role, inRoom).settingsEnabled)
                assertFalse(listenTogetherControls(enabled = false, role, inRoom).settingsEnabled)
            }
        }
    }

    @Test
    fun onlyGuestsInARoomHaveTheirTransportAndQueueLocked() {
        for (role in ListenTogetherRoomRole.entries) {
            for (enabled in listOf(false, true)) {
                assertFalse(listenTogetherControls(enabled, role, inRoom = false).playbackLocked)
                assertEquals(
                    role == ListenTogetherRoomRole.GUEST,
                    listenTogetherControls(enabled, role, inRoom = true).playbackLocked,
                )
            }
        }
    }

    @Test
    fun onlyAnActiveHostCanModerateTheRoom() {
        for (role in ListenTogetherRoomRole.entries) {
            assertEquals(
                role == ListenTogetherRoomRole.HOST,
                listenTogetherControls(enabled = true, role, inRoom = true).canModerate,
            )
            assertFalse(listenTogetherControls(enabled = true, role, inRoom = false).canModerate)
            assertFalse(listenTogetherControls(enabled = false, role, inRoom = true).canModerate)
        }
    }
}
