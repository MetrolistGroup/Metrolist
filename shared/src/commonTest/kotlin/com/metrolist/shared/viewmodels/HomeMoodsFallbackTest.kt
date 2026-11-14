package com.metrolist.shared.viewmodels

import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HomeMoodsFallbackTest {
    @Test
    fun terminalMainPageAppendsOnlyExploreMoodsOnceAfterAllRecommendations() =
        runTest {
            var requests = 0
            var main = listOf(recommendations)
            for (continuation in listOf("first-next", "last-next")) {
                val unchanged =
                    main.appendMissingHomeMoods(
                        signedIn = true,
                        isFiltered = false,
                        continuation = continuation,
                        loadExplore = {
                            requests++
                            listOf(moods)
                        },
                    )
                assertEquals(main, unchanged)
                assertEquals(0, requests)
                main = main + recommendations.copy(title = continuation, items = listOf(song(continuation)))
            }
            val focus = BrowseItem("FEmusic_moods_and_genres_category", "Focus", params = "focus")
            val mixedMoods = moods.copy(items = moods.items + song("unrelated-mood-song"))
            val duplicateMoods = moods.copy(items = moods.items + focus)
            val charts = HomeSection("Charts", listOf(song("chart")), browseId = "FEmusic_charts")
            val other = HomeSection("Other categories", moods.items, browseId = "FEmusic_other")
            val invalidMoods = moods.copy(items = listOf(song("no-category-items")))
            val completed =
                main.appendMissingHomeMoods(
                    signedIn = true,
                    isFiltered = false,
                    continuation = null,
                    loadExplore = {
                        requests++
                        listOf(charts, mixedMoods, other, duplicateMoods, invalidMoods)
                    },
                )
            assertEquals(1, requests)
            assertEquals(main + moods.copy(items = moods.items + focus), completed)
            val again =
                completed.appendMissingHomeMoods(
                    signedIn = true,
                    isFiltered = false,
                    continuation = null,
                    loadExplore = {
                        requests++
                        listOf(moods)
                    },
                )
            assertEquals(completed, again)
            assertEquals(1, requests)
        }

    @Test
    fun moodsAlreadyInTheInitialOrLastMainPageSkipExplore() =
        runTest {
            var requests = 0
            val initialWithMoods = listOf(moods, recommendations)
            val initial =
                initialWithMoods.appendMissingHomeMoods(
                    signedIn = true,
                    isFiltered = false,
                    continuation = null,
                    loadExplore = {
                        requests++
                        listOf(moods)
                    },
                )
            assertEquals(initialWithMoods, initial)
            val firstPage =
                listOf(recommendations).appendMissingHomeMoods(
                    signedIn = true,
                    isFiltered = false,
                    continuation = "last-main-page",
                    loadExplore = {
                        requests++
                        listOf(moods)
                    },
                )
            val lastPage = firstPage + moods
            val completed =
                lastPage.appendMissingHomeMoods(
                    signedIn = true,
                    isFiltered = false,
                    continuation = null,
                    loadExplore = {
                        requests++
                        listOf(moods)
                    },
                )
            assertEquals(lastPage, completed)
            assertEquals(0, requests)
        }

    @Test
    fun signedOutAndFilteredFeedsNeverRequestExploreMoods() =
        runTest {
            var requests = 0
            val main = listOf(recommendations)
            for ((signedIn, isFiltered) in listOf(false to false, false to true, true to true)) {
                val result =
                    main.appendMissingHomeMoods(
                        signedIn = signedIn,
                        isFiltered = isFiltered,
                        continuation = null,
                        loadExplore = {
                            requests++
                            listOf(moods)
                        },
                    )
                assertEquals(main, result)
            }
            assertEquals(0, requests)
        }

    @Test
    fun failedExploreRequestLeavesTheMainFeedIntact() =
        runTest {
            val main = listOf(recommendations)
            val result =
                main.appendMissingHomeMoods(
                    signedIn = true,
                    isFiltered = false,
                    continuation = null,
                    loadExplore = { throw IllegalStateException("Explore unavailable") },
                )
            assertEquals(main, result)
        }

    @Test
    fun cancellationStopsTheFallback() =
        runTest {
            val cancellation = CancellationException("Home changed")
            val thrown =
                assertFailsWith<CancellationException> {
                    listOf(recommendations).appendMissingHomeMoods(
                        signedIn = true,
                        isFiltered = false,
                        continuation = null,
                        loadExplore = { throw cancellation },
                    )
                }
            assertEquals(cancellation.message, thrown.message)
        }

    private val recommendations = HomeSection("Recommendations", listOf(song("main")), browseId = "FEmusic_recommendations")
    private val moods =
        HomeSection(
            "Moods and genres",
            listOf(BrowseItem("FEmusic_moods_and_genres_category", "Relax", params = "relax")),
            subtitle = "Explore music",
            browseId = "FEmusic_moods_and_genres",
            params = "moods-overview",
            thumbnail = "moods-artwork",
            headerBrowseId = "moods-header",
        )

    private fun song(id: String) = SongItem(id, id, null, emptyList(), null)
}
