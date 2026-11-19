package com.metrolist.shared.ui.player

import com.metrolist.shared.viewmodels.PlayerViewModel.QueueItemUiModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class QueueDurationTest {
    @Test
    fun durationUpdatesPreservePendingReorder() {
        val first = track("first", null)
        val second = track("second", null)
        val incoming = listOf(track("first", 60_000L), track("second", 120_000L))

        val reconciled = reconcileReorderedTracks(listOf(second, first), incoming, hasPendingReorder = true)

        assertEquals(listOf("second", "first"), reconciled.map { it.id })
        assertEquals(listOf(120_000L, 60_000L), reconciled.map { it.duration })
    }

    @Test
    fun duplicateOccurrencesKeepKeysAndRefreshMetadataDuringPendingReorder() {
        val item = QueueItemUiModel("repeat", "Title", "Artist", null, null)
        val keys = listOf(11L, 12L, -1L)
        val original = prepareQueueTracks(listOf(item, item, item.copy(isSuggested = true)), keys, emptySet())
        val incoming =
            prepareQueueTracks(
                listOf(item.copy(isLiked = true), item.copy(title = "Edited"), item.copy(isSuggested = true)),
                keys,
                setOf(item.id),
            )
        val reconciled = reconcileReorderedTracks(listOf(original[1], original[0], original[2]), incoming, hasPendingReorder = true)

        assertEquals(listOf("12", "11", "-1"), reconciled.map { it.uniqueKey })
        assertEquals(listOf(0, 1, 2), reconciled.map { it.queueIndex })
        assertEquals(listOf("Edited", "Title", "Title"), reconciled.map { it.title })
        assertEquals(listOf(false, true, false), reconciled.map { it.isLiked })
        assertEquals(listOf(true, true, true), reconciled.map { it.isDownloaded })
        assertEquals(setOf(11L, 12L), reconciled.filter { !it.isSuggested && it.canRemove }.mapTo(mutableSetOf()) { it.entryKey })
    }

    @Test
    fun replacedOccurrenceDiscardsPendingOrderEvenForTheSameSong() {
        val item = QueueItemUiModel("repeat", "Title", "Artist", null, null)
        val original = prepareQueueTracks(listOf(item, item), listOf(11L, 12L), emptySet())
        val incoming = prepareQueueTracks(listOf(item, item), listOf(11L, 13L), emptySet())

        assertSame(incoming, reconcileReorderedTracks(original.reversed(), incoming, hasPendingReorder = true))
        assertSame(incoming, reconcileReorderedTracks(original, incoming, hasPendingReorder = false))
        assertSame(incoming, reconcileReorderedTracks(original.take(1), incoming, hasPendingReorder = true))
    }

    @Test
    fun activeTrackUsesPlaybackDurationWhenCatalogDurationIsUnknown() {
        for (catalogDuration in listOf(null, 0L, -1L)) {
            val track = track("unknown", catalogDuration)
            assertEquals("2:03", track.durationText(isActive = true, currentDuration = 123_000L))
            assertEquals("--:--", track.durationText(isActive = true, currentDuration = 0L))
            assertEquals("--:--", track.durationText(isActive = false, currentDuration = 123_000L))
        }
    }

    @Test
    fun knownCatalogDurationRemainsStable() {
        val known = track("known", 60_000L)
        assertEquals("1:00", known.durationText(isActive = true, currentDuration = 123_000L))
        assertEquals("1:00", known.durationText(isActive = false, currentDuration = 123_000L))
    }

    @Test
    fun queueTotalRemainsUnknownWhenAnInactiveTrackHasAnUnknownDuration() {
        val known = QueueItemUiModel("known", "Known", "Artist", null, 90_000L)
        for (unknownDuration in listOf(null, 0L, -1L)) {
            val unknown = known.copy(id = "unknown", duration = unknownDuration)
            assertNull(queueTotalDuration(listOf(unknown, known), listOf(2L, 3L), 1, 90_000L))
        }
    }

    @Test
    fun queueTotalUsesMeasuredDurationForActiveTrackAndExcludesSyntheticEntries() {
        val active = QueueItemUiModel("active", "Active", "Artist", null, 0L)
        val known = active.copy(id = "known", duration = 90_000L)
        val synthetic = active.copy(id = "synthetic", duration = 10_000L, isSuggested = true)
        val entries = listOf(active, known, synthetic)
        val keys = listOf(2L, 3L, -1L)
        assertEquals(213_000L, queueTotalDuration(entries, keys, 0, 123_000L))
        assertNull(queueTotalDuration(entries, keys, 0, 0L))
    }

    private fun track(
        id: String,
        duration: Long?,
    ) = QueueTrack(
        id = id,
        title = id,
        artist = "artist",
        thumbnailUrl = null,
        duration = duration,
        isExplicit = false,
        isLiked = false,
        isSaved = false,
    )
}
