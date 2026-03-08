package com.metrolist.android.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OfflinePlaybackPolicyTest {
    @Test
    fun cachedAudioIsUsedWithoutResolvingTheNetwork() =
        runBlocking {
            var videoLoaderCalled = false

            assertEquals("cached", cachedAudioResource(wantVideo = false) { "cached" })
            assertNull(cachedAudioResource(wantVideo = true) { videoLoaderCalled = true })
            assertFalse(videoLoaderCalled)
            assertEquals(
                "cached-after-video-failure",
                cachedAudioResource(wantVideo = true, videoResolutionFailed = true) { "cached-after-video-failure" },
            )
        }
}
