package com.metrolist.shared.db.daos

import androidx.room3.Dao
import androidx.room3.RoomWarnings

@Dao
@Suppress(RoomWarnings.QUERY_MISMATCH)
interface DatabaseDao :
    SongDao,
    PlaylistDao,
    HistoryDao,
    CacheDao
