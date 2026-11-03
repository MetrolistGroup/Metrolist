package com.metrolist.music.ui.utils

import com.metrolist.music.db.DatabaseDao

internal data class PendingOffsetEdit(
    val songId: String,
    val offset: Int,
)

/**
 * Keeps a pending lyrics-offset write paired with the song it belongs to.
 * Playback can move to another song before the debounce or dismissal runs,
 * so the owner must never be read from the live player at persist time.
 */
internal class OffsetEditTracker(initialSongId: String?) {
    var songId: String? = initialSongId
        private set

    var pending: PendingOffsetEdit? = null
        private set

    fun record(offset: Int) {
        val owner = songId ?: return
        pending = PendingOffsetEdit(owner, offset)
    }

    /** Switches to [newSongId] and returns the old pair that still needs flushing. */
    fun switchSong(newSongId: String?): PendingOffsetEdit? {
        val flush = pending
        pending = null
        songId = newSongId
        return flush
    }

    /** Returns [edit] only if it is still the latest pending pair. */
    fun takeIfCurrent(edit: PendingOffsetEdit): PendingOffsetEdit? {
        if (pending != edit) return null
        pending = null
        return edit
    }

    fun takePending(): PendingOffsetEdit? {
        val edit = pending
        pending = null
        return edit
    }
}

/** Writes [edit] to the song it was recorded for, never to the current song. */
internal fun persistOffsetEdit(dao: DatabaseDao, edit: PendingOffsetEdit) {
    val target = dao.getSongByIdBlocking(edit.songId)?.song ?: return
    if (target.lyricsOffset != edit.offset) {
        dao.upsert(target.copy(lyricsOffset = edit.offset))
    }
}
