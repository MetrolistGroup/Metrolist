/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.daos

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.metrolist.shared.db.entities.SpeedDialItem
import com.metrolist.shared.model.speedDialItemId
import kotlinx.coroutines.flow.Flow

@Dao
interface SpeedDialDao {
    @Query("SELECT * FROM speed_dial ORDER BY `index` ASC, createDate ASC, id ASC")
    fun getAll(): Flow<List<SpeedDialItem>>

    @Query("SELECT * FROM speed_dial ORDER BY `index` ASC, createDate ASC, id ASC")
    suspend fun getAllOnce(): List<SpeedDialItem>

    @Query("SELECT EXISTS(SELECT 1 FROM speed_dial WHERE id = :id)")
    fun isPinned(id: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SpeedDialItem)

    @Query("DELETE FROM speed_dial WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM speed_dial")
    suspend fun deleteAll()

    @Query("UPDATE speed_dial SET title = :title WHERE id = :id AND type IN ('PLAYLIST', 'LOCAL_PLAYLIST')")
    suspend fun updatePlaylistTitle(
        id: String,
        title: String,
    )

    @Query("UPDATE speed_dial SET thumbnailUrl = :thumbnail WHERE id = :id AND type = 'PLAYLIST'")
    suspend fun updatePlaylistThumbnail(
        id: String,
        thumbnail: String,
    )

    @Transaction
    suspend fun pin(item: SpeedDialItem) {
        val current = getAllOnce()
        val existing = current.firstOrNull { speedDialItemId(it.id) == speedDialItemId(item.id) }
        if (existing == null && current.size >= MAX_PINNED_ITEMS) return
        val updated =
            if (existing == null) {
                current + item
            } else {
                current.map { stored ->
                    if (stored.id == existing.id) {
                        item.copy(id = stored.id, index = stored.index, createDate = stored.createDate)
                    } else {
                        stored
                    }
                }
            }
        writeOrder(updated)
    }

    @Transaction
    suspend fun unpin(id: String) {
        val current = getAllOnce()
        val removed = current.filter { speedDialItemId(it.id) == speedDialItemId(id) }
        val updated = current - removed.toSet()
        removed.forEach { delete(it.id) }
        writeOrder(updated)
    }

    @Transaction
    suspend fun moveEarlier(id: String) {
        move(id, -1)
    }

    @Transaction
    suspend fun moveLater(id: String) {
        move(id, 1)
    }

    private suspend fun move(
        id: String,
        offset: Int,
    ) {
        val current = getAllOnce()
        val fromIndex = current.indexOfFirst { speedDialItemId(it.id) == speedDialItemId(id) }
        if (fromIndex == -1) return
        val toIndex = (fromIndex + offset).coerceIn(current.indices)
        if (fromIndex == toIndex) return
        val updated =
            current.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
        writeOrder(updated)
    }

    private suspend fun writeOrder(items: List<SpeedDialItem>) {
        items.forEachIndexed { index, item ->
            insert(item.copy(index = index))
        }
    }

    companion object {
        const val MAX_PINNED_ITEMS = 27
    }
}
