package com.metrolist.music.playback.alarm

import android.content.Context
import android.os.Build
import com.metrolist.music.constants.AlarmEnabledKey
import com.metrolist.music.constants.AlarmEntriesKey
import com.metrolist.music.constants.AlarmHourKey
import com.metrolist.music.constants.AlarmMinuteKey
import com.metrolist.music.constants.AlarmNextTriggerAtKey
import com.metrolist.music.constants.AlarmPlaylistIdKey
import com.metrolist.music.constants.AlarmRandomSongKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class MusicAlarmEntry(
    val id: String,
    val enabled: Boolean,
    val hour: Int,
    val minute: Int,
    val playlistId: String,
    val randomSong: Boolean,
    val nextTriggerAt: Long = -1L
)

object MusicAlarmStore {
    private const val ALARM_PREFS = "alarm_store"
    private const val ALARM_PREFS_ENTRIES = "alarm_entries"

    suspend fun load(context: Context): List<MusicAlarmEntry> {
        val protectedRaw = alarmPrefsContext(context).getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE)
            .getString(ALARM_PREFS_ENTRIES, null)
            .orEmpty()
        if (protectedRaw.isNotBlank()) {
            parse(protectedRaw)?.let { return it }
        }

        return runCatching {
            val prefs = context.dataStore.data.first()
            val raw = prefs[AlarmEntriesKey].orEmpty()
            if (raw.isNotBlank()) {
                parse(raw)
                    ?: migrateLegacy(
                        prefs[AlarmEnabledKey] ?: false,
                        prefs[AlarmHourKey] ?: 7,
                        prefs[AlarmMinuteKey] ?: 0,
                        prefs[AlarmPlaylistIdKey].orEmpty(),
                        prefs[AlarmRandomSongKey] ?: false,
                        prefs[AlarmNextTriggerAtKey] ?: -1L
                    )
            } else {
                migrateLegacy(prefs[AlarmEnabledKey] ?: false, prefs[AlarmHourKey] ?: 7, prefs[AlarmMinuteKey] ?: 0, prefs[AlarmPlaylistIdKey].orEmpty(), prefs[AlarmRandomSongKey] ?: false, prefs[AlarmNextTriggerAtKey] ?: -1L)
            }
        }.getOrElse {
            emptyList()
        }.also { entries ->
            if (entries.isNotEmpty()) {
                saveProtected(context, entries)
            }
        }
    }

    fun loadBlocking(context: Context): List<MusicAlarmEntry> {
        return runBlocking {
            load(context)
        }
    }

    suspend fun save(context: Context, entries: List<MusicAlarmEntry>) {
        saveProtected(context, entries)
        context.safeDataStoreEdit { prefs ->
            prefs[AlarmEntriesKey] = serialize(entries)
            prefs[AlarmNextTriggerAtKey] = entries.filter { it.enabled }.minOfOrNull { it.nextTriggerAt.takeIf { time -> time > 0L } ?: Long.MAX_VALUE }
                ?.takeIf { it != Long.MAX_VALUE } ?: -1L
        }
    }

    fun saveBlocking(context: Context, entries: List<MusicAlarmEntry>) {
        runBlocking {
            save(context, entries)
        }
    }

    fun createEmpty(): MusicAlarmEntry {
        return MusicAlarmEntry(
            id = UUID.randomUUID().toString(),
            enabled = true,
            hour = 7,
            minute = 0,
            playlistId = "",
            randomSong = false,
            nextTriggerAt = -1L
        )
    }

    private fun migrateLegacy(
        enabled: Boolean,
        hour: Int,
        minute: Int,
        playlistId: String,
        randomSong: Boolean,
        nextTriggerAt: Long
    ): List<MusicAlarmEntry> {
        if (playlistId.isBlank()) return emptyList()
        return listOf(
            MusicAlarmEntry(
                id = "legacy-main-alarm",
                enabled = enabled,
                hour = hour,
                minute = minute,
                playlistId = playlistId,
                randomSong = randomSong,
                nextTriggerAt = nextTriggerAt
            )
        )
    }

    private fun serialize(entries: List<MusicAlarmEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("enabled", entry.enabled)
                    .put("hour", entry.hour)
                    .put("minute", entry.minute)
                    .put("playlistId", entry.playlistId)
                    .put("randomSong", entry.randomSong)
                    .put("nextTriggerAt", entry.nextTriggerAt)
            )
        }
        return array.toString()
    }

    private fun parse(raw: String): List<MusicAlarmEntry>? {
        val array = runCatching { JSONArray(raw) }.getOrElse { return null }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                runCatching {
                    MusicAlarmEntry(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        enabled = item.optBoolean("enabled", true),
                        hour = item.optInt("hour", 7).coerceIn(0, 23),
                        minute = item.optInt("minute", 0).coerceIn(0, 59),
                        playlistId = item.optString("playlistId"),
                        randomSong = item.optBoolean("randomSong", false),
                        nextTriggerAt = item.optLong("nextTriggerAt", -1L)
                    )
                }.getOrNull()?.let(::add)
            }
        }
    }

    private fun saveProtected(context: Context, entries: List<MusicAlarmEntry>) {
        alarmPrefsContext(context)
            .getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ALARM_PREFS_ENTRIES, serialize(entries))
            .apply()
    }

    private fun alarmPrefsContext(context: Context): Context {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
    }
}
