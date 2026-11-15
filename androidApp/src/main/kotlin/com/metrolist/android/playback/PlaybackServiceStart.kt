package com.metrolist.android.playback

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import com.metrolist.android.MainActivity
import com.metrolist.android.R

internal const val PLAYBACK_NOTIFICATION_CHANNEL_ID = "playback"

internal fun requiresPlaybackForegroundStart(intent: Intent?): Boolean =
    intent != null && (intent.action == Intent.ACTION_MEDIA_BUTTON || intent.action == null)

internal fun shouldReconcilePlaybackServiceStart(
    intent: Intent?,
    player: Player?,
): Boolean =
    player?.currentMediaItem == null || player.playbackState == Player.STATE_IDLE ||
        (intent?.action == Intent.ACTION_MEDIA_BUTTON && !player.playWhenReady)

internal fun Service.promotePlaybackServiceStart(notification: MediaNotification?) {
    val startupNotification =
        notification ?: MediaNotification(
            DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID,
            NotificationCompat
                .Builder(this, PLAYBACK_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java)
                            .setAction(MusicService.ACTION_OPEN_PLAYER)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setOngoing(true)
                .setSilent(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build(),
        )
    ServiceCompat.startForeground(
        this,
        startupNotification.notificationId,
        startupNotification.notification,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
    )
}
