package com.metrolist.android.playback

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.metrolist.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [33])
class PromotedMediaNotificationProviderTest {
    @Test
    fun lateNotificationUpdatesNotifyTheListenerAfterMedia3() {
        val context = RuntimeEnvironment.getApplication()
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player).build()
        val delegate = CapturingNotificationProvider(context)
        val provider = PromotedMediaNotificationProvider(context, delegate)
        val order = mutableListOf<String>()
        provider.notificationUpdatedListener = { order += LISTENER }
        try {
            val initial =
                provider.createNotification(
                    session,
                    ImmutableList.of(),
                    ReflectionHelpers.createNullProxy(MediaNotification.ActionFactory::class.java),
                ) { order += MEDIA3 }
            val updated = MediaNotification(initial.notificationId, notification(context))
            delegate.emit(updated)
            assertEquals(listOf(MEDIA3, LISTENER), order)
            assertSame(updated, provider.latestNotification)
        } finally {
            session.release()
            player.release()
        }
    }

    private fun notification(context: Context) =
        NotificationCompat
            .Builder(context, PLAYBACK_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .build()

    private class CapturingNotificationProvider(
        private val context: Context,
    ) : MediaNotification.Provider {
        private var callback: MediaNotification.Provider.Callback? = null

        override fun createNotification(
            mediaSession: MediaSession,
            mediaButtonPreferences: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback,
        ): MediaNotification {
            callback = onNotificationChangedCallback
            return MediaNotification(
                DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
                NotificationCompat
                    .Builder(context, PLAYBACK_NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .build(),
            )
        }

        override fun handleCustomCommand(
            session: MediaSession,
            action: String,
            extras: Bundle,
        ): Boolean = false

        override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
            MediaNotification.Provider.NotificationChannelInfo(
                PLAYBACK_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.playback_notification_channel_name),
            )

        fun emit(notification: MediaNotification) {
            callback?.onNotificationChanged(notification)
        }
    }

    private companion object {
        const val MEDIA3 = "media3"
        const val LISTENER = "listener"
    }
}
