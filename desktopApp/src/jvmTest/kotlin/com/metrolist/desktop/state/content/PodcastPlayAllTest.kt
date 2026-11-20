package com.metrolist.desktop.state.content

import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.PodcastPage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class PodcastPlayAllTest {
    private val loaded =
        PodcastPage(
            PodcastItem("show", "Show", null),
            listOf(EpisodeItem("a", "A", null), EpisodeItem("b", "B", null)),
            continuations = listOf("next"),
        )

    @Test
    fun playAllResumesFromTheLoadedSnapshot() =
        runBlocking {
            val complete = loaded.copy(episodes = loaded.episodes + EpisodeItem("c", "C", null), continuations = emptyList())
            val result =
                loadPodcastForPlayback(loaded) { initial ->
                    assertSame(loaded, initial)
                    assertEquals("next", initial.continuation)
                    assertEquals(listOf("a", "b"), initial.episodes.map { it.id })
                    complete
                }
            assertSame(complete, result)
        }

    @Test
    fun completePageDoesNotLoadAgain() =
        runBlocking {
            val complete = loaded.copy(continuations = emptyList())
            assertSame(complete, loadPodcastForPlayback(complete) { error("Complete page already loaded") })
        }
}
