package com.metrolist.android.playback

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.os.Build
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.legacy.PlaybackStateCompat
import com.google.common.collect.ImmutableList
import com.metrolist.android.R
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import android.R as AndroidR

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [32, 33])
class MediaButtonIconsTest {
    private val context by lazy {
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Suppress("DEPRECATION")
            private val labels =
                object : Resources(baseContext.assets, baseContext.resources.displayMetrics, baseContext.resources.configuration) {
                    override fun getString(id: Int): String = id.toString()

                    override fun getDimensionPixelSize(id: Int): Int = super.getDimensionPixelSize(AndroidR.dimen.app_icon_size)
                }

            override fun getResources(): Resources = labels
        }
    }

    @Before
    fun isolateSettings() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @After
    fun restoreSettings() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun notificationIconsPreserveStandardSessionTransportActions() {
        val player = ExoPlayer.Builder(context).build()
        player.setMediaItems(listOf("first", "second", "third").map(MediaItem::fromUri), 1, 0)
        val session = MediaSession.Builder(context, player).build()
        val delegate = NotificationProvider(context)
        val provider = PromotedMediaNotificationProvider(context, delegate)
        try {
            shadowOf(Looper.getMainLooper()).idle()
            provider.createNotification(
                session,
                ImmutableList.of(),
                ReflectionHelpers.createNullProxy(MediaNotification.ActionFactory::class.java),
            ) { }
            // Robolectric's platform MediaController does not read MediaSession playback state.
            val impl = ReflectionHelpers.getField<Any>(session, "impl")
            val legacyStub = ReflectionHelpers.getField<Any>(impl, "sessionLegacyStub")
            val playerWrapper = ReflectionHelpers.getField<Any>(impl, "playerWrapper")
            val state =
                legacyStub.javaClass
                    .getDeclaredMethod("createPlaybackStateCompat", playerWrapper.javaClass)
                    .apply { isAccessible = true }
                    .invoke(legacyStub, playerWrapper) as PlaybackStateCompat
            assertTrue(state.actions and PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS != 0L)
            assertTrue(state.actions and PlaybackStateCompat.ACTION_SKIP_TO_NEXT != 0L)
            assertTrue(state.actions and PlaybackStateCompat.ACTION_PLAY_PAUSE != 0L)
            assertTrue(state.actions and PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM != 0L)
            assertTrue(state.customActions.isEmpty())

            val buttons = delegate.buttons
            assertEquals(listOf(CommandButton.ICON_PREVIOUS, CommandButton.ICON_PLAY, CommandButton.ICON_NEXT), buttons.map { it.icon })
            if (Build.VERSION.SDK_INT < 33) {
                assertEquals(R.drawable.ic_notification_skip_previous, buttons.first().iconResId)
                assertEquals(R.drawable.ic_notification_skip_next, buttons.last().iconResId)
                assertEquals(Player.COMMAND_SEEK_TO_PREVIOUS, delegate.preferences.first().playerCommand)
                assertEquals(Player.COMMAND_SEEK_TO_NEXT, delegate.preferences.last().playerCommand)
            } else {
                assertTrue(delegate.preferences.isEmpty())
            }
        } finally {
            session.release()
            player.release()
        }
    }

    @Test
    fun enteringAndLeavingGuestPlaybackUpdatesNotificationTransportAvailability() {
        val backend = ExoPlaybackPlayer(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val locked = MutableStateFlow(false)
        backend.setQueue(listOf("first", "second", "third").map { SongItem(it, it, null, emptyList(), null) }, 1, preparePlayback = false)
        val player =
            MediaSessionPlayer(
                backend,
                MutableStateFlow(Player.REPEAT_MODE_OFF),
                isPlaybackLockedProvider = { locked.value },
                isPlaybackLockedFlow = locked,
                scope = scope,
            )
        val session = MediaSession.Builder(context, player).build()
        val delegate = NotificationProvider(context)
        val provider = PromotedMediaNotificationProvider(context, delegate)
        val actionFactory = ReflectionHelpers.createNullProxy(MediaNotification.ActionFactory::class.java)
        try {
            for (guest in listOf(false, true, false)) {
                locked.value = guest
                shadowOf(Looper.getMainLooper()).idle()
                provider.createNotification(session, ImmutableList.of(), actionFactory) { }
                assertEquals(!guest, player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
                assertEquals(!guest, player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
                if (guest) {
                    assertTrue(delegate.preferences.isEmpty())
                    assertTrue(delegate.buttons.isEmpty())
                } else {
                    assertEquals(
                        listOf(CommandButton.ICON_PREVIOUS, CommandButton.ICON_PLAY, CommandButton.ICON_NEXT),
                        delegate.buttons.map { it.icon },
                    )
                }
            }
        } finally {
            session.release()
            scope.cancel()
            backend.releaseAndAwaitScope()
        }
    }

    private class NotificationProvider(
        context: Context,
    ) : DefaultMediaNotificationProvider(context) {
        var preferences: ImmutableList<CommandButton> = ImmutableList.of()
            private set
        var buttons: ImmutableList<CommandButton> = ImmutableList.of()
            private set

        override fun getMediaButtons(
            session: MediaSession,
            playerCommands: Player.Commands,
            mediaButtonPreferences: ImmutableList<CommandButton>,
            showPauseButton: Boolean,
        ): ImmutableList<CommandButton> {
            preferences = mediaButtonPreferences
            buttons =
                super.getMediaButtons(
                    session,
                    playerCommands,
                    preferences,
                    showPauseButton,
                )
            return ImmutableList.of()
        }
    }
}
