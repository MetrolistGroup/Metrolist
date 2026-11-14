package com.metrolist.android.playback

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class MediaSessionTimelineTest {
    @Test
    fun `remote unmute restores last audible volume`() {
        val volume = RemoteVolumeTracker(0.4f)

        volume.observe(0f)
        assertEquals(0f, volume.volumeForMutedState(true))
        assertEquals(0.4f, volume.volumeForMutedState(false))
        volume.observe(0.7f)
        assertEquals(0.7f, volume.volumeForMutedState(false))
    }

    @Test
    fun `locked timeline is not seekable`() {
        val song =
            SongItem(
                id = "locked",
                title = "Locked",
                artists = emptyList(),
                album = null,
                duration = 10,
                thumbnail = null,
            )

        val timeline = mediaSessionTimeline(listOf(MusicQueueEntry(7, song)), 0, 10_000, isSeekable = false)

        assertFalse(timeline.getWindow(0, Timeline.Window()).isSeekable)
    }

    @Test
    fun `timeline exposes the whole queue and receiver duration for duration-less current item`() {
        val songs =
            listOf(
                SongItem(id = "first", title = "First", artists = emptyList(), album = null, duration = 10, thumbnail = null),
                SongItem(id = "second", title = "Second", artists = emptyList(), album = null, duration = null, thumbnail = null),
            )
        val timeline =
            mediaSessionTimeline(
                songs.mapIndexed { index, song -> MusicQueueEntry(index.toLong(), song) },
                currentIndex = 1,
                currentDurationMs = 21_000,
            )

        assertEquals(2, timeline.windowCount)
        assertEquals(2, timeline.periodCount)
        assertEquals(
            AutomotiveMediaIds.QueueSong(0, "first"),
            AutomotiveMediaIds.queueSong(timeline.getWindow(0, Timeline.Window()).mediaItem.mediaId),
        )
        assertEquals(21_000, timeline.getWindow(1, Timeline.Window()).durationMs)
        assertEquals(1, timeline.getPeriod(1, Timeline.Period(), true).windowIndex)
    }

    @Test
    fun `duplicate queue songs have stable occurrence media ids`() {
        val song = SongItem(id = "duplicate", title = "Duplicate", artists = emptyList(), album = null, thumbnail = null)
        val timeline =
            mediaSessionTimeline(
                listOf(MusicQueueEntry(4, song), MusicQueueEntry(9, song)),
                currentIndex = 0,
                currentDurationMs = 10_000,
            )

        val first = timeline.getWindow(0, Timeline.Window()).mediaItem.mediaId
        val second = timeline.getWindow(1, Timeline.Window()).mediaItem.mediaId

        assertNotEquals(first, second)
        assertEquals(AutomotiveMediaIds.QueueSong(4, song.id), AutomotiveMediaIds.queueSong(first))
        assertEquals(AutomotiveMediaIds.QueueSong(9, song.id), AutomotiveMediaIds.queueSong(second))
    }

    @Test
    fun `timeline cache invalidates every published property and preserves previous snapshots`() {
        val song = SongItem(id = "song", title = "Original", artists = emptyList(), album = null, thumbnail = null)
        val entries = listOf(MusicQueueEntry(4, song), MusicQueueEntry(9, song))
        val cache = MediaSessionTimelineCache()
        val original = cache.get(entries, 0, 10_000, true)

        assertSame(original, cache.get(entries.toList(), 0, 10_000, true))
        val moved = cache.get(entries, 1, 10_000, true)
        assertNotSame(original, moved)
        assertEquals(10_000, moved.getWindow(1, Timeline.Window()).durationMs)
        val resized = cache.get(entries, 1, 20_000, true)
        assertNotSame(moved, resized)
        assertEquals(20_000, resized.getWindow(1, Timeline.Window()).durationMs)
        val locked = cache.get(entries, 1, 20_000, false)
        assertNotSame(resized, locked)
        assertFalse(locked.getWindow(1, Timeline.Window()).isSeekable)
        val reordered = cache.get(entries.reversed(), 1, 20_000, false)
        assertNotSame(locked, reordered)
        assertNotEquals(locked.getUidOfPeriod(0), reordered.getUidOfPeriod(0))
        val updated = cache.get(entries.reversed().map { it.copy(song = song.copy(title = "Updated")) }, 1, 20_000, false)
        assertNotSame(reordered, updated)
        assertEquals(2, updated.windowCount)
        assertEquals(
            "Updated",
            updated
                .getWindow(0, Timeline.Window())
                .mediaItem.mediaMetadata.title,
        )
        assertEquals(
            "Original",
            original
                .getWindow(0, Timeline.Window())
                .mediaItem.mediaMetadata.title,
        )
        assertEquals(10_000, original.getWindow(0, Timeline.Window()).durationMs)
        assertTrue(original.getWindow(0, Timeline.Window()).isSeekable)
        assertEquals(0, cache.get(emptyList(), 0, 0, false).windowCount)
    }

    @Test
    fun `timeline cache retains and replaces the current song fallback without changing occurrence identity`() {
        val song = SongItem(id = "fallback", title = "Original", artists = emptyList(), album = null, thumbnail = null)
        val cache = MediaSessionTimelineCache()
        val original = cache.get(emptyList(), 0, 10_000, true, fallbackSong = song)
        assertSame(original, cache.get(emptyList(), 0, 10_000, true, fallbackSong = song))
        assertEquals(song.id, original.getWindow(0, Timeline.Window()).mediaItem.mediaId)
        val edited = cache.get(emptyList(), 0, 10_000, true, fallbackSong = song.copy(customTitle = "Edited"))
        assertEquals(
            "Edited",
            edited
                .getWindow(0, Timeline.Window())
                .mediaItem.mediaMetadata.title,
        )
        assertEquals(original.getUidOfPeriod(0), edited.getUidOfPeriod(0))
        val queued = cache.get(listOf(MusicQueueEntry(4, song)), 0, 10_000, true, fallbackSong = song.copy(title = "Ignored"))
        assertEquals(
            "Original",
            queued
                .getWindow(0, Timeline.Window())
                .mediaItem.mediaMetadata.title,
        )
        assertNotEquals(original.getUidOfPeriod(0), queued.getUidOfPeriod(0))
        assertEquals(0, cache.get(emptyList(), 0, 0, true).windowCount)
    }

    @Test
    fun `runtime duration publishes once for each consecutive duplicate occurrence`() {
        val stream = SongItem(id = "stream", title = "Stream", artists = emptyList(), album = null, thumbnail = null)
        val next = stream.copy(id = "next", duration = 30)
        val tracker = TimelineDurationPublicationTracker()

        assertFalse(tracker.shouldPublish(stream, playbackRequestId = 1, durationMs = 0))
        assertTrue(tracker.shouldPublish(stream, playbackRequestId = 1, durationMs = 1_000))
        assertFalse(tracker.shouldPublish(stream, playbackRequestId = 1, durationMs = 2_000))
        assertFalse(tracker.shouldPublish(stream, playbackRequestId = 2, durationMs = 0))
        assertTrue(tracker.shouldPublish(stream, playbackRequestId = 2, durationMs = 2_000))
        assertFalse(tracker.shouldPublish(stream, playbackRequestId = 2, durationMs = 3_000))
        assertFalse(tracker.shouldPublish(next, playbackRequestId = 3, durationMs = 30_000))
        assertTrue(tracker.shouldPublish(next, playbackRequestId = 3, durationMs = 29_000))
        assertFalse(tracker.shouldPublish(next, playbackRequestId = 3, durationMs = 28_000))
        assertTrue(tracker.shouldPublish(stream, playbackRequestId = 4, durationMs = 3_000))
    }
}
