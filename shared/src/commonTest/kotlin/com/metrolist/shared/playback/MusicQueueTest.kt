package com.metrolist.shared.playback

import com.metrolist.shared.model.SongItem
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MusicQueueTest {
    @Test
    fun legacyUnshuffleRestoresOriginalOrderAndCurrentOccurrence() {
        val duplicate = song("duplicate")
        val original = listOf(song("first"), duplicate, song("third"), duplicate)
        val order = listOf(2, 0, 3, 1)
        val queue = MusicQueue()
        queue.restore(MusicQueueSnapshot(order.map(original::get), original, 2, true, order))
        val currentKey =
            queue.state.value.entries[2]
                .key

        queue.toggleShuffle(legacyShuffle = true)

        assertFalse(queue.isShuffled)
        assertEquals(original, queue.items.value)
        assertEquals(3, queue.currentIndex.value)
        assertEquals(
            currentKey,
            queue.state.value.entries[3]
                .key,
        )
        assertEquals(original.indices.toList(), queue.snapshot().originalIndices)
    }

    @Test
    fun legacyShuffleRoundTripPreservesSelectionIncludingNoCurrentSong() {
        val songs = listOf(song("first"), song("second"), song("third"))
        for (index in -1..songs.lastIndex) {
            val queue = MusicQueue().apply { setQueue(songs, index) }
            queue.toggleShuffle(legacyShuffle = true)
            queue.toggleShuffle(legacyShuffle = true)
            assertEquals(songs, queue.items.value)
            assertEquals(index, queue.currentIndex.value)
        }
    }

    @Test
    fun undoCannotRestoreSongsIntoAReplacementQueueEvenWhenTheSongsAreEqual() {
        val queue = MusicQueue()
        val songs = listOf(song("a"), song("b"), song("c"))
        queue.setQueue(songs)
        val removal = queue.removeAt(setOf(1))!!
        queue.setQueue(listOf(songs[0], songs[2]))
        queue.restore(removal)
        assertEquals(listOf(songs[0], songs[2]), queue.items.value)
    }

    @Test
    fun undoIdentitySurvivesPendingQueueSnapshotsButNotSerializedReplacementQueues() {
        val queue = MusicQueue().apply { setQueue(listOf(song("a"), song("b"), song("c"))) }
        val removal = queue.removeAt(setOf(1))!!
        val pending = MusicQueue().apply { restore(queue.snapshot()) }
        pending.move(1, 0)
        val resumed = MusicQueue().apply { restore(pending.snapshot()) }
        resumed.restore(removal)
        assertEquals(
            setOf("a", "b", "c"),
            resumed.items.value
                .map { it.id }
                .toSet(),
        )
        val replacement = MusicQueue().apply { restore(Json.decodeFromString<MusicQueueSnapshot>(Json.encodeToString(queue.snapshot()))) }
        replacement.restore(removal)
        assertEquals(listOf("a", "c"), replacement.items.value.map { it.id })
    }

    @Test
    fun removingBlockedCurrentSongRetainsItsSuccessorIncludingDuplicateRemovals() {
        for (index in 0..2) {
            val queue = MusicQueue().apply { setQueue(listOf(song("a"), song("b"), song("c")), index) }
            queue.removeSongs(setOf(listOf("a", "b", "c")[index]), keepCurrent = false)
            assertEquals(listOf("b", "c", null)[index], queue.getNext()?.id)
        }
        val queue = MusicQueue().apply { setQueue(listOf(song("b"), song("a"), song("b"), song("c")), 2) }
        queue.removeSongs(setOf("b"), keepCurrent = false)
        assertEquals("c", queue.getNext()?.id)
    }

    @Test
    fun repeatAllCanWrapWithoutReplacingShuffleOrItsCanonicalOrder() {
        val songs = listOf(song("a"), song("b"), song("c"))
        val queue =
            MusicQueue().apply {
                setQueue(songs, 1)
                toggleShuffle()
            }
        val order =
            queue.state.value.entries
                .map { it.key }
        queue.skipTo(queue.items.value.lastIndex)
        queue.skipTo(0)
        assertTrue(queue.isShuffled)
        assertEquals(
            order,
            queue.state.value.entries
                .map { it.key },
        )
        queue.toggleShuffle()
        assertEquals(songs, queue.items.value)
    }

    @Test
    fun shufflePreservesAnUnselectedQueue() {
        val queue = MusicQueue()
        val songs = listOf(song("first"), song("second"))
        queue.restore(MusicQueueSnapshot(displayedItems = songs, originalItems = songs, currentIndex = -1))
        queue.toggleShuffle()
        assertTrue(queue.shuffleEnabled.value)
        assertEquals(-1, queue.currentIndex.value)
        assertEquals(null, queue.currentSong())
        queue.toggleShuffle()
        assertEquals(-1, queue.currentIndex.value)
        assertEquals(songs, queue.items.value)
    }

    @Test
    fun shuffleNeverReplaysHistoryOrSkipsUnplayedOccurrences() {
        val original = listOf(song("a"), song("b"), song("c"), song("d"))
        val queue =
            MusicQueue().apply {
                setQueue(original, 1)
                toggleShuffle()
            }
        assertEquals(original.take(2), queue.items.value.take(2))
        assertEquals(1, queue.currentIndex.value)
        queue.restore(MusicQueueSnapshot(listOf(original[0], original[3], original[1], original[2]), original, 1, true, listOf(0, 3, 1, 2)))
        queue.toggleShuffle()
        assertEquals(listOf("a", "d", "b", "c"), queue.items.value.map { it.id })
        assertEquals("d", queue.currentItem()?.id)
        assertEquals("b", queue.peekNext()?.id)
    }

    @Test
    fun shuffleKeepsGeneratedRecommendationsAfterExplicitOccurrences() {
        val duplicate = song("duplicate")
        val recommendations =
            listOf(QUEUE_SOURCE_RECOMMENDED, QUEUE_SOURCE_AUTO_MIX, "Recommended").map { source ->
                duplicate.copy(queueSourceTitle = source)
            }
        val explicitSongs =
            listOf(
                song("history"),
                duplicate,
                duplicate,
                song("manual").copy(queueSourceTitle = QUEUE_SOURCE_ADDED_MANUALLY),
                song("playlist").copy(queueSourceTitle = "playlist"),
                duplicate,
            )
        val songs = explicitSongs + recommendations + recommendations
        for (startIndex in listOf(-1, 2, explicitSongs.size)) {
            for (preserveHistory in listOf(true, false)) {
                val queue = MusicQueue().apply { setQueue(songs, startIndex) }
                val original = queue.state.value.entries
                val prefix = if (preserveHistory) original.take(startIndex + 1) else listOfNotNull(original.getOrNull(startIndex))
                val upcoming = original.filterNot { it in prefix }
                val explicit = original.take(explicitSongs.size).filterNot { it in prefix }
                val generated = original.drop(explicitSongs.size).filterNot { it in prefix }

                queue.toggleShuffle(preserveHistory)

                val shuffled = queue.state.value.entries
                assertTrue(queue.isShuffled)
                assertEquals(prefix.lastIndex, queue.currentIndex.value)
                assertEquals(prefix, shuffled.take(prefix.size))
                assertEquals(explicit.toSet(), shuffled.drop(prefix.size).take(explicit.size).toSet())
                assertEquals(generated, shuffled.drop(prefix.size + explicit.size))
                assertEquals(original.map { it.key }.sorted(), shuffled.map { it.key }.sorted())
                assertEquals(songs, queue.snapshot().originalItems)

                queue.toggleShuffle()

                assertFalse(queue.isShuffled)
                assertEquals(prefix + upcoming, queue.state.value.entries)
                assertEquals(prefix.lastIndex, queue.currentIndex.value)
            }
        }
    }

    @Test
    fun disablingShuffleAfterAdvancingPreservesHistoryAndRestoresUpcomingRecommendations() {
        val duplicate = song("duplicate")
        val songs =
            listOf(song("history"), duplicate, duplicate, song("manual")) +
                List(10) { song("recommended-$it").copy(queueSourceTitle = QUEUE_SOURCE_RECOMMENDED) }
        val queue = MusicQueue().apply { setQueue(songs, 1) }
        val original = queue.state.value.entries
        queue.toggleShuffle()
        queue.next()
        val played =
            queue.state.value.entries
                .take(3)

        queue.toggleShuffle()

        assertEquals(2, queue.currentIndex.value)
        assertEquals(played + original.filterNot { it in played }, queue.state.value.entries)
        assertEquals(played.last().song, queue.currentItem())
    }

    @Test
    fun freshShuffleVisitsEveryOccurrenceAndRetainsCanonicalUpcomingOrder() {
        val songs = List(50) { song("song-${it / 2}") }
        for (startIndex in listOf(0, 40, songs.lastIndex)) {
            for (disableShuffle in listOf(false, true)) {
                val queue = MusicQueue().apply { setQueue(songs, startIndex) }
                val originalEntries = queue.state.value.entries
                val selected = originalEntries[startIndex]

                queue.toggleShuffle(preserveHistory = false)

                assertTrue(queue.isShuffled)
                assertEquals(0, queue.currentIndex.value)
                assertEquals(
                    selected,
                    queue.state.value.entries
                        .first(),
                )
                assertEquals(songs, queue.snapshot().originalItems)
                assertEquals(startIndex, queue.snapshot().originalIndices.first())
                if (disableShuffle) {
                    queue.toggleShuffle()
                    assertFalse(queue.isShuffled)
                    assertEquals(
                        listOf(selected) + originalEntries.filterNot { it.key == selected.key },
                        queue.state.value.entries,
                    )
                }

                val visited = mutableListOf(selected.key)
                while (queue.next() != null) {
                    visited +=
                        queue.state.value.entries[queue.currentIndex.value]
                            .key
                }
                assertEquals(originalEntries.map { it.key }.sorted(), visited.sorted())
            }
        }
    }

    @Test
    fun bulkUndoMergesAlternatingOccurrencesInBothOrders() {
        val songs = List(20_000) { song("song-${it / 2}") }
        val queue = MusicQueue().apply { setQueue(songs, 10_000) }
        val before = queue.snapshot()
        val removal = queue.removeAt(songs.indices.filter { it % 2 == 0 }.toSet())!!
        queue.restore(removal)
        assertEquals(before, queue.snapshot())
    }

    @Test
    fun singleItemShufflePublishesModeChange() {
        val queue = MusicQueue()
        queue.setQueue(listOf(song("only")))

        queue.toggleShuffle()

        assertTrue(queue.shuffleEnabled.value)
        assertEquals(listOf("only"), queue.items.value.map { it.id })
    }

    @Test
    fun replaceCurrentUpdatesDisplayedAndCanonicalQueues() {
        val queue = MusicQueue()
        val first = song("first")
        val second = song("second")
        val replacement = song("replacement")
        queue.setQueue(listOf(first, second), startIndex = 1)

        assertEquals(second, queue.replaceCurrent(replacement))

        assertEquals(replacement, queue.currentSong())
        assertEquals(listOf(first, replacement), queue.snapshot().originalItems)
    }

    @Test
    fun duplicatesArePreservedUnlessPreventionIsRequested() {
        val queue = MusicQueue()
        val song = song("same")
        queue.setQueue(listOf(song("current"), song), startIndex = 0)
        queue.addToQueue(song)
        assertEquals(listOf("current", "same", "same"), queue.items.value.map { it.id })

        queue.addToQueue(song, preventDuplicates = true)
        assertEquals(2, queue.items.value.size)
        assertEquals(listOf("current", "same"), queue.items.value.map { it.id })
    }

    @Test
    fun moveReordersWithoutChangingTheCurrentOccurrence() {
        val current = song("same").copy(title = "current")
        val duplicate = song("same").copy(title = "duplicate")
        val queue = MusicQueue()
        queue.setQueue(listOf(song("first"), current, duplicate), startIndex = 1)

        queue.move(0, 2)

        assertEquals(listOf("current", "duplicate", "first"), queue.items.value.map { it.title })
        assertEquals("current", queue.currentItem()?.title)
        assertEquals(0, queue.currentIndex.value)
    }

    @Test
    fun removeAndRestorePreservesDuplicateOccurrencesAndCurrentIndex() {
        val queue = MusicQueue()
        val duplicate = song("same")
        val original = listOf(song("a"), duplicate, song("b"), duplicate, song("c"), song("d"))
        queue.setQueue(original, startIndex = 3)
        val removal = queue.removeAt(setOf(0, 3))!!

        assertEquals(listOf("same", "b", "c", "d"), queue.items.value.map { it.id })
        assertEquals(2, queue.currentIndex.value)

        queue.restore(removal)
        assertEquals(original, queue.items.value)
        assertEquals(3, queue.currentIndex.value)
    }

    @Test
    fun combinedUndoRestoresSuccessiveRemovals() {
        for (shuffled in listOf(false, true)) {
            val queue = MusicQueue()
            queue.setQueue(listOf(song("a"), song("b"), song("c"), song("d"), song("e")), startIndex = 2)
            if (shuffled) queue.toggleShuffle()
            val before = queue.snapshot()
            val first = queue.removeAt(setOf(0))!!
            val second = queue.removeAt(setOf(1))!!
            queue.restore(listOf(first, second))
            assertEquals(before, queue.snapshot())
        }
    }

    @Test
    fun restoreKeepsPlaybackAdvanceMadeAfterRemoval() {
        val queue = MusicQueue()
        queue.setQueue(listOf(song("a"), song("b"), song("c"), song("d")), startIndex = 1)
        val removal = queue.removeAt(setOf(0))!!

        assertEquals("c", queue.getNext()!!.id)
        queue.restore(removal)

        assertEquals(listOf("a", "b", "c", "d"), queue.items.value.map { it.id })
        assertEquals("c", queue.currentItem()!!.id)
        assertEquals("d", queue.getNext()!!.id)
    }

    @Test
    fun removeAndRestoreWhileShuffledPreservesCanonicalOrder() {
        val queue = MusicQueue()
        val original = listOf(song("a"), song("b"), song("c"), song("d"), song("e"))
        val displayed = listOf(original[2], original[0], original[4], original[1], original[3])
        for (unshuffleBeforeUndo in listOf(false, true)) {
            queue.restore(MusicQueueSnapshot(displayed, original, currentIndex = 0, isShuffled = true))
            val removal = queue.removeAt(setOf(1, 4))!!
            assertEquals(listOf(0, 3), removal.originalItems.map { it.index })
            if (unshuffleBeforeUndo) queue.toggleShuffle()
            queue.restore(removal)
            if (!unshuffleBeforeUndo) {
                assertEquals(displayed, queue.items.value)
                queue.toggleShuffle()
            }
            assertEquals(listOf(original[2], original[0], original[1], original[3], original[4]), queue.items.value)
            assertEquals(0, queue.currentIndex.value)
            assertEquals(original[2], queue.currentSong())
        }
    }

    @Test
    fun restoreAfterShuffleModeChangeKeepsTheCurrentItemAndCanonicalOrder() {
        val queue = MusicQueue()
        queue.setQueue(listOf(song("removed"), song("current")), startIndex = 0)
        queue.toggleShuffle()
        val removal = queue.removeAt(setOf(0))!!

        queue.toggleShuffle()
        queue.restore(removal)

        assertFalse(queue.isShuffled)
        assertEquals(listOf("removed", "current"), queue.items.value.map { it.id })
        assertEquals("current", queue.currentItem()?.id)
    }

    @Test
    fun serializedShufflePreservesEqualOccurrencesAcrossRestoreAndEdits() {
        val duplicate = song("same")
        val original = listOf(duplicate, song("b"), duplicate, song("c"))
        val queue =
            MusicQueue().apply {
                setQueue(original, startIndex = 2)
                toggleShuffle()
            }
        val saved = Json.decodeFromString<MusicQueueSnapshot>(Json.encodeToString(queue.snapshot()))
        assertEquals(2, saved.forRestore(rememberShuffle = false).currentIndex)
        queue.restore(saved)
        queue.move(0, queue.items.value.lastIndex)
        val beforeRemoval = queue.snapshot()
        val removal = queue.removeAt(setOf(queue.items.value.indexOf(original[1])))!!
        queue.restore(removal)
        assertEquals(beforeRemoval, queue.snapshot())
        queue.playNext(song("next"))
        queue.updateCurrentDuration(183L)
        queue.toggleShuffle()
        assertEquals(listOf("b", "same", "same", "next", "c"), queue.items.value.map { it.id })
        assertEquals(1, queue.currentIndex.value)
        assertEquals(null, queue.items.value[2].duration)
        assertEquals(183L, queue.items.value[1].duration)
    }

    @Test
    fun emptyAndOutOfRangeSnapshotsAreSafe() {
        val queue = MusicQueue()
        queue.restore(MusicQueueSnapshot(currentIndex = 50, isShuffled = true))
        assertEquals(emptyList(), queue.items.value)
        assertEquals(-1, queue.currentIndex.value)
        assertEquals(queue.currentItem(), null)
        assertEquals(queue.getNext(), null)
        assertEquals(queue.getPrevious(), null)
        queue.toggleShuffle()
        assertFalse(queue.isShuffled)
        assertEquals(-1, queue.currentIndex.value)

        queue.restore(MusicQueueSnapshot(listOf(song("a")), listOf(song("a")), 50, false))
        assertEquals(0, queue.currentIndex.value)
        queue.restore(MusicQueueSnapshot(listOf(song("a")), listOf(song("a")), -50, false))
        assertEquals(-1, queue.currentIndex.value)
        assertFalse(queue.isShuffled)
    }

    @Test
    fun restoreFallsBackToDisplayedOrderWhenCanonicalOrderIsMissing() {
        val queue = MusicQueue()
        queue.restore(
            MusicQueueSnapshot(
                displayedItems = listOf(song("a"), song("b")),
                currentIndex = 1,
                isShuffled = true,
            ),
        )

        queue.toggleShuffle()

        assertEquals(listOf("a", "b"), queue.items.value.map { it.id })
        assertEquals(1, queue.currentIndex.value)
    }

    @Test
    fun restoreRejectsCanonicalOrderWithDifferentSongs() {
        val queue = MusicQueue()
        queue.restore(
            MusicQueueSnapshot(
                displayedItems = listOf(song("a"), song("b")),
                originalItems = listOf(song("a"), song("foreign")),
                currentIndex = 1,
                isShuffled = true,
            ),
        )

        queue.toggleShuffle()

        assertEquals(listOf("a", "b"), queue.items.value.map { it.id })
    }

    @Test
    fun restoreRejectsInvalidCanonicalOccurrenceMappings() {
        val original = listOf(song("a"), song("b"))
        for (indices in listOf(listOf(0, 0), listOf(0, 1), listOf(-1, 0), listOf(2, 0), listOf(1))) {
            val restored =
                MusicQueueSnapshot(
                    displayedItems = original.reversed(),
                    originalItems = original,
                    currentIndex = 0,
                    isShuffled = true,
                    originalIndices = indices,
                ).forRestore(rememberShuffle = false)
            assertEquals(original.reversed(), restored.displayedItems)
            assertEquals(0, restored.currentIndex)
        }
    }

    @Test
    fun batchedPlayNextKeepsRequestedOrderInDisplayedAndCanonicalQueues() {
        val original = listOf(song("a"), song("b"))
        val batch = listOf(song("x"), song("y"))
        for (shuffled in listOf(false, true)) {
            for (singleAdds in listOf(false, true)) {
                for (startIndex in original.indices) {
                    val queue = MusicQueue().apply { setQueue(original, startIndex) }
                    if (shuffled) queue.toggleShuffle()
                    if (singleAdds) batch.asReversed().forEach { queue.playNext(it) } else queue.playNext(batch)
                    assertEquals(
                        batch,
                        queue.items.value
                            .drop(queue.currentIndex.value + 1)
                            .take(batch.size),
                    )
                    assertEquals(original[startIndex], queue.currentSong())
                    if (shuffled) queue.toggleShuffle()
                    assertEquals(original.toMutableList().apply { addAll(startIndex + 1, batch) }, queue.items.value)
                }
            }
        }
    }

    @Test
    fun removingAnotherSongKeepsTheExactDuplicateOccurrenceCurrent() {
        val first = song("same").copy(title = "first")
        val second = song("same").copy(title = "second")
        val queue = MusicQueue()
        queue.setQueue(listOf(first, song("remove"), second), startIndex = 2)

        queue.removeSongs(setOf("remove"))

        assertEquals("second", queue.currentItem()?.title)
        assertEquals(1, queue.currentIndex.value)
    }

    @Test
    fun removingDuplicateIdsWhileShuffledKeepsTheCurrentOccurrence() {
        val first = song("same").copy(title = "first")
        val second = song("same").copy(title = "second")
        val queue = MusicQueue()
        queue.setQueue(listOf(first, second, song("other")), startIndex = 1)
        queue.toggleShuffle()

        queue.removeSongs(setOf("same"), keepCurrent = true)
        queue.toggleShuffle()

        assertEquals(listOf("second", "other"), queue.items.value.map { it.title })
        assertEquals("second", queue.currentItem()?.title)
    }

    @Test
    fun learnedDurationSurvivesEquivalentRestoresAndTrackChanges() {
        val queue = MusicQueue()
        queue.setQueue(listOf(song("first"), song("second")), startIndex = 0)
        queue.restore(Json.decodeFromString<MusicQueueSnapshot>(Json.encodeToString(queue.snapshot())))

        queue.updateCurrentDuration(183L)
        queue.getNext()

        assertEquals(
            183L,
            queue.items.value
                .first()
                .duration,
        )
        assertEquals(
            183L,
            queue
                .snapshot()
                .originalItems
                .first()
                .duration,
        )
    }

    @Test
    fun occurrenceKeysStayUniqueAndFollowDuplicateOccurrencesWhenMoved() {
        val duplicate = song("same")
        val queue = MusicQueue()
        queue.setQueue(listOf(duplicate, song("other"), duplicate), startIndex = 1)
        val keysBefore =
            queue.state.value.entries
                .map { it.key }

        queue.move(from = 2, to = 0)

        assertEquals(
            3,
            queue.state.value.entries
                .map { it.key }
                .toSet()
                .size,
        )
        assertEquals(
            listOf(keysBefore[2], keysBefore[0], keysBefore[1]),
            queue.state.value.entries
                .map { it.key },
        )
        assertEquals("other", queue.currentItem()?.id)
        assertEquals(2, queue.currentIndex.value)
    }

    @Test
    fun moveReordersItemsAndUpdatesCurrentIndex() {
        val queue = MusicQueue()
        val songs = listOf(song("a"), song("b"), song("c"), song("d"), song("e"))
        queue.setQueue(songs, startIndex = 2)

        queue.move(from = 4, to = 3)
        assertEquals(listOf("a", "b", "c", "e", "d"), queue.items.value.map { it.id })
        assertEquals(2, queue.currentIndex.value)
        assertEquals("c", queue.currentSong()?.id)

        queue.move(from = 0, to = 3)
        assertEquals(listOf("b", "c", "e", "a", "d"), queue.items.value.map { it.id })
        assertEquals(1, queue.currentIndex.value)
        assertEquals("c", queue.currentSong()?.id)

        queue.move(from = 1, to = 4)
        assertEquals(listOf("b", "e", "a", "d", "c"), queue.items.value.map { it.id })
        assertEquals(4, queue.currentIndex.value)
        assertEquals("c", queue.currentSong()?.id)
    }

    @Test
    fun replaceUpcomingQueueReplacesUpcomingOnlyAndPreservesHistory() {
        val queue = MusicQueue()
        val original = listOf(song("a"), song("b"), song("c"), song("d"))
        queue.setQueue(original, startIndex = 1)

        val newUpcoming = listOf(song("x"), song("y"))
        queue.replaceUpcomingQueue(newUpcoming)

        assertEquals(listOf("a", "b", "x", "y"), queue.items.value.map { it.id })
        assertEquals(1, queue.currentIndex.value)
        assertEquals("b", queue.currentSong()?.id)
        assertEquals(listOf("a", "b", "x", "y"), queue.snapshot().originalItems.map { it.id })
    }

    @Test
    fun moveWithUpdatedItemUpdatesMetadataAndPosition() {
        val queue = MusicQueue()
        val original = listOf(song("a"), song("b"), song("c"))
        queue.setQueue(original, startIndex = 0)

        val updatedC = song("c").copy(queueSourceTitle = "manual")
        queue.move(from = 2, to = 1, updatedItem = updatedC)

        assertEquals(listOf("a", "c", "b"), queue.items.value.map { it.id })
        assertEquals("manual", queue.items.value[1].queueSourceTitle)
        assertEquals(0, queue.currentIndex.value)
    }

    @Test
    fun duplicatePreventionKeepsInsertionBoundaryAndCurrentOccurrence() {
        val original = listOf(song("a"), song("current"), song("b"), song("a"), song("tail"))
        for (shuffled in listOf(false, true)) {
            val queue =
                MusicQueue().apply {
                    restore(
                        MusicQueueSnapshot(
                            displayedItems = original,
                            originalItems = original,
                            currentIndex = 1,
                            isShuffled = shuffled,
                        ),
                    )
                }
            val currentKey =
                queue.state.value.entries[1]
                    .key

            queue.addToQueue(listOf(song("a"), song("b"), song("b"), song("current")), preventDuplicates = true, insertIndex = 2)

            assertEquals(listOf("current", "a", "b", "tail"), queue.items.value.map { it.id })
            assertEquals(0, queue.currentIndex.value)
            assertEquals(
                currentKey,
                queue.state.value.entries[0]
                    .key,
            )
            assertEquals(queue.snapshot(), MusicQueue().apply { restore(queue.snapshot()) }.snapshot())
        }
    }

    @Test
    fun duplicatePreventionClampsInsertionBeforeRemovingOccurrences() {
        val original = listOf(song("a"), song("current"), song("b"), song("a"), song("tail"))
        val cases =
            listOf(
                -1 to listOf("a", "b", "current", "tail"),
                1 to listOf("a", "b", "current", "tail"),
                4 to listOf("current", "a", "b", "tail"),
                99 to listOf("current", "tail", "a", "b"),
                null to listOf("current", "tail", "a", "b"),
            )
        for ((insertIndex, expected) in cases) {
            val queue = MusicQueue().apply { setQueue(original, startIndex = 1) }

            queue.addToQueue(listOf(song("a"), song("b")), preventDuplicates = true, insertIndex = insertIndex)

            assertEquals(expected, queue.items.value.map { it.id })
            assertEquals(expected.indexOf("current"), queue.currentIndex.value)
            assertEquals(queue.items.value, queue.snapshot().originalItems)
        }
    }

    @Test
    fun publishedSnapshotKeepsOrderAndIndexTogetherWhenDuplicateListsAreConflated() {
        val duplicate = song("same")
        val queue =
            MusicQueue().apply {
                restore(
                    MusicQueueSnapshot(
                        displayedItems = listOf(duplicate, duplicate, duplicate),
                        originalItems = listOf(duplicate, duplicate, duplicate),
                        currentIndex = 0,
                        isShuffled = true,
                        originalIndices = listOf(0, 1, 2),
                    ),
                )
            }
        val displayed = queue.snapshots.value
        val itemsBefore = queue.items.value
        queue.move(1, 2)
        assertSame(itemsBefore, queue.items.value)
        assertEquals(listOf(0, 1, 2), displayed.originalIndices)
        assertEquals(listOf(0, 2, 1), queue.snapshots.value.originalIndices)
        assertEquals(0, queue.snapshots.value.currentIndex)

        queue.skipTo(1)
        val removal = queue.removeAt(setOf(0))!!
        assertEquals(0, queue.snapshots.value.currentIndex)
        assertEquals(2, queue.snapshots.value.displayedItems.size)
        assertEquals(listOf(1, 0), queue.snapshots.value.originalIndices)
        queue.restore(removal)
        assertEquals(1, queue.snapshots.value.currentIndex)
        assertEquals(listOf(0, 2, 1), queue.snapshots.value.originalIndices)
    }

    @Test
    fun shuffledIdenticalOccurrenceSurvivesSerializedRestoreAndUnshuffle() {
        val duplicate = song("same")
        val queue = MusicQueue()
        queue.setQueue(listOf(duplicate, duplicate), startIndex = 1)
        queue.toggleShuffle()
        assertEquals(listOf(0, 1), queue.snapshot().originalIndices)

        val snapshot = Json.decodeFromString<MusicQueueSnapshot>(Json.encodeToString(queue.snapshot()))
        val restored = MusicQueue().apply { restore(snapshot) }
        assertEquals(snapshot.originalIndices, restored.snapshot().originalIndices)
        restored.toggleShuffle()

        assertEquals(listOf(duplicate, duplicate), restored.items.value)
        assertEquals(1, restored.currentIndex.value)
        assertEquals(listOf(0, 1), restored.snapshot().originalIndices)
    }

    @Test
    fun occurrenceMappingSurvivesDuplicateInsertionMoveRemovalAndRestoration() {
        val duplicate = song("same")
        val queue = MusicQueue()
        queue.setQueue(listOf(duplicate, duplicate, song("other")), startIndex = 1)
        queue.toggleShuffle()
        queue.playNext(listOf(duplicate, duplicate))
        queue.addToQueue(duplicate)
        queue.move(queue.items.value.lastIndex, 1)
        val removal = queue.removeAt(setOf(2, 3))!!
        queue.restore(removal)
        val snapshot = queue.snapshot()
        assertEquals(snapshot.displayedItems.indices.toSet(), snapshot.originalIndices.toSet())
        assertEquals(1, snapshot.originalIndices[snapshot.currentIndex])

        val restored =
            MusicQueue().apply {
                restore(Json.decodeFromString<MusicQueueSnapshot>(Json.encodeToString(snapshot)))
            }
        assertEquals(snapshot, restored.snapshot())
        restored.toggleShuffle()
        assertEquals(2, restored.currentIndex.value)
        assertEquals(listOf("same", "same", "same", "same", "same", "other"), restored.items.value.map { it.id })
    }

    @Test
    fun replacingShuffledUpcomingRetainsOnlyPlayedOccurrencesInCanonicalOrder() {
        val first = song("first")
        val duplicate = song("same")
        val last = song("last")
        val queue =
            MusicQueue().apply {
                restore(
                    MusicQueueSnapshot(
                        displayedItems = listOf(last, duplicate, first, duplicate),
                        originalItems = listOf(first, duplicate, duplicate, last),
                        currentIndex = 1,
                        isShuffled = true,
                        originalIndices = listOf(3, 2, 0, 1),
                    ),
                )
            }
        queue.replaceUpcomingQueue(listOf(duplicate, duplicate))
        val snapshot = queue.snapshot()
        assertEquals(listOf("last", "same", "same", "same"), snapshot.displayedItems.map { it.id })
        assertEquals(listOf(1, 0, 2, 3), snapshot.originalIndices)
        queue.toggleShuffle()
        assertEquals(1, queue.currentIndex.value)
        assertEquals(listOf("last", "same", "same", "same"), queue.items.value.map { it.id })
    }

    @Test
    fun malformedOccurrenceMappingUsesSafeLegacyOccurrenceOrder() {
        val duplicate = song("same")
        val queue =
            MusicQueue().apply {
                restore(
                    MusicQueueSnapshot(
                        displayedItems = listOf(duplicate, duplicate),
                        originalItems = listOf(duplicate, duplicate),
                        currentIndex = 1,
                        isShuffled = true,
                        originalIndices = listOf(9, 9),
                    ),
                )
            }
        queue.toggleShuffle()
        assertEquals(1, queue.currentIndex.value)
        assertEquals(listOf(0, 1), queue.snapshot().originalIndices)
    }

    private fun song(id: String) = SongItem(id, id, null, emptyList(), null)
}
