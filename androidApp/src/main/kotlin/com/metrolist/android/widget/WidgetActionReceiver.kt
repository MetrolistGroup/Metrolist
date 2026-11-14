package com.metrolist.android.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.metrolist.android.App
import com.metrolist.android.playback.MusicService
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.refreshHistoryPlaylist
import com.metrolist.shared.model.BuiltinPlaylist
import com.metrolist.shared.state.SongLikeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import timber.log.Timber
import java.util.UUID

internal const val EXTRA_WIDGET_COMMAND_TOKEN = "com.metrolist.android.widget.COMMAND_TOKEN"
internal val WIDGET_COMMAND_TOKEN: String = UUID.randomUUID().toString()

class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val serviceAction =
            when (intent.action) {
                MusicWidgetReceiver.ACTION_PLAY_PAUSE,
                MusicWidgetReceiver.ACTION_LIKE,
                MusicWidgetReceiver.ACTION_NEXT,
                MusicWidgetReceiver.ACTION_PREVIOUS,
                MusicWidgetReceiver.ACTION_UPDATE_WIDGET,
                PlaylistWidgetReceiver.ACTION_PLAY_TARGET,
                -> intent.action

                TurntableWidgetReceiver.ACTION_TURNTABLE_PLAY_PAUSE -> MusicWidgetReceiver.ACTION_PLAY_PAUSE

                TurntableWidgetReceiver.ACTION_TURNTABLE_NEXT -> MusicWidgetReceiver.ACTION_NEXT

                TurntableWidgetReceiver.ACTION_TURNTABLE_PREVIOUS -> MusicWidgetReceiver.ACTION_PREVIOUS

                TurntableWidgetReceiver.ACTION_UPDATE_TURNTABLE_WIDGET -> MusicWidgetReceiver.ACTION_UPDATE_WIDGET

                else -> null
            } ?: return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val app = context.applicationContext as App
                app.startup.await()
                if (!app.onboardingManager.isComplete) return@launch
                if (serviceAction == MusicWidgetReceiver.ACTION_UPDATE_WIDGET) {
                    if (MusicService.isRunning) {
                        context.startService(Intent(context, MusicService::class.java).setAction(serviceAction))
                    } else {
                        val widgets = AppWidgetManager.getInstance(context)
                        val manager = GlobalContext.get().get<MetrolistWidgetManager>()
                        manager.updateIdleMusicWidgets(widgets.getAppWidgetIds(ComponentName(context, MusicWidgetReceiver::class.java)))
                        manager.updateIdleTurntableWidgets(
                            widgets.getAppWidgetIds(ComponentName(context, TurntableWidgetReceiver::class.java)),
                        )
                    }
                    return@launch
                }
                val connection = GlobalContext.get().get<PlayerConnection>()
                when (serviceAction) {
                    MusicWidgetReceiver.ACTION_PLAY_PAUSE -> {
                        connection.togglePlayPauseFromWidget()
                    }

                    MusicWidgetReceiver.ACTION_NEXT -> {
                        connection.skipToNext()
                    }

                    MusicWidgetReceiver.ACTION_PREVIOUS -> {
                        connection.skipToPrevious()
                    }

                    MusicWidgetReceiver.ACTION_LIKE -> {
                        connection.awaitConnected()
                        connection.currentSong.value?.let(SongLikeState::toggleLike)
                    }

                    PlaylistWidgetReceiver.ACTION_PLAY_TARGET -> {
                        val id = intent.getStringExtra(PlaylistWidgetReceiver.EXTRA_TARGET_ID) ?: return@launch
                        val title = intent.getStringExtra(PlaylistWidgetReceiver.EXTRA_TARGET_TITLE)
                        val type =
                            intent.getStringExtra(PlaylistWidgetReceiver.EXTRA_TARGET_TYPE)
                        if (type == PlaylistWidgetReceiver.TARGET_TYPE_ONLINE && BuiltinPlaylist.fromId(id) == null) {
                            connection.playPlaylist(id, title.orEmpty())
                        } else {
                            val songs =
                                withContext(Dispatchers.IO) {
                                    val database = GlobalContext.get().get<MusicDatabase>()
                                    val builtin = widgetBuiltinPlaylist(type, id)
                                    if (builtin != null) {
                                        database.widgetBuiltinSongs(builtin)
                                    } else if (type == PlaylistWidgetReceiver.TARGET_TYPE_LOCAL) {
                                        database.refreshHistoryPlaylist(id)
                                        database
                                            .dao()
                                            .playlistSongs(id)
                                            .first()
                                            .map { it.song.toSongItem() }
                                    } else {
                                        emptyList()
                                    }
                                }
                            if (songs.isNotEmpty()) connection.playQueue(songs, queueTitle = title, finite = true)
                        }
                    }
                }
            } catch (error: Exception) {
                Timber.w(error, "Widget action could not start playback")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
