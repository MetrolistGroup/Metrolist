package com.metrolist.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SelectionReorderTest {
    @Test
    fun contiguousAndSeparatedSelectionsMoveInOrderWhileUnselectedDragsStaySingle() {
        val source = (0..5).toList()
        assertEquals(listOf(0, 3, 4, 1, 2, 5), source.moveSelection(1, 4, setOf(1, 2)))
        assertEquals(listOf(0, 2, 4, 5, 1, 3), source.moveSelection(1, 5, setOf(1, 3)))
        assertEquals(listOf(2, 4, 0, 1, 3, 5), source.moveSelection(4, 0, setOf(2, 4)))
        assertEquals(listOf(1, 2, 3, 0, 4, 5), source.moveSelection(0, 3, setOf(1, 2)))
        assertEquals(source, source.moveSelection(1, 3, setOf(1, 3)))
        assertEquals(source, source.moveSelection(-1, 3, setOf(1)))
        assertEquals(source, source.moveSelection(1, 8, setOf(1)))
    }

    @Test
    fun savePlanReproducesEverySelectionAndDragDirectionIncludingReversals() {
        val source = (0..5).toList()
        for (mask in 0 until (1 shl source.size)) {
            val selected = source.filterTo(mutableSetOf()) { mask and (1 shl it) != 0 }
            for (from in source) {
                val moving = if (from in selected) selected else setOf(from)
                var preview = source
                for (target in source + source.reversed()) {
                    val indices = preview.indices.filterTo(mutableSetOf()) { preview[it] in selected }
                    preview = preview.moveSelection(preview.indexOf(from), preview.indexOf(target), indices)
                    val persisted = source.toMutableList()
                    for ((start, end) in selectionReorderMoves(source, preview, moving)) {
                        persisted.add(end, persisted.removeAt(start))
                    }
                    assertEquals(preview, persisted)
                    assertEquals(source.filter { it in moving }, preview.filter { it in moving })
                }
            }
        }
    }
}
