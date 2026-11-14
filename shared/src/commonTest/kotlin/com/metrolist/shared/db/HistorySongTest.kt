package com.metrolist.shared.db

import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.Event
import com.metrolist.shared.db.entities.EventWithSong
import com.metrolist.shared.db.entities.HistorySnapshot
import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.db.entities.SongArtistMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.entities.knownPlayTimeMs
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class HistorySongTest {
    @Test
    fun snapshotPresentationDoesNotReuseEditedCatalogMetadata() {
        val catalog =
            Song(
                SongEntity("song", "Catalog title", customTitle = "Edited title", customArtist = "Edited artist", albumId = "new-album"),
                listOf(ArtistEntity("new-artist", "New artist")),
            )
        val row =
            EventWithSong(
                Event(1, catalog.id, LocalDateTime(2026, 9, 1, 12, 0), 0, playTimeKnown = false),
                catalog,
                HistorySnapshot(1, "Original title", "Earth, Wind & Fire", "Original album", 180, "old-art"),
            )
        val presented = row.withHistoricalMetadata().song.toSongItem()
        assertEquals("Original title", presented.title)
        assertEquals(listOf("Earth, Wind & Fire"), presented.artists.map { it.name })
        assertEquals(AlbumTiny(null, "Original album"), presented.album)
        assertEquals(180L, presented.duration)
        assertEquals("old-art", presented.thumbnail)
        assertEquals("Edited title", catalog.toSongItem().title)
        assertEquals(catalog, row.copy(snapshot = null).withHistoricalMetadata().song)
    }

    @Test
    fun artistIdentityAndAlbumIdKeepCreditsTogether() {
        val timestamp = LocalDateTime(2026, 9, 1, 12, 0)
        val lead = ArtistEntity("lead", "Earth, Wind & Fire")
        val guest = ArtistEntity("guest", "Guest")
        val original = Song(SongEntity("one", "Track one", albumId = "album-id", albumName = "An album"), listOf(lead))
        val featured = Song(SongEntity("two", "Track two", albumId = "album-id", albumName = "An album"), listOf(lead, guest))
        val events =
            listOf(
                EventWithSong(Event(1, original.id, timestamp, 30_000), original),
                EventWithSong(Event(2, featured.id, timestamp, 20_000), featured),
            )
        assertEquals(listOf(lead to 2, guest to 1), historyArtistStats(events))
        val album = historyAlbumStats(events).single()
        assertEquals("album-id", album.id)
        assertEquals(2, album.playCount)
        assertEquals(50_000L, album.playTime)
        assertEquals(setOf(lead.id, guest.id), album.artists.map { it.id }.toSet())

        val legacyOne = original.copy(song = original.song.copy(id = "legacy-one", albumId = null))
        val legacyTwo = featured.copy(song = featured.song.copy(id = "legacy-two", albumId = null))
        val legacyEvents =
            listOf(
                EventWithSong(Event(3, legacyOne.id, timestamp, 1_000), legacyOne),
                EventWithSong(Event(4, legacyTwo.id, timestamp, 2_000), legacyTwo),
            )
        assertEquals(2, historyAlbumStats(legacyEvents).single().playCount)
    }

    @Test
    fun durationDerivedRemoteTimeDoesNotRankAboveKnownListening() {
        val timestamp = LocalDateTime(2026, 9, 1, 12, 0)
        val known =
            Song(
                SongEntity("known", "Known", albumId = "known-album", albumName = "Known album"),
                listOf(ArtistEntity("known-artist", "Known artist")),
            )
        val unknown =
            Song(
                SongEntity("unknown", "Unknown", albumId = "unknown-album", albumName = "Unknown album"),
                listOf(ArtistEntity("unknown-artist", "Unknown artist")),
            )
        val rows =
            listOf(
                EventWithSong(Event(1, unknown.id, timestamp, 240_000, playTimeKnown = false, source = "YOUTUBE"), unknown),
                EventWithSong(Event(2, known.id, timestamp, 30_000), known),
            )
        assertEquals(30_000L, rows.sumOf { it.event.knownPlayTimeMs })
        assertEquals(listOf(known to 1, unknown to 1), historySongStats(rows))
        assertEquals(listOf("known-artist", "unknown-artist"), historyArtistStats(rows).map { it.first.id })
        assertEquals(listOf(30_000L, 0L), historyAlbumStats(rows).map { it.playTime })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun visibleHistoryReactsToBlocksAndDatabaseChangesBeforeAggregatingStatistics() =
        runTest {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val artist = ArtistEntity("artist", "Artist")
            val otherArtist = ArtistEntity("other-artist", "Other artist")
            val blockedArtist = ArtistEntity("blocked-artist", "Blocked artist")
            val first = Song(SongEntity("first", "First", albumId = "shared", albumName = "Shared"), listOf(artist))
            val blocked = first.copy(song = first.song.copy(id = "blocked"))
            val collaboration =
                Song(
                    SongEntity("collaboration", "Collaboration", albumId = "blocked", albumName = "Blocked"),
                    listOf(artist, blockedArtist),
                )
            val other = Song(SongEntity("other", "Other", albumId = "other", albumName = "Other"), listOf(otherArtist))
            val disliked = other.copy(song = other.song.copy(id = "disliked", albumId = "disliked"))
            val timestamp = LocalDateTime(2026, 9, 1, 12, 0)
            val events =
                listOf(first to 10L, first to 20L, blocked to 1000L, collaboration to 2000L, disliked to 3000L, other to 4_000_000_000L)
                    .mapIndexed { index, (song, time) -> EventWithSong(Event(index + 1L, song.id, timestamp, time), song) }
            val source = MutableStateFlow(events)
            var visible = emptyList<EventWithSong>()
            val job = backgroundScope.launch { source.visibleHistoryEvents().collect { visible = it } }
            try {
                runCurrent()
                assertEquals(events, visible)
                BlockedContent.rememberDislikedSong(blocked.toSongItem())
                BlockedContent.blockArtist(blockedArtist.id, blockedArtist.name)
                Settings.shared.dislikedSongIds = disliked.id
                BlockedContent.contentFiltersChanged()
                runCurrent()

                assertEquals(listOf("first", "first", "other"), visible.map { it.song.id })
                assertEquals(4_000_000_030L, visible.sumOf { it.event.playTime })
                assertEquals(listOf(other to 1, first to 2), historySongStats(visible))
                assertEquals(listOf(otherArtist to 1, artist to 2), historyArtistStats(visible))
                val albums = historyAlbumStats(visible)
                assertEquals(listOf("other", "shared"), albums.map { it.id })
                assertEquals(listOf(1, 2), albums.map { it.playCount })
                assertEquals(visible.sumOf { it.event.playTime }, albums.sumOf { it.playTime })

                source.value = events + EventWithSong(Event(7, first.id, timestamp, 40L), first)
                runCurrent()
                assertEquals(4, visible.size)
                assertEquals(listOf(other to 1, first to 3), historySongStats(visible))
                assertEquals(listOf(otherArtist to 1, artist to 3), historyArtistStats(visible))
                assertEquals(70L, historyAlbumStats(visible).last().playTime)

                BlockedContent.unblockSong(blocked.id)
                BlockedContent.unblockArtist(blockedArtist.id)
                Settings.shared.dislikedSongIds = ""
                BlockedContent.contentFiltersChanged()
                runCurrent()
                assertEquals(source.value, visible)
                assertEquals(5, historySongStats(visible).size)
                assertEquals(3, historyArtistStats(visible).size)

                source.value = emptyList()
                runCurrent()
                assertEquals(emptyList(), visible)
                assertEquals(emptyList(), historySongStats(visible))
                assertEquals(emptyList(), historyArtistStats(visible))
                assertEquals(emptyList(), historyAlbumStats(visible))
            } finally {
                job.cancelAndJoin()
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun skeletalHomeSongDoesNotOverwriteStoredMetadata() {
        val timestamp = LocalDateTime(2026, 8, 30, 12, 0)
        val stored =
            SongEntity(
                id = "song",
                title = "Stored title",
                duration = 245,
                thumbnailUrl = "stored.jpg",
                albumId = "album",
                albumName = "Stored album",
                explicit = true,
                year = 2026,
                date = timestamp,
                dateModified = timestamp,
                liked = true,
                likedDate = timestamp,
                totalPlayTime = 123_456L,
                inLibrary = timestamp,
                dateDownload = timestamp,
                isLocal = true,
                libraryAddToken = "add",
                libraryRemoveToken = "remove",
                lyricsOffset = 250,
                romanizeLyrics = false,
                isDownloaded = true,
                isUploaded = true,
                isVideo = true,
                isEpisode = true,
                playbackPosition = 42_000L,
                uploadEntityId = "upload",
                isCached = true,
            )
        val homeSong =
            SongItem(
                id = "song",
                title = "Home title",
                thumbnail = null,
                artists = emptyList(),
                album = null,
            )

        assertEquals(stored.copy(explicitness = true), mergeHistorySong(stored, homeSong))
    }

    @Test
    fun newHistorySongKeepsAvailablePlaybackMetadata() {
        val played =
            SongItem(
                id = "song",
                title = "Song",
                thumbnail = "cover.jpg",
                artists = emptyList(),
                album = AlbumTiny("album", "Album"),
                duration = 180,
                isExplicit = true,
                libraryAddToken = "add",
                libraryRemoveToken = "remove",
                musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
                isUploaded = true,
                isLocal = true,
                uploadEntityId = "upload",
                year = 2025,
            )

        assertEquals(
            SongEntity(
                id = "song",
                title = "Song",
                duration = 180,
                thumbnailUrl = "cover.jpg",
                albumId = "album",
                albumName = "Album",
                explicit = true,
                year = 2025,
                isLocal = true,
                libraryAddToken = "add",
                libraryRemoveToken = "remove",
                isUploaded = true,
                isVideo = false,
                uploadEntityId = "upload",
                explicitness = true,
            ),
            mergeHistorySong(existing = null, played = played),
        )
    }

    @Test
    fun idlessArtistsUseStablePerSongIds() {
        assertEquals("artist", historyArtistId("song", 0, "artist"))
        assertEquals("LH:song:0", historyArtistId("song", 0, null))
        assertEquals("LH:song:0", historyArtistId("song", 0, ""))
        assertEquals("LH:other:0", historyArtistId("other", 0, null))
    }

    @Test
    fun artistPlaceholderMapsAreOnlyRemovedWhenReplaced() {
        val legacy = SongArtistMap("song", "unknown", 0)
        val synthetic = SongArtistMap("song", "LH:song:0", 0)

        assertEquals(emptyList(), obsoleteHistoryArtistMaps("song", emptySet(), listOf(legacy, synthetic)))
        assertEquals(
            listOf(legacy, synthetic),
            obsoleteHistoryArtistMaps("song", setOf("real-artist"), listOf(legacy, synthetic)),
        )
        assertEquals(
            listOf(legacy),
            obsoleteHistoryArtistMaps("song", setOf("LH:song:0"), listOf(legacy, synthetic)),
        )
    }

    @Test
    fun idlessHomeArtistReusesMatchingStoredArtist() {
        val realMap = SongArtistMap("song", "UC-real", 0)
        val syntheticMap = SongArtistMap("song", "LH:song:0", 0)
        val artists =
            mapOf(
                "UC-real" to ArtistEntity("UC-real", "Artist"),
                "LH:song:0" to ArtistEntity("LH:song:0", "Artist", isLocal = true),
            )

        val resolved =
            resolveHistoryArtistId(
                songId = "song",
                position = 0,
                incomingId = null,
                incomingName = "Artist",
                existingMaps = listOf(syntheticMap, realMap),
                existingArtists = artists,
            )

        assertEquals("UC-real", resolved)
        assertEquals(
            listOf(syntheticMap),
            obsoleteHistoryArtistMaps("song", setOf(resolved), listOf(syntheticMap, realMap)),
        )
    }
}
