package com.metrolist.android.listentogether

import com.metrolist.shared.listentogether.network.TrackInfo
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class AndroidListenTogetherTrackResolverTest {
    @Test
    fun resolvesOnlyTheRequestedTrackAndFallsBackOnlyWhenMetadataIsMissing() =
        runTest {
            val track = TrackInfo("song", "Room title", "", duration = 0)
            val song = SongItem("song", "Metadata title", null, emptyList(), null)
            var metadata: SongItem? = song
            var fallbacks = 0
            val resolver =
                androidListenTogetherTrackResolver(
                    metadata = {
                        assertEquals(track.id, it)
                        metadata
                    },
                    fallbackArtist = {
                        fallbacks++
                        "Unknown artist"
                    },
                )
            assertSame(song, resolver.resolve(track))
            assertEquals(0, fallbacks)
            metadata = null
            val fallback = resolver.resolve(track)!!
            assertEquals(track.title, fallback.title)
            assertNull(fallback.duration)
            assertEquals(1, fallbacks)
            val cancellation = CancellationException("cancelled")
            val cancelled =
                androidListenTogetherTrackResolver(
                    metadata = { throw cancellation },
                    fallbackArtist = {
                        fail("Cancelled resolutions must not fall back")
                        ""
                    },
                )
            try {
                cancelled.resolve(track)
                fail("Cancellation must propagate")
            } catch (e: CancellationException) {
                assertSame(cancellation, e)
            }
        }
}
