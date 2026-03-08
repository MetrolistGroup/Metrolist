package com.metrolist.shared.db

import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_CACHE
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class CachedAudioIdsTest {
    @Test
    fun idsIncludeCacheAndDownloadsAndUpdateAfterEviction() =
        runBlocking {
            val path = Files.createTempFile("metrolist-cache-ids", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val dao = database.dao()
                assertEquals(emptyList(), dao.cachedAudioIds().first())
                listOf(KIND_CACHE, KIND_DOWNLOAD).forEachIndexed { index, kind ->
                    dao.upsert(
                        CachedAudioEntity(
                            songId = "song-$index",
                            title = "Song",
                            artist = "Artist",
                            filePath = "/cache/song-$index",
                            fileSize = 100,
                            cachedAt = index.toLong(),
                            lastAccessedAt = 0,
                            cacheLocation = "internal",
                            kind = kind,
                        ),
                    )
                }
                assertEquals(setOf("song-0", "song-1"), dao.cachedAudioIds().first().toSet())
                val afterEviction =
                    async {
                        withTimeout(5_000) { dao.cachedAudioIds().first { it == listOf("song-1") } }
                    }
                dao.deleteCachedAudio("song-0")
                assertEquals(listOf("song-1"), afterEviction.await())
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }
}
