package com.metrolist.desktop.state

import com.metrolist.innertubex.models.YouTubeDataPage
import com.metrolist.shared.api.loadBrowsePage
import com.metrolist.shared.model.BrowsePageData
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BrowsePageLoaderTest {
    @Test
    fun loadsOnlyOnePageAndKeepsCursorForRetry() =
        runBlocking<Unit> {
            val first = HomeSection("First", emptyList())
            val second = HomeSection("Second", emptyList())
            val initial = BrowsePageData("Browse", listOf(first), "next-1")
            var calls = 0
            val result =
                loadBrowsePage(initial, emptySet(), { token ->
                    calls++
                    assertEquals("next-1", token)
                    listOf(second) to "next-2"
                }, { error("No shelf cursor") })
            assertEquals(1, calls)
            assertEquals(initial.copy(sections = listOf(first, second), continuation = "next-2"), result)
            assertFailsWith<IllegalStateException> { loadBrowsePage(result, emptySet(), { error("Offline") }, { error("No shelf") }) }
            assertEquals("next-2", result.continuation)
            assertFailsWith<CancellationException> {
                loadBrowsePage(
                    result,
                    emptySet(),
                    { throw CancellationException() },
                    { error("No shelf") },
                )
            }
        }

    @Test
    fun shelfPagesAppendBeforeOuterContinuationAndStopRepeatedTokens() =
        runBlocking<Unit> {
            val song = SongItem("song", "Song", null, emptyList(), null)
            val initial =
                BrowsePageData(sections = listOf(HomeSection("Shelf", listOf(song), continuation = "shelf")), continuation = "outer")
            val next =
                loadBrowsePage(initial, emptySet(), { error("Shelf must load first") }, { token ->
                    assertEquals("shelf", token)
                    YouTubeDataPage(listOf(song.copy(id = "second")), "shelf")
                })
            assertEquals(
                listOf("song", "second"),
                next.sections
                    .single()
                    .items
                    .map { it.id },
            )
            assertEquals(null, next.sections.single().continuation)
            val last = loadBrowsePage(next, setOf("earlier"), { emptyList<HomeSection>() to "earlier" }, { error("Shelf complete") })
            assertEquals(null, last.continuation)
        }
}
