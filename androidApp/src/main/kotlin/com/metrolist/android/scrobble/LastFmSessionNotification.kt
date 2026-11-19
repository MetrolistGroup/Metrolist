package com.metrolist.android.scrobble

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.metrolist.android.MainActivity
import com.metrolist.android.R
import com.metrolist.shared.scrobble.ScrobbleSessions

internal object LastFmSessionNotification {
    private const val CHANNEL_ID = "lastfm_session"
    private const val NOTIFICATION_ID = 4301

    fun register(context: Context) {
        val appContext = context.applicationContext
        NotificationManagerCompat.from(appContext).createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(appContext.getString(R.string.brand_lastfm))
                .build(),
        )
        ScrobbleSessions.onLastFmSessionExpiredChanged = { expired ->
            val manager = appContext.getSystemService(NotificationManager::class.java)
            if (expired) manager?.notify(NOTIFICATION_ID, build(appContext)) else manager?.cancel(NOTIFICATION_ID)
        }
    }

    private fun build(context: Context) =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.lastfm_session_expired_title))
            .setContentText(context.getString(R.string.lastfm_session_expired_description))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.lastfm_session_expired_description)))
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    NOTIFICATION_ID,
                    Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_LASTFM),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).setAutoCancel(true)
            .build()
}
