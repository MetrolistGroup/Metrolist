package com.metrolist.android.playback

import com.metrolist.android.ui.components.canvasPlaybackSources
import com.metrolist.shared.api.artwork.YouTubeCanvasClip
import com.metrolist.shared.viewmodels.PlayerViewModel.MusicVideoCanvas
import com.metrolist.shared.youtube.playback.PlaybackResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasPlaybackSourceTest {
    private val clips = listOf(YouTubeCanvasClip(20_000, 23_000), YouTubeCanvasClip(40_000, 43_000))
    private val resource = PlaybackResource("song", "audio", emptyMap(), null, null, videoUrl = "https://example.com/video")

    @Test
    fun preparedClipsKeepOriginalMetadataForEvictionRecoveryWithoutKeepingBytes() {
        val canvas = MusicVideoCanvas(resource, clips).copy(cacheKey = "prepared", cachedUrl = "canvas-cache:///prepared/hash")
        val (cached, recovery) = canvasPlaybackSources(canvas.url, canvas.resource, canvas.cacheKey, canvas.clips, cachedAvailable = true)
        assertEquals("canvas-cache:///prepared/hash", cached.url)
        assertEquals(emptyList<YouTubeCanvasClip>(), cached.clips)
        assertEquals(resource.videoUrl, recovery?.url)
        assertEquals(clips, recovery?.clips)
        assertNull(recovery?.cacheKey)
        assertNull(canvas.videoBytes)
        assertEquals(resource, canvas.resource)
    }

    @Test
    fun evictionBeforePlaybackStartsDirectlyFromOriginalClips() {
        val (source, recovery) = canvasPlaybackSources("canvas-cache:///evicted", resource, "prepared", clips, cachedAvailable = false)
        assertEquals(resource.videoUrl, source.url)
        assertEquals(clips, source.clips)
        assertNull(source.cacheKey)
        assertNull(recovery)
    }

    @Test
    fun streamingClipsKeepTheirTimelineAndCacheAndHaveNoRecursiveRecovery() {
        val (source, recovery) = canvasPlaybackSources(resource.videoUrl!!, resource, "stream", clips, cachedAvailable = false)
        assertEquals(clips, source.clips)
        assertEquals("stream", source.cacheKey)
        assertNull(recovery)
    }

    @Test
    fun streamingStartupKeepsClipOffsetsWithoutWritingToThePreparedCacheEntry() {
        val (source, recovery) =
            canvasPlaybackSources(
                resource.videoUrl!!,
                resource,
                "prepared",
                clips,
                cachedAvailable = false,
                preparingClips = true,
            )
        assertEquals(resource.videoUrl, source.url)
        assertEquals(clips, source.clips)
        assertNull(source.cacheKey)
        assertNull(recovery)
    }

    @Test
    fun temporaryClipsPlayFromTheirOwnTimelineAndCanRecoverFromTheOriginalStream() {
        val (source, recovery) =
            canvasPlaybackSources("file:///cache/canvas-clips.media", resource, "stream", clips, cachedAvailable = true)
        assertEquals("file:///cache/canvas-clips.media", source.url)
        assertEquals(emptyList<YouTubeCanvasClip>(), source.clips)
        assertNull(source.cacheKey)
        assertEquals(resource.videoUrl, recovery?.url)
        assertEquals(clips, recovery?.clips)
        assertNull(recovery?.cacheKey)
    }

    @Test
    fun legacyCacheOnlyClipsHaveNoInventedUpstream() {
        val cachedUrl = "canvas-cache:///old/hash"
        val (source, recovery) =
            canvasPlaybackSources(
                cachedUrl,
                resource.copy(videoUrl = cachedUrl),
                "old",
                emptyList(),
                cachedAvailable = false,
            )
        assertEquals(cachedUrl, source.url)
        assertNull(recovery)
    }
}
