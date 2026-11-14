package com.metrolist.shared.settings

internal val LibraryGridColumnsRange = 2..5

internal class LibraryGridPinch(
    initialColumns: Int,
) {
    var columns = initialColumns.coerceIn(LibraryGridColumnsRange)
        private set

    private var zoom = 1f

    fun zoomBy(factor: Float): Int {
        if (!factor.isFinite() || factor <= 0f) return columns
        zoom *= factor
        while (zoom >= 1.25f) {
            if (columns == LibraryGridColumnsRange.first) {
                zoom = 1f
                break
            }
            columns--
            zoom /= 1.25f
        }
        while (zoom <= 0.8f) {
            if (columns == LibraryGridColumnsRange.last) {
                zoom = 1f
                break
            }
            columns++
            zoom /= 0.8f
        }
        return columns
    }
}
