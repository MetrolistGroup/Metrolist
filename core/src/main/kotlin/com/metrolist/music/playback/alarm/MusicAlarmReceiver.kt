package com.metrolist.music.playback.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.metrolist.music.playback.MusicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MusicAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TRIGGER_ALARM) return
        val pendingResult = goAsync()
        val alarmId = intent.getStringExtra(MusicService.EXTRA_ALARM_ID).orEmpty()
        val playlistId = intent.getStringExtra(MusicService.EXTRA_ALARM_PLAYLIST_ID).orEmpty()
        val randomSong = intent.getBooleanExtra(MusicService.EXTRA_ALARM_RANDOM_SONG, false)
        val serviceIntent = Intent(context, MusicService::class.java)
            .setAction(MusicService.ACTION_ALARM_TRIGGER)
            .putExtra(MusicService.EXTRA_ALARM_ID, alarmId)
            .putExtra(MusicService.EXTRA_ALARM_PLAYLIST_ID, playlistId)
            .putExtra(MusicService.EXTRA_ALARM_RANDOM_SONG, randomSong)
        ContextCompat.startForegroundService(context, serviceIntent)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                MusicAlarmScheduler.scheduleFromPreferences(context)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_TRIGGER_ALARM = "com.metrolist.music.action.TRIGGER_ALARM"
    }
}
