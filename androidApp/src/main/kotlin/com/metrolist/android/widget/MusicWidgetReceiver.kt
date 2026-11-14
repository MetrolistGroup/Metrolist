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

class MusicWidgetReceiver :
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

    override fun onDeleted(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        requestWidgetRefresh(context, intArrayOf())
    }

    private fun requestWidgetRefresh(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                (context.applicationContext as App).startup.await()
                widgetManager.refreshProgressDemand()
                if (MusicService.isRunning) {
                    requestServiceSnapshot(context)
                } else {
                    widgetManager.updateIdleMusicWidgets(appWidgetIds)
                    if (MusicService.isRunning) requestServiceSnapshot(context)
                }
            } catch (error: Exception) {
                Timber.e(error, "Failed to refresh music widgets")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun requestServiceSnapshot(context: Context) {
        context.startService(
            Intent(context, MusicService::class.java).setAction(ACTION_UPDATE_WIDGET),
        )
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.metrolist.music.widget.PLAY_PAUSE"
        const val ACTION_LIKE = "com.metrolist.music.widget.LIKE"
        const val ACTION_NEXT = "com.metrolist.music.widget.NEXT"
        const val ACTION_PREVIOUS = "com.metrolist.music.widget.PREVIOUS"
        const val ACTION_UPDATE_WIDGET = "com.metrolist.music.widget.UPDATE_WIDGET"
    }
}
