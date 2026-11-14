package com.metrolist.shared.playlist

import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.librarySearchKey
import com.metrolist.shared.settings.Settings
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

object LibraryDeletionTracker {
    private const val DELETION_SUPPRESSION_TIMEOUT_MILLIS = 10 * 60 * 1000L
    private val lock = SynchronizedObject()
    private val deletedKeysWithTimestamp = mutableMapOf<String, Long>()
    private var loaded = false

    private fun ensureLoaded() {
        if (!loaded) {
            loaded = true
            try {
                val json = Settings.shared.deletedPlaylistIdsJson
                if (json.isNotBlank() && json != "[]" && json != "{}") {
                    val parsed: Map<String, Long> =
                        try {
                            Json.decodeFromString<Map<String, Long>>(json)
                        } catch (_: Exception) {
                            try {
                                Json.decodeFromString<List<String>>(json).associateWith { 0L }
                            } catch (_: Exception) {
                                emptyMap()
                            }
                        }
                    val now = Clock.System.now().toEpochMilliseconds()
                    parsed.forEach { (key, timestamp) ->
                        if (now - timestamp <= DELETION_SUPPRESSION_TIMEOUT_MILLIS && timestamp > 0L) {
                            deletedKeysWithTimestamp[key] = timestamp
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun persist() {
        try {
            Settings.shared.deletedPlaylistIdsJson = Json.encodeToString(deletedKeysWithTimestamp)
        } catch (_: Exception) {
        }
    }

    fun trackDeletedPlaylist(
        id: String,
        timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    ) = synchronized(lock) {
        ensureLoaded()
        deletedKeysWithTimestamp["playlist:${id.removePrefix("VL")}"] = timestamp
        persist()
    }

    fun trackDeletedSong(id: String) =
        synchronized(lock) {
            ensureLoaded()
            deletedKeysWithTimestamp["song:$id"] = Clock.System.now().toEpochMilliseconds()
            persist()
        }

    fun isDeleted(
        key: String,
        now: Long = Clock.System.now().toEpochMilliseconds(),
    ): Boolean =
        synchronized(lock) {
            ensureLoaded()
            val timestamp = deletedKeysWithTimestamp[key] ?: return@synchronized false
            if (now - timestamp > DELETION_SUPPRESSION_TIMEOUT_MILLIS) {
                deletedKeysWithTimestamp.remove(key)
                persist()
                return@synchronized false
            }
            true
        }

    fun isDeleted(
        item: YTItem,
        now: Long = Clock.System.now().toEpochMilliseconds(),
    ): Boolean = isDeleted(item.librarySearchKey(), now)

    fun filterDeleted(
        items: List<YTItem>,
        now: Long = Clock.System.now().toEpochMilliseconds(),
    ): List<YTItem> =
        synchronized(lock) {
            ensureLoaded()
            items.filterNot { isDeleted(it, now) }
        }

    fun reconcile(currentRemoteKeys: Set<String>) =
        synchronized(lock) {
            ensureLoaded()
            if (deletedKeysWithTimestamp.isEmpty()) return@synchronized
            val iterator = deletedKeysWithTimestamp.entries.iterator()
            var changed = false
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in currentRemoteKeys) {
                    iterator.remove()
                    changed = true
                }
            }
            if (changed) {
                persist()
            }
        }

    fun clear() =
        synchronized(lock) {
            ensureLoaded()
            deletedKeysWithTimestamp.clear()
            persist()
        }
}
