package com.metrolist.android.playback

import android.app.Application
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.metrolist.android.constants.MediaSessionConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
class MediaSessionConnectionTest {
    @Test
    fun legacyConnectionCompletesBeforeStartupAndQueueRestoration() {
        val service = Robolectric.buildService(MusicService::class.java).get()
        val callback =
            Class
                .forName("${MusicService::class.java.name}\$AutomotiveSessionCallback")
                .getDeclaredConstructor(MusicService::class.java)
                .apply { isAccessible = true }
                .newInstance(service) as MediaLibrarySession.Callback
        val player = ExoPlayer.Builder(service).build()
        val session = MediaLibrarySession.Builder(service, player, callback).build()
        try {
            for ((trusted, packageVerified) in listOf(true to true, false to true, false to false)) {
                val controller =
                    MediaSession.ControllerInfo.createTestOnlyControllerInfo(
                        service.packageName,
                        0,
                        service.applicationInfo.uid,
                        MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
                        0,
                        trusted,
                        Bundle.EMPTY,
                        packageVerified,
                    )
                val connection = callback.onConnectAsync(session, controller)
                assertTrue("Legacy MediaBrowser blocks main while waiting for this future", connection.isDone)
                val result = connection.get()
                assertTrue(result.isAccepted)
                if (trusted) {
                    assertTrue(result.availableSessionCommands.contains(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT))
                    assertTrue(result.availableSessionCommands.contains(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN))
                    assertTrue(result.availableSessionCommands.contains(MediaSessionConstants.CommandToggleShuffle))
                    assertTrue(result.availableSessionCommands.contains(MediaSessionConstants.CommandToggleRepeat))
                } else {
                    assertFalse(result.availableSessionCommands.contains(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT))
                }
                assertTrue(result.availableSessionCommands.contains(MediaSessionConstants.CommandToggleLike))
                assertTrue(result.availableSessionCommands.contains(MediaSessionConstants.CommandToggleShuffle))
                assertTrue(result.availableSessionCommands.contains(MediaSessionConstants.CommandToggleRepeat))
                assertTrue(result.availableSessionCommands.contains(SessionCommand.COMMAND_CODE_SESSION_SET_RATING))
                assertTrue(result.availablePlayerCommands.contains(Player.COMMAND_PLAY_PAUSE))
                assertTrue(result.availablePlayerCommands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
                assertTrue(result.availablePlayerCommands.contains(Player.COMMAND_SEEK_TO_MEDIA_ITEM))
                if (!trusted) {
                    assertFalse(result.availablePlayerCommands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
                    assertFalse(result.availableSessionCommands.contains(MediaSessionConstants.CommandToggleStartRadio))
                }
            }
            val unverifiedController =
                MediaSession.ControllerInfo.createTestOnlyControllerInfo(
                    "unverified.controller",
                    0,
                    service.applicationInfo.uid,
                    MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION,
                    0,
                    false,
                    Bundle.EMPTY,
                    false,
                )
            val unverifiedResult = callback.onConnectAsync(session, unverifiedController).get()
            assertTrue(unverifiedResult.isAccepted)
            assertFalse(unverifiedResult.availableSessionCommands.contains(MediaSessionConstants.CommandToggleLike))
            assertFalse(unverifiedResult.availableSessionCommands.contains(MediaSessionConstants.CommandToggleShuffle))
            assertFalse(unverifiedResult.availableSessionCommands.contains(MediaSessionConstants.CommandToggleRepeat))
            assertFalse(unverifiedResult.availableSessionCommands.contains(SessionCommand.COMMAND_CODE_SESSION_SET_RATING))
            assertTrue(unverifiedResult.availablePlayerCommands.contains(Player.COMMAND_PLAY_PAUSE))
            assertTrue(unverifiedResult.availablePlayerCommands.contains(Player.COMMAND_SEEK_TO_MEDIA_ITEM))
        } finally {
            session.release()
            player.release()
        }
    }

    @Test
    fun systemAndCompanionTransportCommandsPreserveLibraryAndMutationRestrictions() {
        val service = Robolectric.buildService(MusicService::class.java).get()
        val callback =
            Class
                .forName("${MusicService::class.java.name}\$AutomotiveSessionCallback")
                .getDeclaredConstructor(MusicService::class.java)
                .apply { isAccessible = true }
                .newInstance(service) as MediaLibrarySession.Callback
        val player = ExoPlayer.Builder(service).build()
        val session = MediaLibrarySession.Builder(service, player, callback).build()
        val connectionResult =
            callback.javaClass
                .getDeclaredMethod("connectionResult", MediaSession::class.java, MediaSession.ControllerInfo::class.java)
                .apply { isAccessible = true }
        val transportCommands =
            listOf(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
                Player.COMMAND_SET_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
            )
        try {
            for (legacy in listOf(true, false)) {
                for (trusted in listOf(true, false)) {
                    for (verified in listOf(true, false)) {
                        for (packageName in listOf(
                            MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME,
                            "com.google.android.projection.gearhead",
                        )) {
                            val controller =
                                MediaSession.ControllerInfo.createTestOnlyControllerInfo(
                                    packageName,
                                    -1,
                                    -1,
                                    if (legacy) MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION else 1,
                                    0,
                                    trusted,
                                    Bundle.EMPTY,
                                    verified,
                                )
                            val result = connectionResult.invoke(callback, session, controller) as MediaSession.ConnectionResult
                            val description = "legacy=$legacy trusted=$trusted verified=$verified package=$packageName"
                            assertTrue(description, result.isAccepted)
                            val canControl = legacy || trusted || verified
                            transportCommands.forEach { command ->
                                assertEquals("$description command=$command", canControl, result.availablePlayerCommands.contains(command))
                            }
                            val canBrowse = trusted || (verified && session.isAutoCompanionController(controller))
                            assertEquals(
                                description,
                                canBrowse,
                                result.availableSessionCommands.contains(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT),
                            )
                            assertEquals(
                                description,
                                canBrowse,
                                result.availableSessionCommands.contains(MediaSessionConstants.CommandAddToQueue),
                            )
                            assertEquals(description, canBrowse, result.availablePlayerCommands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
                            assertEquals(description, canControl, result.availablePlayerCommands.contains(Player.COMMAND_SET_MEDIA_ITEM))
                            assertEquals(
                                description,
                                canBrowse,
                                result.availableSessionCommands.contains(MediaSessionConstants.CommandToggleStartRadio),
                            )
                            assertEquals(
                                description,
                                trusted || verified,
                                result.availableSessionCommands.contains(SessionCommand.COMMAND_CODE_SESSION_SET_RATING),
                            )
                            assertEquals(
                                description,
                                trusted || verified,
                                result.availableSessionCommands.contains(MediaSessionConstants.CommandToggleLike),
                            )
                            assertEquals(
                                description,
                                trusted || verified,
                                result.availableSessionCommands.contains(MediaSessionConstants.CommandToggleShuffle),
                            )
                            assertEquals(
                                description,
                                trusted || verified,
                                result.availableSessionCommands.contains(MediaSessionConstants.CommandToggleRepeat),
                            )
                        }
                    }
                }
            }
        } finally {
            session.release()
            player.release()
        }
    }
}
