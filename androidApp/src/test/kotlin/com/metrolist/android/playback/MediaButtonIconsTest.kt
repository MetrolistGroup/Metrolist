package com.metrolist.android.playback

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.legacy.PlaybackStateCompat
import com.google.common.collect.ImmutableList
import com.metrolist.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [32, 33])
class MediaButtonIconsTest {
    @Test
    fun customTransportIconsReplaceDefaultSkipActions() {
        val context = RuntimeEnvironment.getApplication()
        val preferences =
            ImmutableList.of(
                CommandButton
                    .Builder(CommandButton.ICON_PREVIOUS)
                    .setCustomIconResId(R.drawable.ic_notification_skip_previous)
                    .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .setDisplayName("Previous")
                    .setSlots(CommandButton.SLOT_BACK)
                    .build(),
                CommandButton
                    .Builder(CommandButton.ICON_NEXT)
                    .setCustomIconResId(R.drawable.ic_notification_skip_next)
                    .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                    .setDisplayName("Next")
                    .setSlots(CommandButton.SLOT_FORWARD)
                    .build(),
            )
        val player = ExoPlayer.Builder(context).build()
        player.setMediaItems(listOf("first", "second", "third").map(MediaItem::fromUri), 1, 0)
        val session = MediaSession.Builder(context, player).setMediaButtonPreferences(preferences).build()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            // Robolectric's platform MediaController does not read MediaSession playback state.
            val impl = ReflectionHelpers.getField<Any>(session, "impl")
            val legacyStub = ReflectionHelpers.getField<Any>(impl, "sessionLegacyStub")
            val playerWrapper = ReflectionHelpers.getField<Any>(impl, "playerWrapper")
            val state =
                legacyStub.javaClass
                    .getDeclaredMethod("createPlaybackStateCompat", playerWrapper.javaClass)
                    .apply { isAccessible = true }
                    .invoke(legacyStub, playerWrapper) as PlaybackStateCompat
            assertFalse(state.actions and PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS != 0L)
            assertFalse(state.actions and PlaybackStateCompat.ACTION_SKIP_TO_NEXT != 0L)
            assertTrue(state.actions and PlaybackStateCompat.ACTION_PLAY_PAUSE != 0L)
            assertEquals(
                listOf(R.drawable.ic_notification_skip_previous, R.drawable.ic_notification_skip_next),
                state.customActions.map { it.icon },
            )
            val buttons = NotificationProvider(context).buttons(session, preferences)
            assertEquals(listOf(CommandButton.ICON_PREVIOUS, CommandButton.ICON_NEXT), buttons.map { it.icon })
            assertEquals(R.drawable.ic_notification_skip_previous, buttons.first().iconResId)
            assertEquals(R.drawable.ic_notification_skip_next, buttons.last().iconResId)
        } finally {
            session.release()
            player.release()
        }
    }

    private class NotificationProvider(
        context: Context,
    ) : DefaultMediaNotificationProvider(context) {
        fun buttons(
            session: MediaSession,
            preferences: ImmutableList<CommandButton>,
        ): ImmutableList<CommandButton> =
            getMediaButtons(
                session,
                Player.Commands
                    .Builder()
                    .addAll(
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_NEXT,
                    ).build(),
                preferences,
                false,
            )
    }
}
