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
import com.metrolist.android.utils.launchAsync
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

class ExpressiveWidgetReceiver :
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
        launchAsync {
            try {
                (context.applicationContext as App).startup.await()
                if (MusicService.isRunning) {
                    context.startService(
                        Intent(context, MusicService::class.java).setAction(MusicWidgetReceiver.ACTION_UPDATE_WIDGET),
                    )
                } else {
                    widgetManager.updateIdleExpressiveWidgets(appWidgetIds)
                    if (MusicService.isRunning) {
                        context.startService(
                            Intent(context, MusicService::class.java).setAction(MusicWidgetReceiver.ACTION_UPDATE_WIDGET),
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.e(error, "Failed to refresh expressive widgets")
            }
        }
    }
}
