package com.metrolist.shared.ui.mobile

import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MobilePlaylistReorderTest {
    @Test
    fun dragUpdatesDuplicateOccurrencesImmediatelyAndPersistsOnlyItsFinalMove() =
        runTest {
            val source = entries(1, 2, 3, 4, 5)
            val saved = entries(2, 3, 4, 1, 5)
            val calls = mutableListOf<Pair<Int, Int?>>()
            val reorder =
                MobilePlaylistReorder(backgroundScope, { id, successor ->
                    calls += id to successor
                    Result.success(Unit)
                }, { saved })
            reorder.update(source)
            for (target in 2..4) {
                reorder.move(1, target)
                assertEquals(1, reorder.entries[target - 1].second.id)
            }
            runCurrent()
            assertTrue(calls.isEmpty())
            assertEquals(listOf(2, 3, 4, 1, 5), reorder.ids())
            reorder.update(source)
            assertEquals(listOf(2, 3, 4, 1, 5), reorder.ids())
            reorder.commit { error("Unexpected failure") }
            reorder.commit { error("Duplicate commit") }
            runCurrent()
            assertEquals(listOf<Pair<Int, Int?>>(1 to 5), calls)
            assertEquals(saved, reorder.entries)
        }

    @Test
    fun filteredTargetsKeepHiddenOccurrencesAndCanMoveInBothDirections() =
        runTest {
            val calls = mutableListOf<Pair<Int, Int?>>()
            val reorder =
                MobilePlaylistReorder(backgroundScope, { id, successor ->
                    calls += id to successor
                    Result.success(Unit)
                }, { entries(2, 3, 1, 4, 5) })
            reorder.update(entries(1, 2, 3, 4, 5))
            reorder.move(1, 5)
            assertEquals(listOf(2, 3, 4, 5, 1), reorder.ids())
            reorder.move(1, 4)
            assertEquals(listOf(2, 3, 1, 4, 5), reorder.ids())
            reorder.commit { error("Unexpected failure") }
            runCurrent()
            assertEquals(listOf<Pair<Int, Int?>>(1 to 4), calls)
        }

    @Test
    fun failedSaveRestoresLatestDatabaseEntries() =
        runTest {
            val result = CompletableDeferred<Result<Unit>>()
            var failures = 0
            val reorder = MobilePlaylistReorder(backgroundScope, { _, _ -> result.await() }, { error("Failed move must not reload") })
            reorder.update(entries(1, 2, 3))
            reorder.move(3, 1)
            reorder.commit { failures++ }
            runCurrent()
            reorder.update(entries(1, 2, 3, 4))
            assertEquals(listOf(3, 1, 2), reorder.ids())
            result.complete(Result.failure(IllegalStateException()))
            runCurrent()
            assertEquals(1, failures)
            assertEquals(listOf(1, 2, 3, 4), reorder.ids())
        }

    @Test
    fun consecutiveDragsSaveInOrderWithoutReplacingAnActivePreview() =
        runTest {
            val firstSave = CompletableDeferred<Unit>()
            val secondSave = CompletableDeferred<Unit>()
            val calls = mutableListOf<Pair<Int, Int?>>()
            var stored = entries(1, 2, 3)
            val reorder =
                MobilePlaylistReorder(backgroundScope, { id, successor ->
                    calls += id to successor
                    if (calls.size == 1) {
                        firstSave.await()
                        stored = entries(2, 3, 1)
                    } else {
                        secondSave.await()
                        stored = entries(3, 2, 1)
                    }
                    Result.success(Unit)
                }, { stored })
            reorder.update(stored)
            reorder.move(1, 3)
            reorder.commit { error("Unexpected failure") }
            runCurrent()
            reorder.move(3, 2)
            reorder.commit { error("Unexpected failure") }
            runCurrent()
            assertEquals(listOf<Pair<Int, Int?>>(1 to null), calls)
            firstSave.complete(Unit)
            runCurrent()
            assertEquals(listOf(3, 2, 1), reorder.ids())
            reorder.update(entries(2, 3, 1))
            assertEquals(listOf(3, 2, 1), reorder.ids())
            secondSave.complete(Unit)
            runCurrent()
            assertEquals(listOf(1 to null, 3 to 2), calls)
            assertEquals(stored, reorder.entries)
            reorder.update(entries(3, 2, 1, 4))
            assertEquals(listOf(3, 2, 1, 4), reorder.ids())
        }

    @Test
    fun invalidOrUnchangedMovesDoNotWrite() =
        runTest {
            val reorder = MobilePlaylistReorder(backgroundScope, { _, _ -> error("Unexpected write") }, { error("Unexpected reload") })
            reorder.update(entries(1, 2))
            reorder.move(1, 1)
            reorder.move(-1, 2)
            reorder.move(1, -1)
            reorder.commit { error("Unexpected failure") }
            runCurrent()
            assertEquals(listOf(1, 2), reorder.ids())
        }

    @Test
    fun selectedOccurrencesPersistOnlyTheFinalBatchAndReloadAfterPartialFailure() =
        runTest {
            for (fail in listOf(false, true)) {
                var stored = entries(1, 2, 3, 4, 5)
                val calls = mutableListOf<Pair<Int, Int?>>()
                var failures = 0
                val reorder =
                    MobilePlaylistReorder(backgroundScope, { id, successor ->
                        calls += id to successor
                        if (fail && calls.size == 2) {
                            Result.failure(IllegalStateException())
                        } else {
                            val mutable = stored.toMutableList()
                            val item = mutable.removeAt(mutable.indexOfFirst { it.second.id == id })
                            val to = successor?.let { next -> mutable.indexOfFirst { it.second.id == next } } ?: mutable.size
                            mutable.add(to, item)
                            stored = mutable
                            Result.success(Unit)
                        }
                    }, { stored })
                reorder.update(stored)
                reorder.move(2, 5, setOf(2, 4))
                reorder.move(2, 3, setOf(2, 4))
                assertEquals(listOf(1, 2, 4, 3, 5), reorder.ids())
                reorder.commit { failures++ }
                runCurrent()
                assertEquals(listOf<Pair<Int, Int?>>(4 to 3), calls)
                assertEquals(stored, reorder.entries)
                calls.clear()
                reorder.move(2, 5, setOf(2, 4))
                reorder.commit { failures++ }
                runCurrent()
                assertEquals(2, calls.size)
                assertEquals(if (fail) 1 else 0, failures)
                assertEquals(stored, reorder.entries)
            }
        }

    private fun MobilePlaylistReorder.ids() = entries.map { it.second.id }

    private fun entries(vararg ids: Int): List<Pair<SongItem, PlaylistSongMap>> =
        ids.mapIndexed { position, id ->
            SongItem("same", "Song", null, emptyList(), null, playlistLocalMappingId = id) to
                PlaylistSongMap(id = id, playlistId = "LPtest", songId = "same", position = position)
        }
}
