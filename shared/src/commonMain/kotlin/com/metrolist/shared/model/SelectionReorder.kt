package com.metrolist.shared.model

fun <T> List<T>.moveSelection(
    from: Int,
    to: Int,
    selectedIndices: Set<Int>,
): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    val moving = if (from in selectedIndices) selectedIndices.intersect(indices.toSet()) else setOf(from)
    if (to in moving) return this
    val remaining = indices.filterNot { it in moving }.toMutableList()
    val destination = remaining.indexOf(to) + if (from < to) 1 else 0
    remaining.addAll(destination, indices.filter { it in moving })
    return remaining.map(::get)
}

fun <T> selectionReorderMoves(
    original: List<T>,
    reordered: List<T>,
    moving: Set<T>,
): List<Pair<Int, Int>> {
    val current = original.toMutableList()
    val moves = mutableListOf<Pair<Int, Int>>()
    for (index in reordered.indices.reversed()) {
        val item = reordered[index]
        if (item !in moving) continue
        val from = current.indexOf(item)
        if (from < 0) return emptyList()
        current.removeAt(from)
        val to = reordered.getOrNull(index + 1)?.let(current::indexOf) ?: current.size
        if (to < 0) return emptyList()
        current.add(to, item)
        if (from != to) moves += from to to
    }
    return moves
}
