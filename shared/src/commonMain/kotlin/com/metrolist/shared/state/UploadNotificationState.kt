package com.metrolist.shared.state

import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

object UploadNotificationState {
    private val _hasNewUploads = MutableStateFlow(Settings.shared.hasNewUploads)
    val hasNewUploads = _hasNewUploads.asStateFlow()

    suspend fun refreshOnAppOpen(repository: YouTubeRepository) {
        val accountKey =
            Settings.shared.accountChannelHandle
                .trim()
                .lowercase()
        if (accountKey.isBlank() || Settings.shared.innerTubeCookie.isBlank()) return
        val currentSongs = repository.getLibraryUploadSongsResult(limit = 500).getOrNull() ?: return
        val currentIds = currentSongs.map { it.id }.distinct()
        if (currentIds.isEmpty()) return
        val knownByAccount =
            runCatching { Json.decodeFromString<Map<String, List<String>>>(Settings.shared.knownUploadIdsJson) }
                .getOrDefault(emptyMap())
                .toMutableMap()
        val knownIds = knownByAccount[accountKey].orEmpty().toSet()
        if (knownIds.isNotEmpty() && currentIds.any { it !in knownIds }) {
            Settings.shared.hasNewUploads = true
            _hasNewUploads.value = true
        }
        knownByAccount[accountKey] = currentIds
        Settings.shared.knownUploadIdsJson = Json.encodeToString(knownByAccount)
    }

    fun markSeen() {
        Settings.shared.hasNewUploads = false
        _hasNewUploads.value = false
    }
}
