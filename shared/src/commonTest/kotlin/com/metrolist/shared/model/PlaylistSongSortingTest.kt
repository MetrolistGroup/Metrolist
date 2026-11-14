package com.metrolist.shared.model

import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistSongSortingTest {
    @Test
    fun bulkSearchSelectionPreservesIndicesOfRepeatedOccurrences() {
        val repeated = SongItem("repeat", "Match", null, emptyList(), null)
        val hidden = repeated.copy(id = "hidden", title = "Other")
        val songs = List(10_000) { if (it % 2 == 0) hidden else repeated }
        val keys =
            songs.playlistSearchIndices("match").mapTo(mutableSetOf()) { index ->
                songs[index].playlistOccurrenceKey(index)
            }
        assertEquals((1 until 10_000 step 2).map { "repeat@$it" }.toSet(), keys)
    }

    @Test
    fun dateAddedUsesOccurrenceInsertionOrderAfterManualReordering() {
        val first = SongItem("repeat", "Track", null, emptyList(), null, playlistAddedOrder = 1)
        val second = first.copy(id = "second", playlistAddedOrder = 2)
        val repeated = first.copy(playlistAddedOrder = 3)
        val reordered = listOf(repeated, second, first)
        assertEquals(listOf(first, second, repeated), reordered.sortedForPlaylist(PlaylistSongSort.DATE_ADDED, SortDirection.ASCENDING))
        assertEquals(reordered, reordered.sortedForPlaylist(PlaylistSongSort.ORIGINAL, SortDirection.ASCENDING))
        assertEquals(reordered, reordered.sortedForPlaylist(PlaylistSongSort.DATE_ADDED, SortDirection.DESCENDING))
        val likes = listOf(second, repeated, first)
        assertEquals(likes, likes.sortedForLikedPlaylist(PlaylistSongSort.DATE_ADDED, SortDirection.DESCENDING))
    }

    @Test
    fun searchUsesEditedMetadataWithoutChangingPlaylistOccurrences() {
        val original = SongItem("edited-song", "dnd", null, listOf(ArtistTiny("artist", "Original artist")), null)
        val edited = original.copy(customTitle = "crime", customArtist = "Edited artist", playlistSetVideoId = "first")
        val duplicate = edited.copy(playlistSetVideoId = "second")
        val songs = listOf(original.copy(id = "other"), edited, duplicate)
        assertEquals(listOf(edited, duplicate), songs.filterForPlaylistSearch(" CRIME "))
        assertEquals(listOf(1, 2), songs.playlistSearchIndices("edited artist"))
        assertEquals(songs, songs.filterForPlaylistSearch("dnd"))
        assertEquals(listOf(edited, duplicate), songs.presentCollection("crime", CollectionSort.NEWEST) { null })
    }

    private val songs =
        listOf(
            song("2", "Same", "Beta", "Second", 200, 2022),
            song("1", "Same", "Alpha", "First", 100, 2020),
            song("3", "Other", "Gamma", null, null, null),
        )

    @Test
    fun likedAndRegularSortPreferencesPersistIndependently() {
        val preferences = MapSettings()
        val settings = Settings(preferences)
        assertEquals(PlaylistSongSort.ORIGINAL, settings.playlistSongSort)
        assertEquals(songs, songs.sortedForPlaylist(settings.playlistSongSort, settings.playlistSongSortDirection))
        assertEquals(songs, songs.sortedForLikedPlaylist(settings.likedSongSort, settings.likedSongSortDirection))
        settings.playlistSongSort = PlaylistSongSort.TITLE
        settings.playlistSongSortDirection = SortDirection.ASCENDING
        settings.likedSongSort = PlaylistSongSort.DATE_ADDED
        settings.likedSongSortDirection = SortDirection.DESCENDING
        val restored = Settings(preferences)
        assertEquals(PlaylistSongSort.TITLE, restored.playlistSongSort)
        assertEquals(SortDirection.ASCENDING, restored.playlistSongSortDirection)
        assertEquals(
            listOf("3", "2", "1"),
            songs.sortedForPlaylist(restored.playlistSongSort, restored.playlistSongSortDirection).map { it.id },
        )
        assertEquals(PlaylistSongSort.DATE_ADDED, restored.likedSongSort)
        assertEquals(SortDirection.DESCENDING, restored.likedSongSortDirection)
        restored.likedSongSortDirection = SortDirection.ASCENDING
        assertEquals(songs.reversed(), songs.sortedForLikedPlaylist(restored.likedSongSort, restored.likedSongSortDirection))
        assertEquals(PlaylistSongSort.TITLE, restored.playlistSongSort)
    }

    @Test
    fun likedDateOrderUsesNewestFirstSnapshotAndKeepsOriginalOrder() {
        val newestFirst = listOf(songs[2], songs[0], songs[1])
        assertEquals(newestFirst, newestFirst.sortedForLikedPlaylist(PlaylistSongSort.DATE_ADDED, SortDirection.DESCENDING))
        assertEquals(newestFirst.reversed(), newestFirst.sortedForLikedPlaylist(PlaylistSongSort.DATE_ADDED, SortDirection.ASCENDING))
        for (sort in PlaylistSongSort.entries) {
            assertEquals(sort == PlaylistSongSort.ORIGINAL, sort.allowsReordering())
            if (sort.allowsReordering()) {
                for (direction in SortDirection.entries) {
                    assertEquals(newestFirst, newestFirst.sortedForLikedPlaylist(sort, direction))
                    assertEquals(newestFirst, newestFirst.sortedForPlaylist(sort, direction))
                }
            }
        }
        for (direction in SortDirection.entries) {
            assertEquals(newestFirst, newestFirst.sortedForLikedPlaylist(PlaylistSongSort.ORIGINAL, direction))
            assertEquals(
                newestFirst.sortedForPlaylist(PlaylistSongSort.TITLE, direction),
                newestFirst.sortedForLikedPlaylist(PlaylistSongSort.TITLE, direction),
            )
        }
    }

    @Test
    fun existingPersistedSortStillRestoresTheSameOption() {
        val preferences = MapSettings().apply { putInt("playlistSongSort", 1) }
        assertEquals(PlaylistSongSort.TITLE, Settings(preferences).playlistSongSort)
    }

    @Test
    fun artistSortBuildsEachKeyOnceAndKeepsCaseInsensitiveTiesStable() {
        var artistReads = 0
        val songs =
            listOf("Zulu", "alpha", "ALPHA", "Beta").mapIndexed { index, name ->
                val artists =
                    object : AbstractList<ArtistTiny>() {
                        override val size = 2

                        override fun get(index: Int): ArtistTiny {
                            artistReads++
                            return ArtistTiny(null, if (index == 0) name else "Guest")
                        }
                    }
                SongItem("$index", name, null, artists, null)
            }

        for (direction in SortDirection.entries) {
            artistReads = 0
            val sorted = songs.sortedForPlaylist(PlaylistSongSort.ARTIST, direction)
            assertEquals(songs.size * 2, artistReads)
            val expected = if (direction == SortDirection.ASCENDING) listOf("1", "2", "3", "0") else listOf("0", "3", "1", "2")
            assertEquals(expected, sorted.map { it.id })
        }
    }

    @Test
    fun cachedNullableKeysKeepUnknownValuesLastAndDuplicateOccurrencesStable() {
        val unknown = song("unknown", "Unknown", "Artist", null, null, null)
        val first = song("repeat", "Same", "Artist", "alpha", 100L, 2020).copy(playlistSetVideoId = "first")
        val duplicate = first.copy(album = AlbumTiny(null, "ALPHA"), playlistSetVideoId = "second")
        val last = song("last", "Last", "Artist", "Beta", 200L, 2021)
        val secondUnknown = unknown.copy(playlistSetVideoId = "unknown-2")
        val songs = listOf(unknown, first, duplicate, secondUnknown, last)

        for (sort in listOf(PlaylistSongSort.ALBUM, PlaylistSongSort.DURATION, PlaylistSongSort.YEAR)) {
            assertEquals(
                listOf(first, duplicate, last, unknown, secondUnknown),
                songs.sortedForPlaylist(sort, SortDirection.ASCENDING),
            )
            assertEquals(
                listOf(last, first, duplicate, unknown, secondUnknown),
                songs.sortedForPlaylist(sort, SortDirection.DESCENDING),
            )
        }
    }

    @Test
    fun playlistSearchMatchesEditedAndOriginalNames() {
        val edited =
            song("9", "crime", "NewArtist", "Album", 100, 2020).copy(
                customTitle = "crime",
                customArtist = "NewArtist",
                canonicalTitle = "dnd",
                canonicalArtists = listOf(ArtistTiny(null, "OldArtist")),
            )
        val songs = listOf(edited)
        assertEquals(listOf("9"), songs.filterForPlaylistSearch("crime").map { it.id })
        assertEquals(listOf("9"), songs.filterForPlaylistSearch("dnd").map { it.id })
        assertEquals(listOf("9"), songs.filterForPlaylistSearch("NewArtist").map { it.id })
        assertEquals(listOf("9"), songs.filterForPlaylistSearch("OldArtist").map { it.id })
        assertEquals(emptyList(), songs.filterForPlaylistSearch("absent").map { it.id })
    }

    @Test
    fun playlistSearchPreservesSourceIndicesForIdenticalOccurrences() {
        val duplicates = listOf(songs[2], songs[0], songs[0].copy(), songs[1])
        assertEquals(listOf(1, 2), duplicates.playlistSearchIndices(" beta "))
        assertEquals(listOf(3), duplicates.playlistSearchIndices("first"))
        assertEquals(duplicates.indices.toList(), duplicates.playlistSearchIndices(" "))
        assertEquals(emptyList(), duplicates.playlistSearchIndices("absent"))
    }

    @Test
    fun librarySearchMatchesTitleAuthorAndArtistsAcrossItemTypes() {
        val items =
            listOf(
                song("1", "Golden Hour", "Marsh", "Golden", null, null),
                PlaylistItem("pl", "Morning Mix", null, author = "You"),
                ArtistItem("ar", "Marshmello", null),
                AlbumItem("al", "Chill Vibes", null, artists = listOf(ArtistTiny(null, "Lofi"))),
                PodcastItem("po", "Music Talks", null, author = "Studio"),
                EpisodeItem("ep", "Deep Dive", null, author = ArtistTiny(null, "Studio")),
            )

        assertEquals(listOf("1"), items.filterForLibrarySearch("golden").map { it.id })
        assertEquals(listOf("pl"), items.filterForLibrarySearch("you").map { it.id })
        assertEquals(listOf("1", "ar"), items.filterForLibrarySearch("marsh").map { it.id })
        assertEquals(listOf("al"), items.filterForLibrarySearch("lofi").map { it.id })
        assertEquals(listOf("po", "ep"), items.filterForLibrarySearch("studio").map { it.id })
        assertEquals(items, items.filterForLibrarySearch("  "))
    }

    private fun song(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Long?,
        year: Int?,
    ) = SongItem(id, title, null, listOf(ArtistTiny(null, artist)), album?.let { AlbumTiny(null, it) }, duration, year = year)
}
