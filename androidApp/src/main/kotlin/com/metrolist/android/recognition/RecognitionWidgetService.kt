package com.metrolist.android.recognition

import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.metrolist.android.App
import com.metrolist.android.MainActivity
import com.metrolist.android.R
import com.metrolist.android.widget.RecognizerWidgets
import com.metrolist.shared.recognition.RecognitionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import timber.log.Timber

class RecognitionWidgetService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val sessions =
        RecognitionSessionController(
            scope = scope,
            recognize = {
                (application as App).startup.await()
                MusicRecognitionService.recognize(this)
            },
            finish = ::finish,
        )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (!MusicRecognitionService.hasRecordPermission(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            startInForeground()
        } catch (error: RuntimeException) {
            if (!isRecognitionStartDenied(error)) throw error
            Timber.w(error, "Microphone foreground promotion denied")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> sessions.start()
            ACTION_STOP -> sessions.stop()
            else -> sessions.toggle()
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startInForeground() {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat
                .Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(getString(R.string.widget_recognizer_channel_name))
                .setDescription(getString(R.string.widget_recognizer_channel_desc))
                .build(),
        )
        val notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.widget_recognizer_name))
                .setContentText(getString(R.string.widget_recognizer_notification_text))
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_RECOGNITION),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).addAction(
                    0,
                    getString(R.string.menu_stop),
                    PendingIntent.getService(
                        this,
                        1,
                        Intent(this, RecognitionWidgetService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).setOngoing(true)
                .setSilent(true)
                .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(
            context: Context,
            action: String = ACTION_START,
        ): Boolean =
            try {
                ContextCompat.startForegroundService(context, Intent(context, RecognitionWidgetService::class.java).setAction(action))
                true
            } catch (error: RuntimeException) {
                if (!isRecognitionStartDenied(error)) throw error
                Timber.w(error, "Microphone foreground service start denied")
                false
            }

        fun stop(context: Context) {
            context.startService(Intent(context, RecognitionWidgetService::class.java).setAction(ACTION_STOP))
            MusicRecognitionService.reset()
        }

        const val ACTION_STOP = "com.metrolist.music.recognizer.STOP"
        const val ACTION_START = "com.metrolist.music.recognizer.START"
        const val ACTION_TOGGLE = "com.metrolist.music.widget.recognizer.TOGGLE"
        private const val CHANNEL_ID = "widget_recognition"
        private const val NOTIFICATION_ID = 1500
    }
}

private fun isRecognitionStartDenied(error: RuntimeException): Boolean =
    error is SecurityException ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && error is ForegroundServiceStartNotAllowedException)
