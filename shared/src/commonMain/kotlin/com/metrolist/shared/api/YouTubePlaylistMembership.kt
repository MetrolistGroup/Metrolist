package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class PlaylistMembership(
    val ids: Set<String>,
    val complete: Boolean,
    val online: Boolean,
)

internal class YouTubePlaylistMembership(
    private val nowMs: () -> Long,
) {
    internal class Entry {
        val mutex = Mutex()
        var readers = 0
        var revision = 0L
        var observedAt = 0L
        var membership: PlaylistMembership? = null
    }

    internal data class Snapshot(
        val entry: Entry,
        val revision: Long,
        val observedAt: Long,
    )

    private val lock = SynchronizedObject()
    private val entries = linkedMapOf<Pair<InnerTube.SessionSnapshot, String>, Entry>()

    private fun entry(
        session: InnerTube.SessionSnapshot,
        playlistId: String,
        acquire: Boolean = false,
    ): Entry =
        synchronized(lock) {
            entries.keys.removeAll { it.first != session }
            entries.getOrPut(session to playlistId.removePrefix("VL")) { Entry() }.also {
                if (acquire) it.readers++
                while (entries.size > 64) {
                    val removable = entries.entries.firstOrNull { it.value.readers == 0 } ?: break
                    entries.remove(removable.key)
                }
            }
        }

    fun snapshot(
        session: InnerTube.SessionSnapshot,
        playlistId: String,
    ): Snapshot = synchronized(lock) { entry(session, playlistId).let { Snapshot(it, it.revision, nowMs()) } }

    fun remember(
        snapshot: Snapshot,
        membership: PlaylistMembership,
    ) = synchronized(lock) {
        if (snapshot.entry.revision == snapshot.revision && membership.online && snapshot.observedAt >= snapshot.entry.observedAt) {
            snapshot.entry.membership = membership
            snapshot.entry.observedAt = snapshot.observedAt
        }
    }

    private fun fresh(entry: Entry): PlaylistMembership? = entry.membership.takeIf { nowMs() - entry.observedAt in 0 until 5 * 60_000L }

    suspend fun get(
        session: InnerTube.SessionSnapshot,
        playlistId: String,
        songId: String?,
        load: suspend () -> PlaylistMembership,
    ): PlaylistMembership {
        val entry = entry(session, playlistId, acquire = true)
        return try {
            entry.mutex.withLock {
                var result: PlaylistMembership? = null
                while (result == null) {
                    val snapshot =
                        synchronized(lock) {
                            fresh(entry)?.let { if (it.complete || (songId != null && songId in it.ids)) return@withLock it }
                            Snapshot(entry, entry.revision, nowMs())
                        }
                    val loaded = load()
                    synchronized(lock) {
                        if (entry.revision == snapshot.revision) {
                            remember(snapshot, loaded)
                            result = loaded
                        }
                    }
                }
                result
            }
        } finally {
            synchronized(lock) { entry.readers-- }
        }
    }

    fun added(
        session: InnerTube.SessionSnapshot,
        playlistId: String,
        songId: String,
    ) = synchronized(lock) {
        val entry = entry(session, playlistId)
        val previous = fresh(entry)
        entry.revision++
        entry.membership = PlaylistMembership(previous?.ids.orEmpty() + songId, previous?.complete == true, online = true)
        if (previous == null) entry.observedAt = nowMs()
    }

    fun invalidate(
        session: InnerTube.SessionSnapshot,
        playlistId: String,
    ) = synchronized(lock) {
        val entry = entry(session, playlistId)
        entry.revision++
        entry.membership = null
    }
}
