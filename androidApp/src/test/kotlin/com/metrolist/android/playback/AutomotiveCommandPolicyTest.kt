package com.metrolist.android.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomotiveCommandPolicyTest {
    @Test
    fun verifiedAutoCanBrowseWithoutPlatformTrustButOtherUntrustedAppsCannot() {
        assertTrue(canBrowseAutomotiveLibrary(isTrusted = false, isPackageNameVerified = true, isAutoCompanion = true))
        assertTrue(canBrowseAutomotiveLibrary(isTrusted = true, isPackageNameVerified = false, isAutoCompanion = false))
        assertFalse(canBrowseAutomotiveLibrary(isTrusted = false, isPackageNameVerified = false, isAutoCompanion = true))
        assertFalse(canBrowseAutomotiveLibrary(isTrusted = false, isPackageNameVerified = true, isAutoCompanion = false))
    }

    @Test
    fun authorizedExternalControllersCanSelectQueueItemsButCannotReplaceTheQueue() {
        assertTrue(externalTransportPlayerCommandCodes.contains(Player.COMMAND_PLAY_PAUSE))
        assertTrue(externalTransportPlayerCommandCodes.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertFalse(externalTransportPlayerCommandCodes.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertTrue(externalTransportPlayerCommands().contains(Player.COMMAND_SEEK_TO_MEDIA_ITEM))
        assertTrue(externalTransportPlayerCommands().contains(Player.COMMAND_SEEK_BACK))
        assertTrue(externalTransportPlayerCommands().contains(Player.COMMAND_SEEK_FORWARD))
        assertFalse(externalTransportPlayerCommandCodes.contains(Player.COMMAND_SET_SHUFFLE_MODE))
        assertFalse(externalTransportPlayerCommandCodes.contains(Player.COMMAND_SET_REPEAT_MODE))
    }

    @Test
    fun guestRestrictedCommandsIncludePlaybackControlsAndNavigation() {
        val restrictedCommands =
            listOf(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_SET_REPEAT_MODE,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_CHANGE_MEDIA_ITEMS,
            )
        for (command in restrictedCommands) {
            assertTrue("Command $command should be restricted for guests", isGuestRestrictedPlayerCommand(command))
        }
    }

    @Test
    fun guestAllowedCommandsIncludeMetadataAndVolume() {
        val allowedCommands =
            listOf(
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_VOLUME,
                Player.COMMAND_SET_VOLUME,
                Player.COMMAND_GET_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_SET_VIDEO_SURFACE,
            )
        for (command in allowedCommands) {
            assertFalse("Command $command should be allowed for guests", isGuestRestrictedPlayerCommand(command))
        }
    }
}
