package com.metrolist.shared.settings

import com.metrolist.shared.model.YTItem

fun orderLibraryItems(
    items: List<YTItem>,
    storedOrder: String,
): List<YTItem> {
    val positions =
        normalizedLibraryOrder(items.map { HiddenLibraryItem.from(it).key }, storedOrder).withIndex().associate {
            it.value to
                it.index
        }
    return items.withIndex().sortedBy { positions[HiddenLibraryItem.from(it.value).key] ?: Int.MAX_VALUE }.map { it.value }
}

fun moveLibraryItems(
    items: List<YTItem>,
    visibleItems: List<YTItem>,
    storedOrder: String,
    from: HiddenLibraryItemKey,
    to: HiddenLibraryItemKey,
): String =
    moveLibraryOrder(
        items.map { HiddenLibraryItem.from(it).key },
        visibleItems.map { HiddenLibraryItem.from(it).key }.toSet(),
        storedOrder,
        from,
        to,
    )

internal fun moveLibraryOrder(
    available: List<HiddenLibraryItemKey>,
    visible: Set<HiddenLibraryItemKey>,
    storedOrder: String,
    from: HiddenLibraryItemKey,
    to: HiddenLibraryItemKey,
): String {
    val order = normalizedLibraryOrder(available, storedOrder)
    val moved = order.filter { it in visible }.toMutableList()
    val fromIndex = moved.indexOf(from)
    val toIndex = moved.indexOf(to)
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return storedOrder
    moved.add(toIndex, moved.removeAt(fromIndex))
    val reordered = moved.iterator()
    return encodeLibraryOrder(order.map { if (it in visible) reordered.next() else it })
}

internal fun normalizedLibraryOrder(
    available: List<HiddenLibraryItemKey>,
    storedOrder: String,
): List<HiddenLibraryItemKey> {
    val availableKeys = available.toSet()
    val stored = decodeLibraryOrder(storedOrder).filterTo(mutableListOf()) { it in availableKeys }
    return (stored + available.filterNot(stored.toSet()::contains)).distinct()
}

private fun encodeLibraryOrder(keys: List<HiddenLibraryItemKey>): String = keys.joinToString("\n") { "${it.type.name}:${it.id}" }

private fun decodeLibraryOrder(value: String): List<HiddenLibraryItemKey> =
    value
        .lineSequence()
        .mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@mapNotNull null
            val type = runCatching { HiddenLibraryItemType.valueOf(line.substring(0, separator)) }.getOrNull()
            type?.let { HiddenLibraryItemKey(it, line.substring(separator + 1)) }
        }.toList()
