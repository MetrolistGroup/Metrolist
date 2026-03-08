package com.metrolist.shared.storage

import com.metrolist.shared.model.SongItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Access is serialized by the download manager's queue mutex. */
internal class IosDownloadQueue(
    encoded: String,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val requests = linkedMapOf<String, IosDownloadQueueRequest>()

    init {
        replace(
            runCatching {
                if (encoded.isBlank()) emptyList() else json.decodeFromString<List<IosDownloadQueueRequest>>(encoded)
            }.getOrDefault(emptyList()).map { it.copy(state = recoverInterruptedAudioDownload(it.state)) },
        )
    }

    fun snapshot(): Map<String, IosDownloadQueueRequest> = requests.toMap()

    fun values(): List<IosDownloadQueueRequest> = requests.values.toList()

    fun replace(queue: List<IosDownloadQueueRequest>) {
        requests.clear()
        queue.filterNot { it.state == AudioDownloadState.COMPLETED }.associateByTo(requests) { it.song.id }
    }

    fun update(
        songId: String,
        transform: (IosDownloadQueueRequest) -> IosDownloadQueueRequest,
    ): Boolean {
        val previous = requests[songId] ?: return false
        val next = transform(previous)
        if (previous == next) return false
        if (next.state == AudioDownloadState.COMPLETED) requests.remove(songId) else requests[songId] = next
        return true
    }

    fun cancelRemovedDownload(
        song: SongItem,
        now: Long,
    ) {
        requests[song.id] = IosDownloadQueueRequest(song, AudioDownloadState.CANCELLED, now)
    }

    fun enqueue(
        songs: List<SongItem>,
        downloaded: Set<String>,
        observed: Map<String, IosDownloadQueueRequest>,
        preserveBlockedRequests: Boolean,
        now: Long,
    ): Int {
        var added = 0
        for (song in songs) {
            if (song.isUnavailable) continue
            val current = requests[song.id]
            if (current != observed[song.id] || song.id in downloaded) continue
            if (current?.state == AudioDownloadState.RUNNING || current?.state == AudioDownloadState.QUEUED) continue
            if (preserveBlockedRequests && current?.let { shouldKeepTrackedPlaylistRequest(it.state) } == true) continue
            requests.remove(song.id)
            requests[song.id] = IosDownloadQueueRequest(song = song, enqueuedAt = now)
            added++
        }
        return added
    }

    fun encode(): String = if (requests.isEmpty()) "" else json.encodeToString(requests.values.toList())
}

@Serializable
internal data class IosDownloadQueueRequest(
    val song: SongItem,
    val state: AudioDownloadState = AudioDownloadState.QUEUED,
    val enqueuedAt: Long,
    val updatedAt: Long = enqueuedAt,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val attemptCount: Int = 0,
    val lastError: String? = null,
)
