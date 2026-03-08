package com.metrolist.shared.settings

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistPageResult
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.Chip
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.viewmodels.HomeViewModel
import com.metrolist.shared.viewmodels.homeContentAfterBlockedContentChange
import com.metrolist.shared.viewmodels.visibleArtistSections
import com.metrolist.shared.viewmodels.withoutBlockedContent
import com.russhwolf.settings.MapSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BlockedContentTest {
    @Test
    fun unavailableRowsRemainVisibleWithoutContributingToContentFilterCounts() {
        Settings.shared.hideVideoSongs = true
        Settings.shared.explicitOnly = true
        Settings.shared.blockedSongIds = "unavailable"
        val unavailable = song("unavailable").copy(isUnavailable = true, musicVideoType = "MUSIC_VIDEO_TYPE_OMV")
        val playable = song("playable").copy(isExplicit = true)
        val hidden = song("hidden").copy(musicVideoType = "MUSIC_VIDEO_TYPE_OMV")
        val songs = listOf(unavailable, hidden, playable)
        assertEquals(listOf(unavailable, playable), BlockedContent.filterSongs(songs))
        assertEquals(listOf(unavailable, playable), BlockedContent.filterItems(songs))
        assertEquals(listOf(playable), BlockedContent.filterPlayableSongs(songs))
        assertFalse(BlockedContent.isBlocked(unavailable))
    }

    @Test
    fun itemShelvesCanShareOneImmutableBlockSnapshot() {
        Settings.shared.blockedSongIds = "blocked"
        val snapshot = BlockedContent.snapshot()
        Settings.shared.blockedSongIds = "clean"
        val items = listOf(song("blocked"), song("clean"))
        assertEquals(listOf("clean"), BlockedContent.filterItems(items, snapshot).map { it.id })
        assertEquals(listOf("clean"), BlockedContent.filterItemSections(mapOf("Songs" to items), snapshot).getValue("Songs").map { it.id })
        assertEquals(listOf("blocked"), BlockedContent.filterItems(items).map { it.id })
    }

    @Test
    fun decodedListsAreReusedAndInvalidatedByRawEditsRestoreAndStoreReplacement() {
        Settings.shared.blockedSongsJson = """[{"id":"song","title":"Song"}]"""
        Settings.shared.blockedArtistsJson = """[{"id":"artist","name":"Artist"}]"""
        val songs = BlockedContent.songs()
        val artists = BlockedContent.artists()
        val ids = BlockedContent.songIds()
        repeat(100) {
            assertTrue(BlockedContent.isBlocked(song("song")))
            assertSame(songs, BlockedContent.songs())
            assertSame(artists, BlockedContent.artists())
            assertSame(ids, BlockedContent.songIds())
        }
        Settings.shared.dislikedSongIds = "disliked"
        assertTrue(BlockedContent.isSongBlocked("disliked"))
        Settings.shared.importBackupSettings(
            PortableSettings(entries = listOf(PortableSetting("blockedSongsJson", SettingType.STRING, "[]"))),
        )
        assertFalse(BlockedContent.isSongBlocked("song"))
        Settings.shared.blockedArtistsJson = "invalid"
        assertTrue(BlockedContent.artists().isEmpty())
        Settings.shared.blockedArtistIds = "legacy"
        assertTrue(BlockedContent.isArtistBlocked("legacy"))
        Settings.replaceSharedForTest(Settings(MapSettings()))
        assertTrue(BlockedContent.songIds().isEmpty())
        assertTrue(BlockedContent.artistIds().isEmpty())
    }

    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        AiSlopBlocklist.replaceForTest(emptySet())
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
        AiSlopBlocklist.replaceForTest(emptySet())
    }

    @Test
    fun videoFilteringRemovesNowEmptyHomeSections() {
        Settings.shared.hideVideoSongs = true
        val video =
            SongItem(
                id = "video",
                title = "Video",
                thumbnail = null,
                artists = emptyList(),
                album = null,
                musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
            )

        assertTrue(BlockedContent.filterSections(listOf(HomeSection("Videos", listOf(video)))).isEmpty())
    }

    @Test
    fun unfilteredHomeSnapshotRestoresContentAfterFilterIsDisabled() {
        val raw = HomeViewModel.HomeUiState(quickPicks = listOf(song("blocked"), song("clean")))

        Settings.shared.blockedSongIds = "blocked"
        assertEquals(listOf("clean"), raw.withoutBlockedContent().quickPicks.map(SongItem::id))

        Settings.shared.blockedSongIds = ""
        assertEquals(listOf("blocked", "clean"), raw.withoutBlockedContent().quickPicks.map(SongItem::id))
    }

    @Test
    fun selectedChipSnapshotRestoresContentAfterFilterIsDisabledOffline() {
        val chip = Chip(title = "Energy", params = "energy")
        val raw =
            HomeViewModel.HomeUiState(
                selectedChip = chip,
                quickPicks = listOf(song("blocked"), song("clean")),
            )

        Settings.shared.blockedSongIds = "blocked"
        assertEquals(
            listOf("clean"),
            homeContentAfterBlockedContentChange(raw, cachedAllContent = null, cachedSelectedChipContent = raw)
                .quickPicks
                .map(SongItem::id),
        )

        Settings.shared.blockedSongIds = ""
        assertEquals(
            listOf("blocked", "clean"),
            homeContentAfterBlockedContentChange(raw, cachedAllContent = null, cachedSelectedChipContent = raw)
                .quickPicks
                .map(SongItem::id),
        )
    }

    @Test
    fun snapshotKeepsBulkFilteringConsistent() {
        Settings.shared.blockedSongIds = "blocked-song"
        Settings.shared.blockedArtistIds = "blocked-artist"
        Settings.shared.blockAiSlopArtists = true
        AiSlopBlocklist.replaceForTest(setOf("@ai-artist"))
        val snapshot = BlockedContent.snapshot()

        Settings.shared.blockedSongIds = "newly-blocked"
        Settings.shared.blockAiSlopArtists = false
        AiSlopBlocklist.replaceForTest(emptySet())

        assertTrue(snapshot.isBlocked(song("blocked-song")))
        assertTrue(snapshot.isBlocked(song("clean", artistId = "blocked-artist")))
        assertTrue(snapshot.isBlocked(song("ai-song", artistName = "AI-Artist")))
        assertFalse(snapshot.isBlocked(song("newly-blocked")))
    }

    @Test
    fun nameOnlyArtistsAreBlockedWithoutHidingKnownDifferentArtists() {
        BlockedContent.blockArtist("blocked-artist", "Blocked artist")
        val topResultAlbum = AlbumItem("album", "Blocked release", null, listOf(ArtistTiny(null, "BLOCKED ARTIST")))
        val sameNameKnownArtist = AlbumItem("other-album", "Another release", null, listOf(ArtistTiny("other-artist", "Blocked artist")))
        val topResultSong = song("song", artistId = null, artistName = "blocked artist")
        val knownArtistSong = song("other-song", artistId = "other-artist", artistName = "Blocked artist")

        assertEquals(listOf(sameNameKnownArtist), BlockedContent.filterItems(listOf(topResultAlbum, sameNameKnownArtist)))
        assertEquals(listOf(knownArtistSong), BlockedContent.filterSongs(listOf(topResultSong, knownArtistSong)))
    }

    @Test
    fun blockedArtistPageKeepsHeaderForUnblockAction() {
        val header = ArtistItem("artist", "Artist", null)
        val sections = mapOf("header" to listOf(header), "tracks" to listOf(song("song")))

        BlockedContent.blockArtist("artist", "Artist")
        val visible = visibleArtistSections(sections, "artist")

        assertEquals(true, visible["header"]?.filterIsInstance<ArtistItem>()?.single()?.isBlocked)
        assertNull(visible["tracks"])
    }

    @Test
    fun refilteringLoadedPagesRemovesBlockedItemsAndRestoresThemAfterUnblocking() {
        val songs = listOf(song("first"), song("second", artistId = "other-artist"))
        val album = AlbumItem("album", "Album", null, emptyList())
        val blockedAlbum = album.copy(id = "other-album", artists = songs.last().artists)
        val page = AlbumPage(album, songs, otherVersions = listOf(blockedAlbum), artistAlbums = listOf(blockedAlbum))
        val header = ArtistItem("artist", "Artist", null)
        val artistPage = ArtistPageResult(mapOf("header" to listOf(header), "tracks" to songs))
        val sections = listOf(HomeSection("Tracks", songs))
        val version = BlockedContent.version.value

        BlockedContent.rememberDislikedSong(songs.first())
        BlockedContent.blockArtist("other-artist", "Other artist")
        assertTrue(BlockedContent.version.value > version)
        assertEquals(emptyList(), BlockedContent.filterSongs(songs))
        val filteredAlbum = BlockedContent.filterAlbumPage(page)
        assertEquals(emptyList(), filteredAlbum.songs)
        assertEquals(emptyList(), filteredAlbum.otherVersions)
        assertEquals(emptyList(), filteredAlbum.artistAlbums)
        assertEquals(mapOf("header" to listOf(header)), BlockedContent.filterArtistPage(artistPage).sections)
        assertEquals(emptyList(), BlockedContent.filterSections(sections))
        assertEquals(songs, sections.single().items)
        assertEquals(songs, page.songs)

        BlockedContent.unblockSong(songs.first().id)
        BlockedContent.unblockArtist("other-artist")
        assertEquals(songs, BlockedContent.filterSongs(songs))
        assertEquals(page, BlockedContent.filterAlbumPage(page))
        assertEquals(artistPage, BlockedContent.filterArtistPage(artistPage))
        assertEquals(sections, BlockedContent.filterSections(sections))
    }

    private fun song(
        id: String,
        artistId: String? = "artist",
        artistName: String = "Artist",
    ) = SongItem(
        id = id,
        title = "Song",
        thumbnail = null,
        artists = listOf(ArtistTiny(artistId, artistName)),
        album = null,
    )
}
