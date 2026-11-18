package com.metrolist.android.playback

import android.app.Application
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import com.metrolist.android.R
import com.metrolist.android.widget.MusicWidgetReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28, 33])
class PlaybackServiceStartTest {
    @Test
    fun mediaButtonsAndMedia3SelfStartsRequireForegroundAcknowledgement() {
        assertTrue(requiresPlaybackForegroundStart(Intent(Intent.ACTION_MEDIA_BUTTON)))
        assertTrue(requiresPlaybackForegroundStart(Intent()))
        assertFalse(requiresPlaybackForegroundStart(null))
        assertFalse(requiresPlaybackForegroundStart(Intent(MusicWidgetReceiver.ACTION_UPDATE_WIDGET)))
        assertFalse(requiresPlaybackForegroundStart(Intent(MusicService.ACTION_REFRESH_AUTOMOTIVE_LIBRARY)))
        assertFalse(requiresPlaybackForegroundStart(Intent("androidx.media3.session.CUSTOM_NOTIFICATION_ACTION")))
    }

    @Test
    fun emptyQueueStartsWithAValidNotificationUsingTheMedia3NotificationId() {
        val controller = Robolectric.buildService(RecordingService::class.java).create()
        val service = controller.get()
        try {
            service.promotePlaybackServiceStart(null)
            val shadow = shadowOf(service)
            assertEquals(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID, shadow.lastForegroundNotificationId)
            val notification = shadow.lastForegroundNotification
            assertEquals(R.string.app_name.toString(), notification.extras.getCharSequence("android.title"))
            assertTrue(notification.smallIcon != null)
            assertTrue(notification.contentIntent != null)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun repeatedStartsReuseTheCurrentMediaNotification() {
        val controller = Robolectric.buildService(RecordingService::class.java).create()
        val service = controller.get()
        val notification =
            NotificationCompat
                .Builder(service, PLAYBACK_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .build()
        val mediaNotification = MediaNotification(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID, notification)
        try {
            repeat(2) {
                service.promotePlaybackServiceStart(mediaNotification)
                assertSame(notification, shadowOf(service).lastForegroundNotification)
                assertEquals(mediaNotification.notificationId, shadowOf(service).lastForegroundNotificationId)
            }
            if (Build.VERSION.SDK_INT >= 29) {
                assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, service.foregroundServiceType)
            }
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun reconciliationClearsIdleStartsWithoutRepeatingForPreparedSelfStarts() {
        val mediaButton = Intent(Intent.ACTION_MEDIA_BUTTON)
        val selfStart = Intent()
        val item = MediaItem.Builder().setMediaId("track").build()
        val paused = player(item, Player.STATE_READY, false)
        assertTrue(shouldReconcilePlaybackServiceStart(mediaButton, paused))
        assertFalse(shouldReconcilePlaybackServiceStart(selfStart, paused))
        val playing = player(item, Player.STATE_READY, true)
        assertFalse(shouldReconcilePlaybackServiceStart(mediaButton, playing))
        assertFalse(shouldReconcilePlaybackServiceStart(selfStart, playing))
        assertTrue(shouldReconcilePlaybackServiceStart(selfStart, player(item, Player.STATE_IDLE, false)))
        assertTrue(shouldReconcilePlaybackServiceStart(selfStart, player(null, Player.STATE_READY, false)))
        assertTrue(shouldReconcilePlaybackServiceStart(selfStart, null))
    }

    private fun player(
        item: MediaItem?,
        state: Int,
        playWhenReady: Boolean,
    ): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getCurrentMediaItem" -> item
                "getPlaybackState" -> state
                "getPlayWhenReady" -> playWhenReady
                else -> error("Unexpected player call: ${method.name}")
            }
        } as Player

    class RecordingService : Service() {
        @Suppress("DEPRECATION")
        private val labels by lazy {
            object : Resources(baseContext.assets, baseContext.resources.displayMetrics, baseContext.resources.configuration) {
                override fun getString(id: Int): String = id.toString()
            }
        }

        override fun getResources(): Resources = labels

        override fun onBind(intent: Intent?): IBinder? = null
    }
}
