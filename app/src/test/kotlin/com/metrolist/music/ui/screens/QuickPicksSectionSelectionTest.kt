package com.metrolist.music.ui.screens

import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.pages.HomePage
import com.metrolist.music.db.entities.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickPicksSectionSelectionTest {
    @Test
    fun `dedicated quick picks stay authoritative and suppress homepage quick picks`() {
        val homeSections =
            listOf(
                section("Quick picks", listOf(songItem("s1"))),
                section("Trending", listOf(songItem("s2"))),
            )

        val result =
            selectQuickPicksSection(
                dedicatedQuickPicks = listOf(songEntity("local1")),
                homePageSections = homeSections,
                localizedQuickPicksTitle = "Quick picks",
            )

        assertTrue(result.hasDedicatedQuickPicks)
        assertEquals(HomeSection.QuickPicks, result.authoritativeSection)
        assertEquals(setOf(0), result.suppressedHomePageSectionIndexes)
    }

    @Test
    fun `homepage quick picks are promoted when dedicated quick picks are empty`() {
        val homeSections =
            listOf(
                section("Quick picks", listOf(songItem("s1"))),
                section("Quick picks", listOf(songItem("s2"))),
                section("Trending", listOf(songItem("s3"))),
            )

        val result =
            selectQuickPicksSection(
                dedicatedQuickPicks = emptyList(),
                homePageSections = homeSections,
                localizedQuickPicksTitle = "Quick picks",
            )

        assertFalse(result.hasDedicatedQuickPicks)
        assertEquals(HomeSection.HomePageSection(0), result.authoritativeSection)
        assertEquals(setOf(0, 1), result.suppressedHomePageSectionIndexes)
    }

    @Test
    fun `english quick picks title is detected when localized title differs`() {
        val homeSections = listOf(section("Quick picks", listOf(songItem("s1"))))

        val result =
            selectQuickPicksSection(
                dedicatedQuickPicks = null,
                homePageSections = homeSections,
                localizedQuickPicksTitle = "Sélection rapide",
            )

        assertEquals(HomeSection.HomePageSection(0), result.authoritativeSection)
    }

    @Test
    fun `non song sections are not treated as quick picks`() {
        val homeSections =
            listOf(
                section("Quick picks", listOf(albumItem("a1"))),
                section("Trending", listOf(songItem("s1"))),
            )

        val result =
            selectQuickPicksSection(
                dedicatedQuickPicks = null,
                homePageSections = homeSections,
                localizedQuickPicksTitle = "Quick picks",
            )

        assertEquals(null, result.authoritativeSection)
        assertTrue(result.suppressedHomePageSectionIndexes.isEmpty())
    }

    private fun section(title: String, items: List<YTItem>) =
        HomePage.Section(
            title = title,
            label = null,
            thumbnail = null,
            endpoint = null,
            items = items,
        )

    private fun songItem(id: String) =
        SongItem(
            id = id,
            title = id,
            artists = emptyList(),
            thumbnail = "",
        )

    private fun albumItem(id: String) =
        AlbumItem(
            browseId = id,
            playlistId = id,
            title = id,
            artists = emptyList(),
            year = null,
            thumbnail = "",
        )

    private fun songEntity(id: String) =
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
