package com.metrolist.shared.listentogether

import com.metrolist.shared.listentogether.network.TrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class ListenTogetherQueueReconciliationTest {
    private fun tracks(vararg ids: String) = ids.map { TrackInfo(it, it, "Artist", duration = 180_000L) }

    @Test
    fun canonicalSuggestionsPreserveUnpublishedHostAdditionsRemovalsAndOrder() {
        val published = tracks("first", "second", "removed")
        val local = tracks("second", "first", "added")
        val canonical = tracks("suggested", "first", "second", "removed")
        assertEquals(tracks("suggested", "second", "first", "added"), reconcileHostQueue(published, local, canonical))
    }

    @Test
    fun serverRemovalsAndReorderingApplyAlongsideLocalAdditions() {
        val published = tracks("first", "second", "removed")
        val local = published + tracks("added")
        val canonical = tracks("second", "first")
        assertEquals(tracks("second", "first", "added"), reconcileHostQueue(published, local, canonical))
        assertEquals(tracks("added"), reconcileHostQueue(published, local, emptyList()))
    }

    @Test
    fun duplicateOccurrencesAndMetadataSurviveReconciliation() {
        val published = tracks("same", "same", "other")
        val local = tracks("same", "other", "same", "added")
        val canonical = tracks("suggested", "same", "same", "other").map { it.copy(title = "Updated ${it.id}") }
        assertEquals(
            listOf("suggested", "same", "other", "same", "added"),
            reconcileHostQueue(published, local, canonical).map { it.id },
        )
        assertEquals("Updated same", reconcileHostQueue(published, local, canonical)[1].title)
        assertEquals(
            tracks("suggested", "same", "added"),
            reconcileHostQueue(tracks("same", "same"), tracks("same", "added"), tracks("suggested", "same", "same")),
        )
    }
}
