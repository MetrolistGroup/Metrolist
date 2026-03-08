package com.metrolist.shared.db

import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class HistoryPlaylistsTest {
    private val now = 1_768_478_400_000L
    private val day = 86_400_000L

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    private fun song(id: String) =
        SongItem(
            id = id,
            title = "Song $id",
            thumbnail = "https://example.com/$id.jpg",
            artists = listOf(ArtistTiny(id = "artist-$id", name = "Artist $id")),
            album = null,
            duration = 100,
        )

    private fun withDatabase(block: suspend (MusicDatabase) -> Unit) =
        runBlocking {
            val path = Files.createTempFile("metrolist-history-playlists", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                Settings.replaceSharedForTest(Settings(MapSettings()))
                block(database)
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    private suspend fun MusicDatabase.play(
        songId: String,
        playedAt: Long,
        playTimeMs: Long = 1_000,
    ) = recordHistoryPlay(song(songId), playedAt, playTimeMs = playTimeMs)

    private suspend fun MusicDatabase.songIds(playlistId: String): List<String> =
        dao().playlistSongs(playlistId).first().map { it.map.songId }

    @Test
    fun generatesWeeklyAndMonthlyByPlayCount() =
        withDatabase { database ->
            Settings.shared.historyPlaylistsEnabled = true
            repeat(3) { database.play("A", now - (it + 1) * day) }
            database.play("B", now - 4 * day)
            repeat(5) { database.play("C", now - 10 * day - it) }

            database.refreshHistoryPlaylists(now)

            assertEquals(listOf("A", "B"), database.songIds(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID))
            assertEquals(listOf("C", "A", "B"), database.songIds(PlaylistEntity.MONTHLY_MOST_PLAYLIST_ID))

            val weekly = database.dao().findPlaylistEntity(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID)
            assertNotNull(weekly)
            assertFalse(weekly.isEditable)
            assertTrue(weekly.isLocal)
            assertEquals("https://example.com/A.jpg", weekly.thumbnailUrl)
        }

    @Test
    fun ranksEqualPlayCountsByListeningTime() =
        withDatabase { database ->
            Settings.shared.historyPlaylistsEnabled = true
            database.play("short", now - day, playTimeMs = 1_000)
            database.play("short", now - 2 * day, playTimeMs = 1_000)
            database.play("long", now - day, playTimeMs = 4_000)
            database.play("long", now - 2 * day, playTimeMs = 4_000)

            database.refreshHistoryPlaylists(now)

            assertEquals(listOf("long", "short"), database.songIds(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID))
        }

    @Test
    fun limitsToThirtyTracks() =
        withDatabase { database ->
            Settings.shared.historyPlaylistsEnabled = true
            repeat(35) { database.play("song$it", now - day) }

            database.refreshHistoryPlaylists(now)

            assertEquals(30, database.songIds(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID).size)
        }

    @Test
    fun disabledSettingRemovesPlaylists() =
        withDatabase { database ->
            Settings.shared.historyPlaylistsEnabled = true
            database.play("A", now - day)
            database.refreshHistoryPlaylists(now)
            assertNotNull(database.dao().findPlaylistEntity(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID))

            Settings.shared.historyPlaylistsEnabled = false
            database.refreshHistoryPlaylists(now)
            assertNull(database.dao().findPlaylistEntity(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID))
            assertNull(database.dao().findPlaylistEntity(PlaylistEntity.MONTHLY_MOST_PLAYLIST_ID))
        }

    @Test
    fun doesNotRegenerateWithinSamePeriodButRefreshesNextWeek() =
        withDatabase { database ->
            Settings.shared.historyPlaylistsEnabled = true
            database.play("A", now - day)
            database.refreshHistoryPlaylists(now)
            assertEquals(listOf("A"), database.songIds(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID))

            database.play("B", now - day)
            database.refreshHistoryPlaylists(now)
            assertEquals(listOf("A"), database.songIds(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID))

            val nextWeek = now + 7 * day
            database.play("B", nextWeek - day)
            database.play("B", nextWeek - 2 * day)
            database.play("A", nextWeek - 3 * day)
            database.refreshHistoryPlaylists(nextWeek)
            assertEquals(listOf("B", "A"), database.songIds(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID))
        }

    @Test
    fun openingHistoryPlaylistRollsOverWithoutOpeningLibrary() =
        withDatabase { database ->
            Settings.shared.historyPlaylistsEnabled = true
            val current = Clock.System.now().toEpochMilliseconds()
            val previous = current - 40 * day
            database.play("old", previous - day)
            database.refreshHistoryPlaylists(previous)
            assertEquals(listOf("old"), database.songIds(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID))

            database.play("current", current - day)
            val offline = database.loadOfflinePlaylist(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID, setOf("current"))
            assertEquals(
                listOf("current"),
                offline
                    .orEmpty()
                    .values
                    .flatten()
                    .filterIsInstance<SongItem>()
                    .map { it.id },
            )
            assertEquals(listOf("current"), database.songIds(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID))
            assertEquals(listOf("current"), database.songIds(PlaylistEntity.MONTHLY_MOST_PLAYLIST_ID))

            Settings.shared.historyPlaylistsEnabled = false
            database.refreshHistoryPlaylist(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID)
            assertNull(database.dao().findPlaylistEntity(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID))
        }

    @Test
    fun emptyHistoryCreatesNoPlaylist() =
        withDatabase { database ->
            Settings.shared.historyPlaylistsEnabled = true
            database.refreshHistoryPlaylists(now)
            assertNull(database.dao().findPlaylistEntity(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID))
            assertNull(database.dao().findPlaylistEntity(PlaylistEntity.MONTHLY_MOST_PLAYLIST_ID))
        }
}
