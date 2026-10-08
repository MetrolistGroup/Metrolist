package com.metrolist.music.ui.screens

import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.HomePage
import com.metrolist.music.db.entities.Song
import com.metrolist.music.viewmodels.QuickPicksLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSectionOrderTest {
    private val otherSections =
        listOf(
            HomeSection.FromTheCommunity,
            HomeSection.DailyDiscover,
            HomeSection.KeepListening,
            HomeSection.AccountPlaylists,
            HomeSection.ForgottenFavorites,
            HomeSection.SimilarRecommendation(0),
            HomeSection.SimilarRecommendation(1),
            HomeSection.MoodAndGenres,
        )

    /** Mirrors how HomeScreen assembles the section list before ordering it. */
    private fun buildSections(
        quickPicks: List<Song>?,
        homePageSections: List<HomePage.Section>,
        randomize: Boolean,
        seed: Long,
    ): List<HomeSection> {
        val selection = selectQuickPicksSection(quickPicks, homePageSections, "Quick picks")
        val quickPicksSection = selection.authoritativeSection
        val list = mutableListOf<HomeSection>()
        if (quickPicksSection != null) list.add(quickPicksSection)
        list.add(HomeSection.SpeedDial)
        list.addAll(otherSections)
        homePageSections.indices
            .filterNot { it in selection.suppressedHomePageSectionIndexes }
            .forEach { list.add(HomeSection.HomePageSection(it)) }
        return orderHomeSections(list, quickPicksSection, randomize, seed)
    }

    private val homePageWithQuickPicks =
        listOf(
            section("Trending", "t1"),
            section("Quick picks", "q1"),
            section("New releases", "n1"),
        )

    @Test
    fun `dedicated quick picks sit directly above speed dial with and without randomization`() {
        for (randomize in listOf(true, false)) {
            for (seed in 0L until 50L) {
                val ordered = buildSections(listOf(song("a")), homePageWithQuickPicks, randomize, seed)
                assertEquals(listOf(HomeSection.QuickPicks, HomeSection.SpeedDial), ordered.take(2))
            }
        }
    }

    @Test
    fun `homepage quick picks fallback also sits directly above speed dial`() {
        for (randomize in listOf(true, false)) {
            for (seed in 0L until 50L) {
                val ordered = buildSections(null, homePageWithQuickPicks, randomize, seed)
                assertEquals(listOf(HomeSection.HomePageSection(1), HomeSection.SpeedDial), ordered.take(2))
            }
        }
    }

    @Test
    fun `homepage section titled quick picks never duplicates the dedicated section`() {
        for (randomize in listOf(true, false)) {
            val ordered = buildSections(listOf(song("a")), homePageWithQuickPicks, randomize, 7L)
            assertEquals(1, ordered.count { it == HomeSection.QuickPicks })
            assertEquals(false, HomeSection.HomePageSection(1) in ordered)
            assertEquals(ordered.size, ordered.distinct().size)
            assertEquals(true, HomeSection.HomePageSection(0) in ordered)
            assertEquals(true, HomeSection.HomePageSection(2) in ordered)
        }
    }

    @Test
    fun `speed dial leads when no quick picks exist`() {
        val ordered = buildSections(null, listOf(section("Trending", "t1")), randomize = false, seed = 0L)
        assertEquals(HomeSection.SpeedDial, ordered.first())
    }

    @Test
    fun `quick picks section stays in place while a refresh is loading`() =
        runBlocking {
            val loader = QuickPicksLoader<Song> { it.id }
            loader.load(local = { listOf(song("a")) }, similar = { emptyList() })

            val local = CompletableDeferred<List<Song>>()
            val similar = CompletableDeferred<List<Song>>()
            val refresh =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    loader.load(local = { local.await() }, similar = { similar.await() })
                }

            for (stage in 0..2) {
                when (stage) {
                    1 -> local.complete(emptyList())
                    2 -> similar.complete(emptyList())
                }
                repeat(10) { yield() }
                val ordered = buildSections(loader.items.value, homePageWithQuickPicks, randomize = true, seed = stage.toLong())
                assertEquals(listOf(HomeSection.QuickPicks, HomeSection.SpeedDial), ordered.take(2))
            }
            refresh.join()
        }

    private fun section(title: String, songId: String) =
        HomePage.Section(
            title = title,
            label = null,
            thumbnail = null,
            endpoint = null,
            items = listOf(SongItem(id = songId, title = songId, artists = emptyList(), thumbnail = "")),
        )

    private fun song(id: String) =
        Song(
            id = id,
            title = id,
            duration = null,
            thumbnailUrl = null,
            albumId = null,
            liked = false,
            totalPlayTime = 0,
            inLibrary = null,
        )
}
