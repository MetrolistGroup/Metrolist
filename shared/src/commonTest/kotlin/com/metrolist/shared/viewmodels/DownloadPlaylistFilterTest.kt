package com.metrolist.shared.viewmodels

import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.model.CollectionMeta
import com.metrolist.shared.model.CollectionSort
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.presentCollection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadPlaylistFilterTest {
    private val local = PlaylistEntity(id = "local", name = "Local", isLocal = true)
    private val remote = PlaylistEntity(id = "remote", name = "Remote", browseId = "PLremote")
    private val songs =
        listOf(
            SongItem("a", "Alpha", null, emptyList(), null),
            SongItem("b", "Beta", null, emptyList(), null),
            SongItem("c", "Gamma", null, emptyList(), null),
        )

    @Test
    fun pickerExcludesBrowseFeedsButKeepsLocalAndRemotePlaylists() {
        for (id in listOf("RDPN", "VLRDPN", "SE", "VLSE", "FEmusic_library_podcasts", "FEmusic_episodes", "MPSPshow")) {
            assertFalse(remote.copy(browseId = id).supportsDownloadPlaylistFilter(), id)
            assertTrue(local.copy(browseId = id).supportsDownloadPlaylistFilter(), id)
        }
        for (id in listOf("PLremote", "VLPLremote", "LM", "VLLM", "LM:account", "SS", "VLSS", "RDCLmusic", null)) {
            assertTrue(remote.copy(browseId = id, name = "New Episodes").supportsDownloadPlaylistFilter(), id)
        }
    }

    @Test
    fun membershipFiltersComposeWithSearchAndSortAndKeepDownloadOrder() {
        val state = DownloadPlaylistFilterState(local, songIds = setOf("c", "a", "not-downloaded"))
        assertEquals(listOf("a", "c"), state.apply(songs).map { it.id })
        assertEquals(listOf("b"), state.copy(exclude = true).apply(songs).map { it.id })
        val result = state.apply(songs).presentCollection("a", CollectionSort.NEWEST) { CollectionMeta(if (it.id == "c") 2 else 1, 0) }
        assertEquals(listOf("c", "a"), result.map { it.id })
        assertTrue(state.copy(songIds = emptySet()).apply(songs).isEmpty())
        assertEquals(songs, state.copy(songIds = emptySet(), exclude = true).apply(songs))
        assertEquals(songs, DownloadPlaylistFilterState().apply(songs))
    }

    @Test
    fun localMembershipUpdatesWithoutRemoteRequestsAndClearingRestoresAllDownloads() =
        runTest {
            val membership = MutableStateFlow(setOf("a"))
            val filter =
                DownloadPlaylistFilter(
                    scope = backgroundScope,
                    localMembership = { membership },
                    remoteMembership = { error("Local playlist must not fetch remote membership") },
                )
            filter.select(local)
            assertTrue(filter.state.value.loading)
            assertEquals(songs, filter.state.value.apply(songs))
            runCurrent()
            assertEquals(listOf(songs[0]), filter.state.value.apply(songs))
            filter.setExcluded(true)
            membership.value = setOf("a", "b")
            runCurrent()
            assertEquals(listOf(songs[2]), filter.state.value.apply(songs))
            filter.select(null)
            membership.value = emptySet()
            runCurrent()
            assertEquals(songs, filter.state.value.apply(songs))
        }

    @Test
    fun failedAndPendingRefreshKeepDownloadsVisibleUntilRetryCompletes() =
        runTest {
            var requests = 0
            val filter =
                DownloadPlaylistFilter(backgroundScope, { error("Remote must not use cached membership") }, {
                    requests++
                    if (requests == 2) error("Failed continuation")
                    if (requests == 1) setOf("a") else setOf("a", "b")
                })
            filter.setExcluded(true)
            filter.select(remote)
            assertEquals(songs, filter.state.value.apply(songs))
            runCurrent()
            assertEquals(listOf(songs[1], songs[2]), filter.state.value.apply(songs))
            filter.refresh()
            assertEquals(songs, filter.state.value.apply(songs))
            runCurrent()
            assertTrue(filter.state.value.failed)
            assertEquals(songs, filter.state.value.apply(songs))
            filter.setExcluded(false)
            assertEquals(songs, filter.state.value.apply(songs))
            filter.setExcluded(true)
            filter.refresh()
            runCurrent()
            assertFalse(filter.state.value.failed)
            assertEquals(listOf(songs[2]), filter.state.value.apply(songs))
            assertEquals(3, requests)
        }

    @Test
    fun switchingPlaylistRejectsLateResultsEvenWhenLoaderDoesNotCooperateWithCancellation() =
        runTest {
            val oldResult = CompletableDeferred<Set<String>>()
            val filter =
                DownloadPlaylistFilter(backgroundScope, { flowOf(setOf("b")) }, {
                    withContext(NonCancellable) { oldResult.await() }
                })
            filter.select(remote)
            runCurrent()
            filter.select(local)
            runCurrent()
            oldResult.complete(setOf("a"))
            runCurrent()
            assertEquals(local, filter.state.value.playlist)
            assertEquals(listOf(songs[1]), filter.state.value.apply(songs))
        }
}
