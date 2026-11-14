package com.metrolist.android.playback

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.metrolist.android.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import timber.log.Timber

class DownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manager: SongDownloadManager by lazy { GlobalContext.get().get() }
    private val drainLock = Any()
    private var drainJob: Job? = null
    private var drainRequested = false
    private var latestStartId = 0
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        if (!(application as App).infrastructureReady) stopSelf()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (!(application as App).infrastructureReady) return START_NOT_STICKY
        // Every startForegroundService() call needs its own startForeground(), even while already in the foreground.
        if (enterForeground()) drainQueue(startId)
        return START_NOT_STICKY
    }

    private fun enterForeground(): Boolean {
        try {
            ServiceCompat.startForeground(
                this,
                SongDownloadManager.NOTIFICATION_ID,
                manager.aggregateNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
            foregroundStarted = true
        } catch (e: RuntimeException) {
            Timber.tag(TAG).w(e, "foreground download quota is unavailable")
            if (!foregroundStarted) {
                manager.onServiceUnavailable()
                stopSelf()
            }
        }
        return foregroundStarted
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        manager.onServiceUnavailable()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun drainQueue(startId: Int) {
        synchronized(drainLock) {
            latestStartId = startId
            if (drainJob?.isActive == true) {
                drainRequested = true
                return
            }
            drainRequested = false
            drainJob =
                serviceScope.launch {
                    (application as App).startup.await()
                    manager.resetInterruptedDownloads()
                    var completedStartId = 0
                    do {
                        while (isActive && manager.processNext()) {
                        }
                    } while (
                        synchronized(drainLock) {
                            if (drainRequested) {
                                drainRequested = false
                                true
                            } else {
                                drainJob = null
                                completedStartId = latestStartId
                                false
                            }
                        }
                    )
                    manager.flushQueue()
                    if (!manager.hasRunnableDownloads()) {
                        if (stopSelfResult(completedStartId)) {
                            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        }
                    }
                }
        }
    }

    companion object {
        private const val TAG = "DownloadService"

        fun start(context: Context): Boolean =
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
                true
            } catch (e: RuntimeException) {
                Timber.tag(TAG).w(e, "foreground download service start deferred")
                GlobalContext.get().get<SongDownloadManager>().onServiceUnavailable()
                false
            }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java))
        }
    }
}
