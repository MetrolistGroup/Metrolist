package com.metrolist.shared.ui.tv

internal data class TvReorderScrollTarget(
    val key: String,
    val previousIndex: Int,
) {
    fun listIndex(
        keys: List<String>,
        leadingItems: Int,
    ): Int? {
        val index = keys.indexOf(key)
        return index.takeIf { it >= 0 && it != previousIndex }?.plus(leadingItems)
    }
}
