package com.metrolist.shared.viewmodels

import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.PodcastPage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PodcastPagingTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun firstPageIsPublishedBeforeDemandAndCancellationStopsFurtherRequests() =
        runTest {
            val demand = Channel<Unit>()
            val received = mutableListOf<PodcastPage>()
            val first = PodcastPage(PodcastItem("show", "Show", null), emptyList(), continuations = listOf("next"))
            var requests = 0
            val job =
                launch {
                    collectPodcastPages(
                        flow {
                            requests++
                            emit(first)
                            requests++
                            emit(first.copy(continuations = listOf("last")))
                            requests++
                            emit(first.copy(continuations = emptyList()))
                        },
                        { demand.receive() },
                        received::add,
                    )
                }
            runCurrent()
            assertEquals(listOf(first), received)
            assertEquals(1, requests)
            demand.send(Unit)
            runCurrent()
            assertEquals(2, received.size)
            assertEquals(2, requests)
            job.cancel()
            runCurrent()
            assertEquals(2, requests)
        }
}
