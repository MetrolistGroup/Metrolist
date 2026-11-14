package com.metrolist.shared.ui.screens

import androidx.compose.ui.unit.dp
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.Chip
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.HomeChipFeed
import com.metrolist.shared.viewmodels.HomeViewModel.Genre
import com.metrolist.shared.viewmodels.HomeViewModel.HomeUiState
import com.metrolist.shared.viewmodels.HomeViewModel.SimilarRecommendation
import com.metrolist.shared.viewmodels.uncategorizedHomeSections
import com.russhwolf.settings.MapSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class HomeShelfBuilderTest {
    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun recommendationHeadersKeepSourceMetadataAndBrowseActions() {
        val section =
            HomeSection(
                title = "Mixtape per te",
                items = listOf(song("mix")),
                subtitle = "PER SENTIRTI BENE",
                browseId = "recommendations",
                params = "mood",
                thumbnail = "header-artwork",
                headerBrowseId = "UCartist",
                continuation = "next-shelf-page",
                isPersonal = true,
            )
        val shelf = build(HomeUiState(data = listOf(section), sections = listOf(section))).single()
        assertEquals(section.title, shelf.title)
        assertEquals(section.subtitle, shelf.label)
        assertEquals(section.thumbnail, shelf.thumbnail)
        assertEquals(section.headerBrowseId, shelf.headerArtistId)
        assertEquals(HomeShelfHeaderAction.BROWSE, shelf.headerAction)
        assertEquals(section.browseId, shelf.browseId)
        assertEquals(section.params, shelf.params)
        val page = shelf.toHomeSection()
        assertEquals(section.items, page.items)
        assertEquals(section.browseId, page.browseId)
        assertEquals(section.params, page.params)
        assertEquals(section.continuation, page.continuation)
        assertEquals(section.isPersonal, page.isPersonal)
    }

    @Test
    fun categorizedQuickPicksKeepSourceHeaderAndPlayAll() {
        val section =
            HomeSection(
                "Scelte rapide",
                listOf(song("one")),
                subtitle = "Recommendations",
                browseId = "FEmusic_quickpicks",
                thumbnail = "source-artwork",
            )
        val shelf = build(HomeUiState(data = listOf(section), quickPicks = section.items.filterIsInstance<SongItem>())).single()
        assertEquals(section.title, shelf.title)
        assertEquals(section.subtitle, shelf.label)
        assertEquals(section.thumbnail, shelf.thumbnail)
        assertEquals(HomeShelfHeaderAction.PLAY_ALL, shelf.headerAction)
    }

    @Test
    fun podcastLibraryHeaderStillOpensItsSource() {
        val section =
            HomeSection(
                "Your shows",
                listOf(PodcastItem("MPSPshow", "Show", null)),
                browseId = "FEmusic_library_podcasts",
            )
        val shelf = build(HomeUiState(data = listOf(section), sections = listOf(section))).single()
        assertEquals(HomeShelfHeaderAction.BROWSE, shelf.headerAction)
        assertEquals(section.browseId, shelf.browseId)
    }

    @Test
    fun longListenRowsKeepTheirColumnCountInsteadOfBecomingVideoCards() {
        val section =
            HomeSection(
                "Ascolti di lunga durata",
                (1..8).map { song("video-$it").copy(musicVideoType = "MUSIC_VIDEO_TYPE_UGC") },
                isMultiRow = true,
                numItemsPerColumn = 4,
                isResponsiveList = true,
            )
        val shelf = build(HomeUiState(data = listOf(section), sections = listOf(section))).single()
        assertEquals(HomeShelfStyle.SONG_GRID, shelf.style)
        assertEquals(4, shelf.rows)
        assertEquals(true, shelf.compactRows)
    }

    @Test
    fun compactTwoRowShelvesDoNotUseArtworkCards() {
        val section = HomeSection("Songs", listOf(song("one")), numItemsPerColumn = 2, isResponsiveList = true)
        val shelf = build(HomeUiState(sections = listOf(section))).single()
        assertEquals(HomeShelfStyle.SONG_GRID, shelf.style)
        assertEquals(2, shelf.rows)
        assertEquals(true, shelf.compactRows)
    }

    @Test
    fun apiShelfOrderSurvivesCategorizationAndContinuationWhileSpeedDialStaysFirst() {
        val recommendations = HomeSection("Mixtape per te", listOf(song("mix")), browseId = "mixes")
        val quickPicks = HomeSection("Scelte rapide", listOf(song("quick")), browseId = "FEmusic_quickpicks", params = "mood")
        val next = HomeSection("Dalla comunità", listOf(song("community")), browseId = "community")
        val initial =
            HomeUiState(
                data = listOf(recommendations, quickPicks),
                sections = listOf(recommendations),
                quickPicks = quickPicks.items.filterIsInstance<SongItem>(),
                speedDialItems = listOf(song("pinned")),
            )
        val shelves = build(initial)
        assertEquals(listOf("Speed Dial", recommendations.title, quickPicks.title), shelves.map { it.title })
        assertEquals("mood", shelves.last().params)
        val continued = build(initial.copy(data = initial.data.orEmpty() + next, sections = initial.sections + next))
        assertEquals(shelves.map { it.title } + next.title, continued.map { it.title })
    }

    @Test
    fun moodsStayLastWhenTheirSourcePrecedesRecommendationsAndContinuedSections() {
        val mood = BrowseItem("FEmusic_moods_and_genres_category", "Relax", params = "relax")
        val moods = HomeSection(text.moodsAndGenres, listOf(mood), browseId = "FEmusic_moods_and_genres", params = "moods-overview")
        val recommendation = HomeSection("Recommended mixes", listOf(PlaylistItem("VLRDmix", "Mix", null)), browseId = "recommendations")
        val quickPicks = HomeSection(text.quickPicks, listOf(song("quick")), browseId = "FEmusic_quickpicks")
        val initialSections = listOf(moods, recommendation, quickPicks)
        val initial =
            HomeUiState(
                data = initialSections,
                sections = uncategorizedHomeSections(initialSections),
                moodsAndGenres = listOf(Genre(mood.title, mood.id, mood.thumbnail, mood.params)),
                quickPicks = quickPicks.items.filterIsInstance<SongItem>(),
                speedDialItems = listOf(song("pinned")),
                continuation = "home-next",
            )
        val shelves = build(initial)
        assertEquals(listOf(text.speedDial, recommendation.title, quickPicks.title, text.moodsAndGenres), shelves.map { it.title })
        val next = HomeSection("More recommendations", listOf(PlaylistItem("VLRDnext", "Next mix", null)), browseId = "next")
        val continuedSections = initialSections + next
        val continued =
            build(initial.copy(data = continuedSections, sections = uncategorizedHomeSections(continuedSections), continuation = null))
        assertEquals(
            listOf(text.speedDial, recommendation.title, quickPicks.title, next.title, text.moodsAndGenres),
            continued.map { it.title },
        )
        assertEquals(1, continued.count { it.style == HomeShelfStyle.MOOD_AND_GENRES })
        val footer = continued.last()
        assertEquals("moods", footer.key)
        assertEquals(moods.items, footer.items)
        assertEquals(moods.browseId, footer.browseId)
        assertEquals(moods.params, footer.params)
        assertEquals(HomeShelfHeaderAction.BROWSE, footer.headerAction)
    }

    @Test
    fun homeCardsKeepPlaylistDescriptionsAndLocalizedEpisodeMetadata() {
        assertEquals(
            "Lady Gaga, Rihanna, Emma",
            homeCardSubtitle(PlaylistItem("VLRDmix", "Mix", null, subtitle = "Lady Gaga, Rihanna, Emma")),
        )
        assertEquals(
            "Author • 12 episodes",
            homeCardSubtitle(PodcastItem("MPSPshow", "Show", null, author = "Author", episodeCount = "12 episodes")),
        )
        assertEquals(
            "Show • 2 giorni fa • 1 ora",
            homeCardSubtitle(
                EpisodeItem(
                    "episode",
                    "Episode",
                    null,
                    author = ArtistTiny("UCshow", "Show"),
                    subtitle = "2 giorni fa",
                    durationText = "1 ora",
                ),
            ),
        )
    }

    @Test
    fun sourceSongRowsDoNotReplaceTheSpeedDialProjection() {
        val section = HomeSection(text.speedDial, listOf(song("source")), isResponsiveList = true)
        val shelf = build(HomeUiState(data = listOf(section), speedDialItems = listOf(song("pinned")))).single()
        assertEquals(HomeShelfStyle.SPEED_DIAL, shelf.style)
        assertEquals(listOf("pinned"), shelf.items.map { it.id })
    }

    @Test
    fun personalFreshFindsPlaylistShelfOpensItsSectionPage() {
        val section =
            HomeSection(
                "Fresh finds",
                listOf(PlaylistItem("VLRDpersonal", "Personal mix", null)),
                browseId = "FEmusic_fresh_finds",
                isPersonal = true,
            )
        val shelf = build(HomeUiState(data = listOf(section), sections = listOf(section))).single()
        assertEquals(HomeShelfHeaderAction.LIBRARY, shelf.headerAction)
        assertEquals(HomeShelfHeaderAction.LOCAL, shelf.headerNavigationAction(hasSectionPage = true))
        assertEquals(section.items, shelf.toHomeSection().items)
    }

    @Test
    fun sameTitleGenericSourceCannotReplaceTheFilteredShelf() {
        val generic =
            HomeSection(
                "Mixed for you",
                listOf(PlaylistItem("VLRDgeneric", "Generic mix", null)),
                browseId = "FEmusic_mixes",
                continuation = "generic-next",
            )
        val filtered =
            HomeSection(
                "Mixed for you",
                listOf(PlaylistItem("VLRDrelax", "Relax mix", null)),
                browseId = "FEmusic_mixes",
                params = "relax-shelf",
                continuation = "relax-next",
                isPersonal = true,
            )
        val chip = Chip("Relax", "relax-feed")
        val shelf = build(HomeUiState(data = listOf(generic, filtered), sections = listOf(filtered), selectedChip = chip)).single()
        assertEquals(filtered.items, shelf.items)
        assertEquals(filtered.browseId, shelf.browseId)
        assertEquals(filtered.params, shelf.params)
        assertEquals(filtered.continuation, shelf.continuation)
        assertEquals(filtered.isPersonal, shelf.isPersonal)
        assertEquals(chip, shelf.feedChip)
        assertEquals(filtered.browseId, shelf.toHomeSection().browseId)
        assertEquals(filtered.params, shelf.toHomeSection().params)
    }

    @Test
    fun visibleFilteredShelfDoesNotBorrowAnOlderFeedsCursorForSharedItems() {
        val item = PlaylistItem("VLRDshared", "Mix", null)
        val generic = HomeSection("Mixed for you", listOf(item), browseId = "FEmusic_mixes", continuation = "generic-next")
        val filtered = generic.copy(subtitle = "For relaxing", continuation = null)
        val shelf = build(HomeUiState(data = listOf(generic), sections = listOf(filtered), selectedChip = Chip("Relax", "relax"))).single()
        assertEquals(filtered.subtitle, shelf.label)
        assertNull(shelf.continuation)
        assertNull(shelf.toHomeSection().browseId)
        assertEquals(filtered.items, shelf.toHomeSection().items)
    }

    @Test
    fun exactSourceParametersWinWhenSameTitleSourcesShareAnItem() {
        val item = PlaylistItem("VLRDshared", "Mix", null)
        val generic = HomeSection("Mixed for you", listOf(item), browseId = "FEmusic_mixes", continuation = "generic-next")
        val filtered = generic.copy(params = "relax-shelf", continuation = "relax-next", isPersonal = true)
        val shelf = build(HomeUiState(data = listOf(generic, filtered), sections = listOf(filtered))).single()
        assertEquals("relax-shelf", shelf.params)
        assertEquals("relax-next", shelf.continuation)
        assertEquals(true, shelf.isPersonal)
    }

    @Test
    fun sameTitleShelvesWithDifferentParametersKeepTheirOwnItemsAndCursors() {
        val relax =
            HomeSection(
                "Mixed for you",
                listOf(PlaylistItem("VLRDrelax", "Relax mix", null)),
                browseId = "FEmusic_mixes",
                params = "relax",
                continuation = "relax-next",
            )
        val focus = relax.copy(items = listOf(PlaylistItem("VLRDfocus", "Focus mix", null)), params = "focus", continuation = "focus-next")
        val shelves = build(HomeUiState(data = listOf(relax, focus), sections = listOf(relax, focus)))
        assertEquals(listOf(relax.items, focus.items), shelves.map { it.items })
        assertEquals(listOf("relax", "focus"), shelves.map { it.params })
        assertEquals(listOf("relax-next", "focus-next"), shelves.map { it.continuation })
        assertEquals(2, shelves.map { it.pageKey }.toSet().size)
    }

    @Test
    fun similarArtistWithoutMoreEndpointKeepsCardsAndArtistHeader() {
        val artist = ArtistItem("UCfemtanyl", "femtanyl", "artist-artwork")
        val items = listOf(PlaylistItem("VLRDsimilar", "Similar mix", null))
        val shelf = build(HomeUiState(similarRecommendations = listOf(SimilarRecommendation(artist, items)))).single()
        assertEquals(items, shelf.items)
        assertEquals(artist.id, shelf.headerArtistId)
        assertEquals(artist.title, shelf.headerTitle)
        assertEquals(artist.thumbnail, shelf.thumbnail)
        assertNull(shelf.browseId)
        assertNull(shelf.toHomeSection().browseId)
        assertEquals(items, shelf.toHomeSection().items)
    }

    @Test
    fun similarArtistWithMoreEndpointKeepsItsParametersAndCursor() {
        val artist = ArtistItem("UCfemtanyl", "femtanyl", "artist-artwork")
        val items = listOf(PlaylistItem("VLRDsimilar", "Similar mix", null))
        val source =
            HomeSection(
                artist.title,
                items,
                subtitle = text.similarTo,
                browseId = "FEmusic_similar",
                params = "femtanyl-recommendations",
                continuation = "similar-next",
                isPersonal = true,
                headerBrowseId = artist.id,
            )
        val shelf =
            build(
                HomeUiState(
                    data = listOf(source),
                    similarRecommendations = listOf(SimilarRecommendation(artist, items, source.browseId, source.params, source.subtitle)),
                ),
            ).single()
        assertEquals(artist.id, shelf.headerArtistId)
        assertEquals(source.browseId, shelf.toHomeSection().browseId)
        assertEquals(source.params, shelf.toHomeSection().params)
        assertEquals(source.continuation, shelf.toHomeSection().continuation)
        assertEquals(true, shelf.toHomeSection().isPersonal)
        assertEquals(items, shelf.toHomeSection().items)
    }

    @Test
    fun chipShelvesFollowTheExistingFeedAndNameTheFirstVisibleShelf() {
        val main = HomeSection("Main", listOf(song("main")))
        val hidden = HomeSection("Hidden", listOf(song("hidden")), browseId = "hidden")
        val rows =
            HomeSection(
                "Compact rows",
                listOf(song("row")),
                browseId = "rows",
                params = "rows-params",
                continuation = "rows-next",
                isPersonal = true,
                isResponsiveList = true,
                numItemsPerColumn = 2,
            )
        val last = HomeSection("Last", listOf(song("last")))
        val state =
            HomeUiState(
                data = listOf(main),
                sections = listOf(main),
                moodsAndGenres = listOf(Genre("Rock", "rock")),
            )
        val original = build(state)
        assertEquals(HomeShelfStyle.MOOD_AND_GENRES, original.last().style)
        Settings.shared.toggleHomeSectionVisibility("section:hidden:", false)
        val shelves =
            build(
                state.copy(
                    chipFeeds = listOf(HomeChipFeed(Chip("Focus", params = "focus"), listOf(hidden, rows, last))),
                ),
            )
        assertEquals(original, shelves.take(original.size))
        val appended = shelves.drop(original.size)
        assertEquals(listOf("Compact rows", "Last"), appended.map { it.title })
        assertEquals(listOf("Focus", null), appended.map { it.chipTitle })
        assertEquals(HomeShelfStyle.SONG_GRID, appended.first().style)
        assertEquals(2, appended.first().rows)
        assertEquals(true, appended.first().compactRows)
        assertEquals(rows.params, appended.first().params)
        assertEquals(Chip("Focus", params = "focus"), appended.first().feedChip)
        assertEquals(rows.continuation, appended.first().continuation)
        assertEquals(rows.isPersonal, appended.first().isPersonal)
        assertEquals(HomeShelfHeaderAction.BROWSE, appended.first().headerAction)
        Settings.shared.toggleHomeSectionVisibility("section:rows:rows-params", false)
        Settings.shared.toggleHomeSectionVisibility("section:Last:", false)
        assertEquals(original, build(state.copy(chipFeeds = listOf(HomeChipFeed(Chip("Focus"), listOf(hidden, rows, last))))))
    }

    @Test
    fun appendedChipPagesKeepTheirFeedIdentityAndShelfEndpoints() {
        val main = HomeSection("Main", listOf(song("main")), browseId = "mixes", params = "main-more", continuation = "main-next")
        val source = HomeSection("Chip mixes", listOf(song("chip")), browseId = "mixes", isPersonal = true)
        val chips = listOf(Chip("Focus", "focus-feed"), Chip("Relax", "relax-feed", "relax-home"))
        for (section in listOf(source, source.copy(params = "shelf-more"), source.copy(continuation = "shelf-next"))) {
            val shelves =
                chips.map { chip ->
                    build(
                        HomeUiState(
                            data = listOf(main),
                            sections = listOf(main),
                            chipFeeds = listOf(HomeChipFeed(chip, listOf(section))),
                        ),
                    ).last()
                }
            assertNotEquals(shelves[0].pageKey, shelves[1].pageKey)
            shelves.zip(chips).forEach { (shelf, chip) ->
                assertEquals(chip, shelf.feedChip)
                assertNotEquals(shelf.copy(feedChip = null).pageKey, shelf.pageKey)
                assertEquals(HomeShelfHeaderAction.LOCAL, shelf.headerNavigationAction(hasSectionPage = true))
                val page = shelf.toHomeSection()
                assertEquals(section.items, page.items)
                assertEquals(section.params, page.params)
                assertEquals(section.continuation, page.continuation)
                assertEquals(section.isPersonal, page.isPersonal)
                assertEquals(section.browseId.takeIf { section.params != null || section.continuation != null }, page.browseId)
            }
        }
    }

    private fun build(state: HomeUiState) = HomeShelfBuilder.build(state, text, 400.dp)

    private fun song(id: String) = SongItem(id, id, null, emptyList(), null)

    private val text =
        HomeContentText(
            moodsAndGenres = "Moods and genres",
            speedDial = "Speed Dial",
            newReleases = "New releases",
            similarTo = "Similar to",
            podcastsForYou = "Podcasts for you",
            charts = "Charts",
            albumCharts = "Album charts",
            editorialPlaylists = "From the community",
            recapPlaylists = "Recap",
            shuffle = "Shuffle",
            playAll = "Play all",
            keepListening = "Keep listening",
            quickPicks = "Quick picks",
            forgottenFavorites = "Forgotten favorites",
            loading = "Loading",
            tryAgain = "Try again",
            homeUnavailable = "Home unavailable",
        )
}
