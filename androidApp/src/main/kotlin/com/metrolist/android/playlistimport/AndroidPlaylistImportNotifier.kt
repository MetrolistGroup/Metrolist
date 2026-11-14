package com.metrolist.android.playlistimport

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.metrolist.android.MainActivity
import com.metrolist.android.R
import com.metrolist.shared.playlistimport.ImportProgressState
import com.metrolist.shared.playlistimport.PlaylistImportManager

object AndroidPlaylistImportNotifier {
    const val CHANNEL_ID = "playlist_import_progress"
    const val RESULT_CHANNEL_ID = "playlist_import"
    const val PROGRESS_NOTIFICATION_ID = 4101
    private const val RESULT_NOTIFICATION_ID = 4102

    private var lastProgressNotificationTime = 0L
    private const val NOTIFICATION_THROTTLE_MS = 300L

    fun ensureChannel(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        nm.createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.import_notification_progress_channel_name))
                .setDescription(context.getString(R.string.import_notification_channel_desc))
                .build(),
        )
        nm.createNotificationChannel(
            NotificationChannelCompat
                .Builder(RESULT_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.import_notification_channel_name))
                .setDescription(context.getString(R.string.import_notification_channel_desc))
                .build(),
        )
    }

    fun showProgress(
        context: Context,
        requestId: String,
        playlistTitle: String,
        statusText: String,
        current: Int,
        total: Int?,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastProgressNotificationTime < NOTIFICATION_THROTTLE_MS && current < (total ?: 0)) return
        lastProgressNotificationTime = now
        context.getSystemService(NotificationManager::class.java)?.notify(
            PROGRESS_NOTIFICATION_ID,
            progressNotification(context, requestId, playlistTitle, statusText, current, total),
        )
    }

    fun progressNotification(
        context: Context,
        requestId: String,
        playlistTitle: String,
        statusText: String,
        current: Int,
        total: Int?,
    ): Notification {
        ensureChannel(context)
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                PROGRESS_NOTIFICATION_ID,
                Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_PLAYLIST_IMPORT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val cancelIntent =
            Intent(context, AndroidPlaylistImportService::class.java).apply {
                action = AndroidPlaylistImportTransfer.ACTION_CANCEL
                putExtra(AndroidPlaylistImportTransfer.EXTRA_REQUEST_ID, requestId)
            }
        val cancelPendingIntent =
            PendingIntent.getService(
                context,
                requestId.hashCode(),
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.import_notification_in_progress, playlistTitle))
                .setContentText(statusText)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .addAction(0, context.getString(R.string.import_cancel), cancelPendingIntent)
        if (total != null && total > 0) builder.setProgress(total, current, false) else builder.setProgress(0, 0, true)
        return builder.build()
    }

    fun showSuccess(
        context: Context,
        playlistTitle: String,
        importedCount: Int,
        unimportedTracks: List<PlaylistImportManager.CsvTrack>,
    ) {
        ensureChannel(context)
        context.getSystemService(NotificationManager::class.java)?.cancel(PROGRESS_NOTIFICATION_ID)

        val text =
            if (unimportedTracks.isEmpty()) {
                context.getString(R.string.import_notification_success, importedCount, playlistTitle)
            } else {
                context.getString(R.string.import_notification_partial, importedCount, playlistTitle, unimportedTracks.size)
            }

        val builder =
            NotificationCompat
                .Builder(context, RESULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.import_status_completed))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(resultPendingIntent(context, text))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        context.getSystemService(NotificationManager::class.java)?.notify(RESULT_NOTIFICATION_ID, builder.build())
    }

    fun showFailure(
        context: Context,
        playlistTitle: String,
        errorMessage: String,
    ) {
        ensureChannel(context)
        context.getSystemService(NotificationManager::class.java)?.cancel(PROGRESS_NOTIFICATION_ID)

        val text = context.getString(R.string.import_notification_failed, playlistTitle, errorMessage)

        val builder =
            NotificationCompat
                .Builder(context, RESULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.import_status_failed))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(resultPendingIntent(context, text))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)

        context.getSystemService(NotificationManager::class.java)?.notify(RESULT_NOTIFICATION_ID, builder.build())
    }

    fun showCancelled(
        context: Context,
        cancelled: ImportProgressState.Cancelled,
    ) {
        ensureChannel(context)
        cancelProgress(context)
        val message = context.getString(R.string.import_cancelled_partial, cancelled.saved, cancelled.destinationName)
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(cancelled.destinationName)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(resultPendingIntent(context, message))
                .setAutoCancel(true)
                .build()
        context.getSystemService(NotificationManager::class.java)?.notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun resultPendingIntent(
        context: Context,
        message: String,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            RESULT_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_PLAYLIST_IMPORT)
                .putExtra(MainActivity.EXTRA_IMPORT_RESULT_MESSAGE, message),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun cancelProgress(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(PROGRESS_NOTIFICATION_ID)
    }
}
