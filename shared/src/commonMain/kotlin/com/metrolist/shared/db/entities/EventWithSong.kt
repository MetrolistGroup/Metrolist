/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.entities

import androidx.room3.Embedded
import androidx.room3.Relation

data class EventWithSong(
    @Embedded
    val event: Event,
    @Relation(
        entity = SongEntity::class,
        parentColumns = ["songId"],
        entityColumns = ["id"],
    )
    val songs: List<Song>,
    @Relation(parentColumns = ["id"], entityColumns = ["eventId"])
    val snapshots: List<HistorySnapshot> = emptyList(),
) {
    // Lists avoid Room's iOS ConcurrentModificationException for single-value relations above 999 rows.
    val song: Song
        get() = songs.last()
    val snapshot: HistorySnapshot?
        get() = snapshots.lastOrNull()
}
