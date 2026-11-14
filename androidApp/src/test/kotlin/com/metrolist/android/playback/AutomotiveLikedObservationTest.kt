package com.metrolist.android.playback

import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomotiveLikedObservationTest {
    @Test
    fun legacyCollectionFollowsFallbackAndStopsOnModernLikesAccountChangesAndCancellation() =
        runTest {
            val legacy = MutableStateFlow(listOf(song("legacy")))
            val modern = listOf(song("modern"))
            val source = MutableStateFlow(AutomotiveLikedQueueSource(emptyList(), "account", signedOut = false))
            var activeCollections = 0
            var starts = 0
            val observed = mutableListOf<AutomotiveLikedQueueSource>()
            val observer =
                launch {
                    source
                        .withLegacyLikes {
                            flow {
                                starts++
                                activeCollections++
                                try {
                                    legacy.collect { emit(it) }
                                } finally {
                                    activeCollections--
                                }
                            }
                        }.collect { observed += it }
                }
            runCurrent()
            assertEquals(0, starts)
            assertEquals(emptyList<SongItem>(), observed.last().likes)

            source.value = AutomotiveLikedQueueSource(emptyList(), "", signedOut = true)
            runCurrent()
            assertEquals(1, activeCollections)
            assertEquals(legacy.value, observed.last().likes)

            source.value = source.value.copy(likes = modern)
            runCurrent()
            assertEquals(0, activeCollections)
            assertEquals(modern, observed.last().likes)
            val count = observed.size
            legacy.value = listOf(song("changed-legacy"))
            runCurrent()
            assertEquals(count, observed.size)

            source.value = source.value.copy(likes = emptyList())
            runCurrent()
            assertEquals(2, starts)
            assertEquals(legacy.value, observed.last().likes)
            legacy.value = emptyList()
            runCurrent()
            assertEquals(emptyList<SongItem>(), observed.last().likes)

            source.value = AutomotiveLikedQueueSource(emptyList(), "other-account", signedOut = false)
            runCurrent()
            assertEquals(0, activeCollections)
            assertEquals(source.value, observed.last())

            source.value = source.value.copy(account = "", signedOut = true)
            runCurrent()
            assertEquals(1, activeCollections)
            observer.cancel()
            runCurrent()
            assertEquals(0, activeCollections)
        }

    private fun song(id: String) = SongItem(id, id, null, emptyList(), null)
}
