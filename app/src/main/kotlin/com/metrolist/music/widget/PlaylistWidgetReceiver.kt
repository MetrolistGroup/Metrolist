/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import com.metrolist.music.MainActivity
import com.metrolist.music.playback.MusicService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import timber.log.Timber

@AndroidEntryPoint
class PlaylistWidgetReceiver : AppWidgetProvider() {
    @Inject
    lateinit var playlistWidgetManager: PlaylistWidgetManager

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        refreshIdleWidgets(appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        refreshIdleWidget(appWidgetId, newOptions)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_PLAY_TARGET -> {
                val serviceIntent = Intent(context, MusicService::class.java).apply {
                    action = ACTION_PLAY_TARGET
                    putExtra(EXTRA_TARGET_TYPE, intent.getStringExtra(EXTRA_TARGET_TYPE))
                    putExtra(EXTRA_TARGET_ID, intent.getStringExtra(EXTRA_TARGET_ID))
                    putExtra(EXTRA_TARGET_TITLE, intent.getStringExtra(EXTRA_TARGET_TITLE))
                }
                try {
                    context.startService(serviceIntent)
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to start playlist widget target")
                    openTargetInApp(context, intent)
                }
            }

            ACTION_UPDATE_WIDGET -> refreshIdleWidgets()
        }
    }

    private fun refreshIdleWidgets(
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                appWidgetIds.forEach { appWidgetId ->
                    playlistWidgetManager.updateIdleWidget(
                        appWidgetId = appWidgetId,
                        options = appWidgetManager.getAppWidgetOptions(appWidgetId),
                    )
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to refresh playlist widgets")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun refreshIdleWidgets() {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                playlistWidgetManager.updateIdleWidgets()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to refresh playlist widgets")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun refreshIdleWidget(appWidgetId: Int, options: android.os.Bundle) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                playlistWidgetManager.updateIdleWidget(appWidgetId, options)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to refresh playlist widget")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun openTargetInApp(context: Context, source: Intent) {
        val activityIntent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_WIDGET_TARGET
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(
                MainActivity.EXTRA_WIDGET_TARGET_TYPE,
                source.getStringExtra(EXTRA_TARGET_TYPE),
            )
            putExtra(
                MainActivity.EXTRA_WIDGET_TARGET_ID,
                source.getStringExtra(EXTRA_TARGET_ID),
            )
        }
        context.startActivity(activityIntent)
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
        const val TARGET_TYPE_TOP = "top"
    }
}
