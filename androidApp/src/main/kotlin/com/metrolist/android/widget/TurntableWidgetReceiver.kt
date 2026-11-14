/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.metrolist.android.App
import com.metrolist.android.playback.MusicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

class TurntableWidgetReceiver :
    AppWidgetProvider(),
    KoinComponent {
    private val widgetManager: MetrolistWidgetManager by inject()

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        requestWidgetRefresh(context, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        requestWidgetRefresh(context, intArrayOf(appWidgetId))
    }

    private fun requestWidgetRefresh(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                (context.applicationContext as App).startup.await()
                if (MusicService.isRunning) {
                    requestServiceSnapshot(context)
                } else {
                    widgetManager.updateIdleTurntableWidgets(appWidgetIds)
                    if (MusicService.isRunning) requestServiceSnapshot(context)
                }
            } catch (error: Exception) {
                Timber.e(error, "Failed to refresh turntable widgets")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun requestServiceSnapshot(context: Context) {
        context.startService(
            Intent(context, MusicService::class.java).setAction(MusicWidgetReceiver.ACTION_UPDATE_WIDGET),
        )
    }

    companion object {
        const val ACTION_TURNTABLE_PLAY_PAUSE = "com.metrolist.music.widget.TURNTABLE_PLAY_PAUSE"
        const val ACTION_TURNTABLE_NEXT = "com.metrolist.music.widget.TURNTABLE_NEXT"
        const val ACTION_TURNTABLE_PREVIOUS = "com.metrolist.music.widget.TURNTABLE_PREVIOUS"
        const val ACTION_UPDATE_TURNTABLE_WIDGET = "com.metrolist.music.widget.UPDATE_TURNTABLE_WIDGET"
    }
}
