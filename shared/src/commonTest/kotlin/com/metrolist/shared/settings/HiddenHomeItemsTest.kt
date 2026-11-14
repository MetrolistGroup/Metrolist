package com.metrolist.shared.settings

import com.metrolist.shared.constants.HiddenHomeItemsJsonKey
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.toSongItem
import com.metrolist.shared.viewmodels.HomeViewModel
import com.metrolist.shared.viewmodels.withoutHiddenHomeItems
import com.russhwolf.settings.MapSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HiddenHomeItemsTest {
    private val artist = ArtistItem("artist", "Artist", "artist.jpg", isSubscribed = true)
    private val otherArtist = ArtistTiny("other", "Other")
    private val podcast = PodcastItem("MPSPshow", "Show", "show.jpg", author = "Host", channelId = "host")
    private val song = SongItem("track", "Track", null, listOf(ArtistTiny(artist.id, artist.title)), null)
    private val album = AlbumItem("album", "Album", null, song.artists)
    private val episode = EpisodeItem("episode", "Episode", null, podcastId = podcast.id)

    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun hidingMatchesPrimaryArtistsAndCanonicalMetadataButKeepsFeaturedArtistsAndMixedPlaylists() {
        HiddenHomeItems.hide(artist)
        val filter = HomeContentFilter(HiddenHomeItems.all())
        assertTrue(filter.hides(artist))
        assertTrue(filter.hides(song))
        assertTrue(filter.hides(album))
        assertTrue(filter.hides(song.copy(artists = listOf(otherArtist), canonicalArtists = song.artists)))
        assertTrue(filter.hides(song.copy(artists = listOf(ArtistTiny(null, " ARTIST ")))))
        assertFalse(filter.hides(song.copy(artists = listOf(ArtistTiny("different", artist.title)))))
        assertFalse(filter.hides(song.copy(artists = listOf(otherArtist) + song.artists)))
        assertFalse(filter.hides(album.copy(artists = listOf(otherArtist) + album.artists)))
        assertFalse(filter.hides(PlaylistItem("mix", "Mixed playlist", null, authorId = artist.id)))
        assertFalse(filter.hides(podcast.copy(id = artist.id)))
    }

    @Test
    fun hiddenShowsRemoveTheirEpisodesInBothRepresentationsWithoutHidingTheHostsOtherShows() {
        HiddenHomeItems.hide(podcast)
        val filter = HomeContentFilter(HiddenHomeItems.all())
        assertTrue(filter.hides(podcast))
        assertTrue(filter.hides(episode))
        assertTrue(filter.hides(episode.toSongItem()))
        assertTrue(filter.hides(episode.copy(podcastId = "VL${podcast.id}")))
        assertTrue(filter.hides(episode.copy(podcastId = null, author = ArtistTiny(podcast.id, podcast.title))))
        assertFalse(filter.hides(podcast.copy(id = "MPSPother")))
        assertFalse(filter.hides(episode.copy(podcastId = "MPSPother", author = ArtistTiny("host", "Host"))))
        assertFalse(filter.hides(artist.copy(id = podcast.channelId!!)))
    }

    @Test
    fun hiddenArtistsAlsoHideTheirPodcastAndEpisodeCredits() {
        HiddenHomeItems.hide(artist)
        val filter = HomeContentFilter(HiddenHomeItems.all())
        assertTrue(filter.hides(podcast.copy(channelId = artist.id)))
        assertTrue(filter.hides(podcast.copy(channelId = artist.id, author = null)))
        assertTrue(filter.hides(podcast.copy(channelId = null, author = artist.title)))
        assertTrue(filter.hides(episode.copy(author = song.artists.single())))
    }

    @Test
    fun sectionsRemoveHiddenHeadersAndEmptyShelvesButKeepContinuationCursors() {
        HiddenHomeItems.hide(artist)
        HiddenHomeItems.hide(podcast)
        val filter = HomeContentFilter(HiddenHomeItems.all())
        val visible = song.copy(id = "visible", artists = listOf(otherArtist))
        val mixed = HomeSection("Mixed", listOf(song, visible, episode), continuation = "mixed-next")
        val page = HomeSection("Page", listOf(song), continuation = "next")
        val sections =
            listOf(
                mixed,
                page,
                HomeSection("Empty", listOf(episode)),
                HomeSection("Artist", listOf(visible), headerBrowseId = artist.id),
                HomeSection("Show", listOf(visible), browseId = podcast.id),
            )
        assertEquals(listOf(mixed.copy(items = listOf(visible)), page.copy(items = emptyList())), filter.filterSections(sections))
        assertEquals(listOf(song, visible, episode), mixed.items)
    }

    @Test
    fun everyHomeListIsFilteredWhileExplicitSpeedDialPinsRemain() {
        HiddenHomeItems.hide(artist)
        HiddenHomeItems.hide(podcast)
        val items = listOf<YTItem>(artist, song, album, podcast, episode)
        val sections = listOf(HomeSection("Home", items))
        val raw =
            HomeViewModel.HomeUiState(
                data = sections,
                discoverySections = sections,
                sections = sections,
                trending = listOf(song),
                quickPicks = listOf(song),
                albumCharts = listOf(album),
                newReleases = listOf(album),
                keepListening = listOf(song),
                forgottenFavorites = listOf(song),
                recentlyPlayed = listOf(song),
                podcasts = listOf(podcast),
                speedDialItems = items,
                speedDialPinnedIds = listOf(artist.id, episode.id),
                similarRecommendations = listOf(HomeViewModel.SimilarRecommendation(artist, items)),
            )
        val filtered = raw.withoutHiddenHomeItems(HomeContentFilter(HiddenHomeItems.all()))
        assertEquals(emptyList(), filtered.data)
        assertTrue(filtered.sections.isEmpty())
        assertTrue(filtered.discoverySections.isEmpty())
        assertTrue(filtered.trending.isEmpty())
        assertTrue(filtered.quickPicks.isEmpty())
        assertTrue(filtered.albumCharts.isEmpty())
        assertTrue(filtered.newReleases.isEmpty())
        assertTrue(filtered.keepListening.isEmpty())
        assertTrue(filtered.forgottenFavorites.isEmpty())
        assertTrue(filtered.recentlyPlayed.isEmpty())
        assertTrue(filtered.podcasts.isEmpty())
        assertTrue(filtered.similarRecommendations.isEmpty())
        assertEquals(listOf(artist, episode), filtered.speedDialItems)
        assertEquals(raw.speedDialPinnedIds, filtered.speedDialPinnedIds)
        HiddenHomeItems.restore(HiddenLibraryItem.from(artist).key)
        HiddenHomeItems.restore(HiddenLibraryItem.from(podcast).key)
        assertEquals(raw, raw.withoutHiddenHomeItems(HomeContentFilter(HiddenHomeItems.all())))
    }

    @Test
    fun hideAndRestorePersistThroughRestartAndBackupWithoutChangingOtherSettingsOrBlocking() {
        Settings.shared.blockedArtistIds = "blocked"
        Settings.shared.likedSongIds = "liked"
        Settings.shared.innerTubeCookie = "session"
        Settings.shared.hiddenLibraryPlaylistsJson = "[]"
        val before = Settings.shared.exportBackupSettings()
        HiddenHomeItems.hide(artist)
        HiddenHomeItems.hide(artist.copy(thumbnail = "updated.jpg"))
        HiddenHomeItems.hide(podcast)
        assertEquals(2, HiddenHomeItems.all().size)
        assertEquals("updated.jpg", HiddenHomeItems.all().first().thumbnail)
        assertFalse(BlockedContent.isArtistBlocked(artist.id))
        assertFalse(BlockedContent.isBlocked(song))
        assertFalse(HiddenLibraryItems.isHidden(artist))
        val backup = Settings.shared.exportBackupSettings()
        assertEquals(before, backup.copy(entries = backup.entries.filterNot { it.key == HiddenHomeItemsJsonKey }))
        val backing = MapSettings()
        Settings(backing).importBackupSettings(backup)
        Settings.replaceSharedForTest(Settings(backing))
        assertEquals(listOf(artist.id, podcast.id), HiddenHomeItems.all().map { it.id })
        HiddenHomeItems.restore(HiddenLibraryItem.from(artist).key)
        Settings.replaceSharedForTest(Settings(backing))
        assertEquals(listOf(podcast.id), HiddenHomeItems.all().map { it.id })
        assertEquals("liked", Settings.shared.likedSongIds)
        assertEquals("blocked", Settings.shared.blockedArtistIds)
        assertEquals("session", Settings.shared.innerTubeCookie)
    }
}
