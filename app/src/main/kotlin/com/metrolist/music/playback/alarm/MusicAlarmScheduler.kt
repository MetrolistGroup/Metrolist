package com.metrolist.music.playback.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.metrolist.music.playback.MusicService
import java.util.Calendar

object MusicAlarmScheduler {
    fun scheduleFromPreferences(context: Context) {
        val alarms = MusicAlarmStore.loadBlocking(context)
        scheduleAll(context, alarms)
    }

    fun scheduleAll(context: Context, alarms: List<MusicAlarmEntry>) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val knownAlarmIds = (MusicAlarmStore.loadBlocking(context).map { it.id } + alarms.map { it.id }).distinct()
        knownAlarmIds.forEach { alarmId ->
            cancel(context, alarmId)
        }
        val updated = alarms.map { alarm ->
            if (!alarm.enabled || alarm.playlistId.isBlank()) {
                alarm.copy(nextTriggerAt = -1L)
            } else {
                val triggerAtMillis = nextTriggerMillis(alarm.hour, alarm.minute)
                val pendingIntent = alarmPendingIntent(context, alarm)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
                alarm.copy(nextTriggerAt = triggerAtMillis)
            }
        }
        MusicAlarmStore.saveBlocking(context, updated)
    }

    fun cancel(context: Context, alarmId: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(alarmPendingIntent(context, alarmId))
        alarmManager.cancel(legacyAlarmPendingIntent(context, alarmId))
    }

    private fun alarmPendingIntent(
        context: Context,
        alarm: MusicAlarmEntry
    ): PendingIntent {
        val intent = Intent(context, MusicService::class.java)
            .setAction(MusicService.ACTION_ALARM_TRIGGER)
            .putExtra(MusicService.EXTRA_ALARM_ID, alarm.id)
            .putExtra(MusicService.EXTRA_ALARM_PLAYLIST_ID, alarm.playlistId)
            .putExtra(MusicService.EXTRA_ALARM_RANDOM_SONG, alarm.randomSong)

        return foregroundServicePendingIntent(context, alarm.id, intent)
    }

    private fun alarmPendingIntent(context: Context, alarmId: String): PendingIntent {
        val intent = Intent(context, MusicService::class.java)
            .setAction(MusicService.ACTION_ALARM_TRIGGER)
            .putExtra(MusicService.EXTRA_ALARM_ID, alarmId)

        return foregroundServicePendingIntent(context, alarmId, intent)
    }

    private fun foregroundServicePendingIntent(
        context: Context,
        alarmId: String,
        intent: Intent
    ): PendingIntent {
        return PendingIntent.getForegroundService(
            context,
            requestCode(alarmId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun legacyAlarmPendingIntent(context: Context, alarmId: String): PendingIntent {
        val intent = Intent(context, MusicAlarmReceiver::class.java)
            .setAction(MusicAlarmReceiver.ACTION_TRIGGER_ALARM)
            .putExtra(MusicService.EXTRA_ALARM_ID, alarmId)

        return PendingIntent.getBroadcast(
            context,
            requestCode(alarmId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun requestCode(alarmId: String): Int {
        return alarmId.hashCode() and Int.MAX_VALUE
    }

    private fun nextTriggerMillis(hour: Int, minute: Int): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }
}
