package com.metrolist.shared.db

import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_CACHE
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.db.entities.SongAlbumMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AlbumDownloadQueriesTest {
    @Test
    fun completeAlbumTracksDoNotRequirePreviouslyEncounteredVideoVersions() =
        runBlocking {
            val directory = Files.createTempDirectory("album-download-video-versions")
            val database = getDesktopDatabaseBuilder(directory.resolve("music.db").toFile()).build()
            try {
                val dao = database.dao()
                val album = AlbumItem(id = "album", title = "Album", thumbnail = null, artists = emptyList())
                val tracks =
                    listOf("audio-1", "audio-2").map { id ->
                        SongItem(id, id, null, emptyList(), album = AlbumTiny(album.id, album.title), duration = 180)
                    }
                database.storeAlbumPage(AlbumPage(album, tracks))
                dao.upsert(SongEntity(id = "video-1", title = "Video version", albumId = album.id))

                suspend fun assertComplete(expected: Boolean) {
                    assertEquals(expected, dao.isAlbumDownloaded(album.id).first())
                    val keys = if (expected) listOf("album:${album.id}") else emptyList()
                    assertEquals(keys, dao.downloadedCollectionKeys().first())
                    assertEquals(keys, dao.downloadedCollectionKeys(dao.cachedAudioIdsByKind(KIND_DOWNLOAD).first()).first())
                }

                tracks.forEach { song ->
                    dao.upsert(
                        CachedAudioEntity(
                            songId = song.id,
                            title = song.title,
                            artist = "Artist",
                            filePath = "/${song.id}",
                            fileSize = 10,
                            cachedAt = 1,
                            lastAccessedAt = 1,
                            cacheLocation = "internal",
                            kind = KIND_DOWNLOAD,
                        ),
                    )
                }
                assertComplete(true)
                assertNull(dao.cachedAudio("video-1"))
                dao.deleteCachedAudio("audio-2")
                assertComplete(false)
                dao.upsert(dao.getSongById("audio-2")!!.song.copy(isUnavailable = true))
                assertComplete(true)
                dao.upsert(dao.getSongById("audio-1")!!.song.copy(isUnavailable = true))
                assertComplete(false)
            } finally {
                database.close()
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun downloadsUseMappedAndCanonicalMembershipWithoutDoubleCounting() =
        runBlocking {
            val directory = Files.createTempDirectory("album-download-queries")
            val database = getDesktopDatabaseBuilder(directory.resolve("music.db").toFile()).build()
            try {
                val dao = database.dao()
                val album = AlbumEntity(id = "album", title = "Album", songCount = 4, duration = 0)
                dao.upsert(album)
                dao.upsert(album.copy(id = "other", songCount = 0))
                dao.upsert(album.copy(id = "empty"))
                val songs =
                    listOf(
                        SongEntity(id = "mapped-null", title = "Mapped"),
                        SongEntity(id = "mapped-other", title = "Mapped elsewhere", albumId = "other"),
                        SongEntity(id = "both", title = "Both", albumId = album.id),
                        SongEntity(id = "canonical", title = "Canonical", albumId = album.id),
                    )
                songs.forEach { dao.upsert(it) }
                songs.take(3).forEachIndexed { index, song -> dao.upsert(SongAlbumMap(song.id, album.id, index)) }
                dao.upsert(SongEntity(id = "unrelated", title = "Unrelated"))

                fun download(id: String) =
                    CachedAudioEntity(
                        songId = id,
                        title = id,
                        artist = "Artist",
                        filePath = "/$id",
                        fileSize = 10,
                        cachedAt = 1,
                        lastAccessedAt = 1,
                        cacheLocation = "internal",
                        kind = KIND_DOWNLOAD,
                    )

                val ids = songs.map { it.id }
                val membership =
                    dao
                        .storedAlbumSongs(album.id)
                        .first()
                        .map { it.id }
                        .toSet()
                assertEquals(ids.toSet(), membership)

                suspend fun assertDownloads(
                    expectedIds: Set<String>,
                    complete: Boolean,
                ) {
                    val removalIds = dao.albumDownloadIds(album.id)
                    assertEquals(expectedIds, removalIds.toSet())
                    assertEquals(expectedIds.size, removalIds.size)
                    assertEquals(complete, dao.isAlbumDownloaded(album.id).first())
                    val expectedKeys = if (complete) setOf("album:album") else emptySet()
                    assertEquals(expectedKeys, dao.downloadedCollectionKeys().first().toSet())
                    assertEquals(expectedKeys, dao.downloadedCollectionKeys((expectedIds + "unrelated").toList()).first().toSet())
                }

                dao.upsert(download("unrelated"))
                assertDownloads(emptySet(), complete = false)
                ids.dropLast(1).forEach { dao.upsert(download(it)) }
                assertDownloads(ids.dropLast(1).toSet(), complete = false)
                dao.upsert(download(ids.last()))
                assertDownloads(membership, complete = true)

                dao.upsert(album.copy(songCount = 5))
                assertDownloads(membership, complete = false)
                dao.upsert(album)
                for (invalid in listOf(download(ids.first()).copy(kind = KIND_CACHE), download(ids.first()).copy(fileSize = 0))) {
                    dao.upsert(invalid)
                    assertDownloads(membership - ids.first(), complete = false)
                }
                dao.upsert(download(ids.first()))
                assertDownloads(membership, complete = true)

                val incomplete = async { withTimeout(5_000) { dao.isAlbumDownloaded(album.id).first { !it } } }
                val noBadge = async { withTimeout(5_000) { dao.downloadedCollectionKeys().first { it.isEmpty() } } }
                val noExternalBadge = async { withTimeout(5_000) { dao.downloadedCollectionKeys(ids).first { it.isEmpty() } } }
                dao.upsert(SongEntity(id = "missing", title = "Missing"))
                dao.upsert(SongAlbumMap("missing", album.id, 4))
                assertFalse(incomplete.await())
                assertEquals(emptyList(), noBadge.await())
                assertEquals(emptyList(), noExternalBadge.await())
                assertDownloads(membership, complete = false)
                assertFalse(dao.isAlbumDownloaded("empty").first())
                assertFalse(dao.isAlbumDownloaded("absent").first())
                assertEquals(emptyList(), dao.albumDownloadIds("absent"))
            } finally {
                database.close()
                directory.toFile().deleteRecursively()
            }
        }
}
