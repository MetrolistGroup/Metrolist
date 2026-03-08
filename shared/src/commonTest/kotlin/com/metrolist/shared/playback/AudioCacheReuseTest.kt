package com.metrolist.shared.playback

import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioCacheReuseTest {
    @Test
    fun playbackTouchesDoNotInvalidateAnUnchangedFileButRemovalAndReplacementDo() {
        val entry =
            CachedAudioEntity(
                "song",
                "Track",
                "Artist",
                filePath = "/cache/song",
                fileSize = 2048,
                cachedAt = 1,
                lastAccessedAt = 1,
                cacheLocation = "INTERNAL",
            )
        assertTrue(hasSameStoredAudio(entry, entry.copy(lastAccessedAt = 100, title = "Updated title")))
        assertFalse(hasSameStoredAudio(entry, null))
        assertFalse(hasSameStoredAudio(null, entry))
        assertFalse(hasSameStoredAudio(entry, entry.copy(filePath = "/cache/replacement")))
        assertFalse(hasSameStoredAudio(entry, entry.copy(kind = KIND_DOWNLOAD)))
        assertFalse(hasSameStoredAudio(entry, entry.copy(cacheLocation = "QUARANTINE")))
    }

    @Test
    fun onlyTheRequestedCompleteRepresentationCanBePromoted() {
        val resource =
            PlaybackResource(
                "song",
                "https://media.example.test/audio",
                emptyMap(),
                null,
                null,
                contentLengthBytes = 4096,
                itag = 140,
                mimeType = "audio/mp4",
                codecs = "mp4a.40.2",
            )
        assertTrue(canReuseCachedAudio(resource, 140, "audio/mp4", "mp4a.40.2", 4096))
        assertFalse(canReuseCachedAudio(resource, 141, "audio/mp4", "mp4a.40.2", 4096))
        assertFalse(canReuseCachedAudio(resource, 140, "audio/webm", "mp4a.40.2", 4096))
        assertFalse(canReuseCachedAudio(resource, 140, "audio/mp4", "opus", 4096))
        assertFalse(canReuseCachedAudio(resource, 140, "audio/mp4", "mp4a.40.2", 2048))
        assertFalse(canReuseCachedAudio(resource.copy(itag = null), null, "audio/mp4", "mp4a.40.2", 4096))
        assertFalse(canReuseCachedAudio(resource.copy(contentLengthBytes = null), 140, "audio/mp4", "mp4a.40.2", 4096))
    }
}
