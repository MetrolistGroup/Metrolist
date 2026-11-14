package com.metrolist.shared.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class ArtworkPrefetchWindowTest {
    @Test
    fun overlappingRequestsSurviveWindowAndPresentationChanges() {
        val started = mutableListOf<String>()
        val disposed = mutableListOf<String>()
        val window =
            ArtworkPrefetchWindow(enqueue = { url: String ->
                started += url
                url
            }, dispose = { disposed += it })
        window.update(listOf("a", "b", "c", "c"))
        window.update(listOf("b", "c", "d"))
        assertEquals(listOf("a", "b", "c", "d"), started)
        assertEquals(listOf("a"), disposed)
        window.update(listOf("c", "b"))
        assertEquals(listOf("a", "d"), disposed)
        window.clear()
        assertEquals(listOf("a", "d", "b", "c"), disposed)
        window.clear()
        assertEquals(4, disposed.size)
    }
}
