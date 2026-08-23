package com.metrolist.music.playback

import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DownloadRequestsTest {
    private data class TestSong(val id: String, val title: String)

    private fun download(id: String, state: Int): Download =
        Download(
            DownloadRequest.Builder(id, id.toUri()).build(),
            state,
            /* startTimeMs = */ 0L,
            /* updateTimeMs = */ 0L,
            /* contentLength = */ 0L,
            /* stopReason = */ Download.STOP_REASON_NONE,
            // media3 requires failureReason to be set if and only if state is STATE_FAILED
            /* failureReason = */
            if (state == Download.STATE_FAILED) {
                Download.FAILURE_REASON_UNKNOWN
            } else {
                Download.FAILURE_REASON_NONE
            },
        )

    private fun requestsFor(
        downloads: Map<String, Download>,
        songs: List<TestSong>,
    ) = buildPendingDownloadRequests(downloads, songs, { it.id }, { it.title })

    @Test
    fun `mixed selection only enqueues songs that are not completed`() {
        val songs = listOf(
            TestSong("a", "A"),
            TestSong("b", "B"),
            TestSong("c", "C"),
            TestSong("d", "D"),
        )
        val downloads = mapOf(
            "a" to download("a", Download.STATE_COMPLETED),
            "b" to download("b", Download.STATE_FAILED),
            "c" to download("c", Download.STATE_COMPLETED),
            // "d" has never been downloaded and is absent from the map
        )

        val requests = requestsFor(downloads, songs)

        assertEquals(2, requests.size)
        assertEquals(listOf("b", "d"), requests.map { it.id })
    }

    @Test
    fun `fully downloaded selection enqueues nothing`() {
        val songs = listOf(TestSong("a", "A"), TestSong("b", "B"))
        val downloads = mapOf(
            "a" to download("a", Download.STATE_COMPLETED),
            "b" to download("b", Download.STATE_COMPLETED),
        )

        assertEquals(0, requestsFor(downloads, songs).size)
    }

    @Test
    fun `selection with no downloads enqueues every song`() {
        val songs = listOf(TestSong("a", "A"), TestSong("b", "B"), TestSong("c", "C"))

        val requests = requestsFor(emptyMap(), songs)

        assertEquals(3, requests.size)
        assertEquals(listOf("a", "b", "c"), requests.map { it.id })
    }

    @Test
    fun `in-flight states are re-enqueued so retries still work`() {
        val songs = listOf(TestSong("a", "A"), TestSong("b", "B"), TestSong("c", "C"))
        val downloads = mapOf(
            "a" to download("a", Download.STATE_QUEUED),
            "b" to download("b", Download.STATE_DOWNLOADING),
            "c" to download("c", Download.STATE_STOPPED),
        )

        assertEquals(3, requestsFor(downloads, songs).size)
    }

    @Test
    fun `request carries the custom cache key and title payload`() {
        val requests = requestsFor(emptyMap(), listOf(TestSong("a", "Song A")))

        assertEquals(1, requests.size)
        assertEquals("a", requests[0].id)
        assertEquals("a", requests[0].customCacheKey)
        assertEquals("Song A", String(requests[0].data))
    }
}
