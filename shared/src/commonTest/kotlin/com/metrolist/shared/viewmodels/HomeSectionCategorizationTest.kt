package com.metrolist.shared.viewmodels

import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.mergeAndDeduplicateHomeSections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeSectionCategorizationTest {
    @Test
    fun libraryAlbumShelfKeepsOnlySavedAlbumsAndPreservesMetadata() {
        val saved = album("saved")
        val unsaved = album("unsaved")
        val section =
            HomeSection(
                "De tu biblioteca",
                listOf(unsaved, saved, album("saved-ep")),
                browseId = "FEmusic_library_landing",
                params = "library-params",
                continuation = "library-next",
            )

        assertEquals(
            listOf(section.copy(items = listOf(saved, album("saved-ep")))),
            listOf(section).withLibraryMusicAlbumsOnly(setOf("saved", "saved-ep")),
        )
    }

    @Test
    fun mixedLibraryAlbumShelfExcludesPodcastsAndHidesWhenNoSavedAlbumsRemain() {
        val section =
            HomeSection(
                "From your library",
                listOf(album("saved"), PodcastItem("MPSPshow", "Show", null), EpisodeItem("episode", "Episode", null)),
                browseId = "FEmusic_library_landing",
            )

        assertEquals(listOf(section.copy(items = listOf(album("saved")))), listOf(section).withLibraryMusicAlbumsOnly(setOf("saved")))
        assertTrue(listOf(section).withLibraryMusicAlbumsOnly(emptySet()).isEmpty())
    }

    @Test
    fun savedAlbumFilterPreservesYourShowsAcrossPagesAndLeavesRecommendationsAlone() {
        val music = HomeSection("From your library", listOf(album("unsaved")), browseId = "FEmusic_library_landing")
        val recommendations = music.copy(browseId = "FEmusic_recommendations")
        for (browseId in listOf("FEmusic_library_podcasts", "FEmusic_library_non_music_audio_list")) {
            val shows = HomeSection("Your shows", listOf(PodcastItem("MPSPshow", "Show", null)), browseId = browseId, params = "shows")
            val nextShows = shows.copy(items = listOf(PodcastItem("MPSPnext", "Next show", null)))
            for (sections in listOf(listOf(music, shows, nextShows), listOf(shows, music, nextShows))) {
                val merged = mergeAndDeduplicateHomeSections(sections) + recommendations
                assertEquals(
                    listOf(shows.copy(items = shows.items + nextShows.items), recommendations),
                    uncategorizedHomeSections(merged).withLibraryMusicAlbumsOnly(emptySet()),
                )
            }
        }
    }

    @Test
    fun chipCacheEvictsLeastRecentlyUsedPageAndExpiresFreshness() {
        var cache: Map<String, Int> = emptyMap()
        repeat(6) { cache = boundedHomeChipCache(cache, "chip-$it", it) }
        cache = boundedHomeChipCache(cache, "chip-0", 0)
        cache = boundedHomeChipCache(cache, "chip-6", 6)
        assertEquals(listOf("chip-2", "chip-3", "chip-4", "chip-5", "chip-0", "chip-6"), cache.keys.toList())
        assertTrue(isFreshHomeChip(10_000, 10_001))
        assertEquals(false, isFreshHomeChip(10_000, 130_000))
        assertEquals(false, isFreshHomeChip(null, 10_000))
        assertEquals(false, isFreshHomeChip(10_000, 9_999))
    }

    @Test
    fun strictBlocksAndFiltersStayLocalButRelaxingRefreshes() {
        val before = HomeFilterIdentity(setOf("one"), setOf("artist"), listOf(false))
        val stricter = before.copy(songs = setOf("one", "two"))
        assertEquals(false, stricter.requiresRefreshAfter(before))
        assertTrue(before.requiresRefreshAfter(stricter))
        assertTrue(before.copy(artists = emptySet()).requiresRefreshAfter(before))
        val strictSettings = before.copy(settings = listOf(true), minimumDuration = 60L, aiArtists = setOf("@blocked"))
        assertEquals(false, strictSettings.requiresRefreshAfter(before))
        assertTrue(before.requiresRefreshAfter(strictSettings))
        assertTrue(strictSettings.copy(minimumDuration = 30L).requiresRefreshAfter(strictSettings))
        assertTrue(strictSettings.copy(aiArtists = emptySet()).requiresRefreshAfter(strictSettings))
    }

    @Test
    fun multipleLibrarySectionsAreMergedIntoSingleSection() {
        val libSection1 =
            HomeSection(
                title = "From your library",
                items = listOf(playlist("VLPL_favs"), playlist("PL_rock")),
                browseId = "FEmusic_library",
            )
        val libSection2 =
            HomeSection(
                title = "From your library",
                items = listOf(playlist("PL_favs"), playlist("VLPL_jazz")),
                isPersonal = true,
            )

        val merged = mergeAndDeduplicateHomeSections(listOf(libSection1, libSection2))

        assertEquals(1, merged.size)
        assertEquals("From your library", merged.single().title)
        assertEquals(listOf("VLPL_favs", "PL_rock", "VLPL_jazz"), merged.single().items.map { it.id })
    }

    @Test
    fun podcastLibraryShelvesKeepTheirIdentityAcrossPages() {
        val music = HomeSection("From your library", listOf(playlist("PL_music")), browseId = "FEmusic_library_landing")
        val shows = HomeSection("Your shows", listOf(PodcastItem("MPSPshow", "Show", null)), browseId = "FEmusic_library_podcasts")
        val episodes = HomeSection("Saved episodes", listOf(EpisodeItem("episode", "Episode", null)), browseId = "VLSE")
        val nextShows = shows.copy(items = shows.items + PodcastItem("MPSPnext", "Next show", null))
        val nextMusic = music.copy(items = music.items + playlist("PL_next"))

        val merged = mergeAndDeduplicateHomeSections(listOf(music, shows, episodes, nextShows, nextMusic))

        assertEquals(listOf("From your library", "Your shows", "Saved episodes"), merged.map { it.title })
        assertEquals(listOf("PL_music", "PL_next"), merged[0].items.map { it.id })
        assertEquals(listOf("MPSPshow", "MPSPnext"), merged[1].items.map { it.id })
        assertEquals(episodes, merged[2])
        val visible = uncategorizedHomeSections(merged)
        assertEquals(listOf(merged[1], episodes), visible)
    }

    @Test
    fun sectionsWithDuplicateBrowseIdOrTitleAreMerged() {
        val s1 = HomeSection("Today's hits", listOf(song("s1"), song("s2")), browseId = "hits")
        val s2 = HomeSection("today's hits", listOf(song("s2"), song("s3")), browseId = "hits")

        val merged = mergeAndDeduplicateHomeSections(listOf(s1, s2))

        assertEquals(1, merged.size)
        assertEquals(listOf("s1", "s2", "s3"), merged.single().items.map { it.id })
    }

    @Test
    fun duplicateItemsWithinSectionAreRemoved() {
        val s = HomeSection("Rock", listOf(song("s1"), song("s1"), song("s2")))
        val merged = mergeAndDeduplicateHomeSections(listOf(s))

        assertEquals(listOf("s1", "s2"), merged.single().items.map { it.id })
    }

    private fun song(id: String) = SongItem(id, id, null, emptyList(), null)

    private fun album(id: String) = AlbumItem(id, id, null, emptyList())

    private fun playlist(id: String) = PlaylistItem(id, id, null)
}
