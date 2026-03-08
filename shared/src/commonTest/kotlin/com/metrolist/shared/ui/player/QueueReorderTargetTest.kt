package com.metrolist.shared.ui.player

import com.metrolist.shared.model.moveSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class QueueReorderTargetTest {
    @Test
    fun draggingFirstSelectedOccurrenceSkipsItsCompanionAndMovesBothPastTheNextSong() {
        val tracks = tracks()
        val selected = setOf(1L, 2L)
        val target = tracks.drop(2).first { it.isReorderTarget(1L, selected) }
        assertEquals(3L, target.entryKey)

        val reordered = tracks.moveSelection(1, tracks.indexOf(target), setOf(1, 2))
        assertEquals(listOf(0L, 3L, 1L, 2L, 4L), reordered.map { it.entryKey })
        assertFalse(reordered[3].isReorderTarget(1L, selected))

        val reverseTarget = reordered.first { it.isReorderTarget(1L, selected) }
        val restored = reordered.moveSelection(2, reordered.indexOf(reverseTarget), setOf(2, 3))
        assertEquals(tracks, restored)
    }

    @Test
    fun everyEligibleTargetChangesTheOrderWithoutReorderingSelectedOccurrences() {
        val tracks = tracks()
        for (mask in 0 until (1 shl tracks.size)) {
            val selected = tracks.filter { mask and (1 shl it.queueIndex) != 0 }.mapTo(mutableSetOf()) { it.entryKey }
            val selectedIndices = tracks.filter { it.canRemove && it.entryKey in selected }.mapTo(mutableSetOf()) { it.queueIndex }
            for (dragged in tracks.filter { it.canRemove }) {
                for (target in tracks.filter { it.isReorderTarget(dragged.entryKey, selected) }) {
                    val reordered = tracks.moveSelection(dragged.queueIndex, target.queueIndex, selectedIndices)
                    assertNotEquals(tracks, reordered)
                    assertEquals(tracks.first(), reordered.first())
                    if (dragged.entryKey in selected) {
                        assertEquals(tracks.filter { it.entryKey in selected }, reordered.filter { it.entryKey in selected })
                    }
                }
            }
        }
    }

    @Test
    fun unselectedDragCanCrossSelectedRowsAndStoppingRestoresTargets() {
        val tracks = tracks()
        val selected = setOf(1L, 2L)
        assertEquals(listOf(1L, 2L, 4L), tracks.filter { it.isReorderTarget(3L, selected) }.map { it.entryKey })
        assertEquals(listOf(1L, 2L, 3L, 4L), tracks.filter { it.isReorderTarget(null, selected) }.map { it.entryKey })
    }

    private fun tracks() =
        List(5) { index ->
            QueueTrack(
                id = "duplicate",
                title = "Song",
                artist = "Artist",
                thumbnailUrl = null,
                duration = null,
                isExplicit = false,
                isLiked = false,
                isSaved = false,
                canRemove = index > 0,
                queueIndex = index,
                entryKey = index.toLong(),
                uniqueKey = index.toString(),
            )
        }
}
