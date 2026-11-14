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

class PlaylistWidgetReceiver :
    AppWidgetProvider(),
    KoinComponent {
    private val playlistWidgetManager: PlaylistWidgetManager by inject()
    private val widgetManager: MetrolistWidgetManager by inject()

    override fun onEnabled(context: Context) {
        CoroutineScope(Dispatchers.Main).launch {
            (context.applicationContext as App).startup.await()
            playlistWidgetManager.updateObservation()
        }
    }

    override fun onDisabled(context: Context) {
        playlistWidgetManager.stopObservation()
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        requestWidgetRefresh(context)
    }

    override fun onDeleted(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        requestWidgetRefresh(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        requestWidgetRefresh(context)
    }

    private fun requestWidgetRefresh(context: Context) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                (context.applicationContext as App).startup.await()
                playlistWidgetManager.updateObservation()
                widgetManager.refreshProgressDemand()
                if (MusicService.isRunning) {
                    requestServiceSnapshot(context)
                } else {
                    playlistWidgetManager.updateIdleWidgets()
                    if (MusicService.isRunning) requestServiceSnapshot(context)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to refresh playlist widgets")
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
        private const val TAG = "PlaylistWidgetReceiver"
        const val ACTION_PLAY_TARGET = "com.metrolist.music.widget.playlists.PLAY_TARGET"
        const val ACTION_UPDATE_WIDGET = "com.metrolist.music.widget.playlists.UPDATE_WIDGET"

        const val EXTRA_TARGET_TYPE = "playlist_widget_target_type"
        const val EXTRA_TARGET_ID = "playlist_widget_target_id"
        const val EXTRA_TARGET_TITLE = "playlist_widget_target_title"

        const val TARGET_TYPE_LOCAL = "local"
        const val TARGET_TYPE_ONLINE = "online"
        const val TARGET_TYPE_LIKED = "liked"
        const val TARGET_TYPE_DOWNLOADED = "downloaded"
        const val TARGET_TYPE_COLLECTION = "collection"
        const val TARGET_TYPE_TOP = "top"
    }
}
