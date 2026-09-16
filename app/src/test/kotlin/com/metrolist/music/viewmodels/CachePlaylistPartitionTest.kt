package com.metrolist.music.viewmodels

import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class CachePlaylistPartitionTest {
    private fun song(
        id: String,
        contentLength: Long? = 1_000L,
        isDownloaded: Boolean = false,
    ) = Song(
        song = SongEntity(id = id, title = "title-$id", isDownloaded = isDownloaded),
        artists = emptyList(),
        format = contentLength?.let {
            @Suppress("DEPRECATION")
            FormatEntity(
                id = id,
                itag = 251,
                mimeType = "audio/webm",
                codecs = "opus",
                bitrate = 128_000,
                sampleRate = 48_000,
                contentLength = it,
                loudnessDb = null,
                playbackUrl = null,
            )
        },
    )

    @Test
    fun `song whose bytes are still present stays cached`() {
        val result = partitionCachedSongs(listOf(song("a"))) { _, _ -> true }

        assertEquals(listOf("a"), result.stillCached.map { it.id })
        assertEquals(emptyList<String>(), result.stale.map { it.id })
    }

    @Test
    fun `song whose bytes are gone is reported as stale`() {
        val result = partitionCachedSongs(listOf(song("a"))) { _, _ -> false }

        assertEquals(emptyList<String>(), result.stillCached.map { it.id })
        assertEquals(listOf("a"), result.stale.map { it.id })
    }

    @Test
    fun `downloaded song is excluded without clearing its download date`() {
        var lookups = 0
        val result = partitionCachedSongs(listOf(song("a", isDownloaded = true))) { _, _ ->
            lookups++
            true
        }

        assertEquals(emptyList<String>(), result.stillCached.map { it.id })
        assertEquals(emptyList<String>(), result.stale.map { it.id })
        assertEquals(0, lookups)
    }

    @Test
    fun `cache metadata length is used when format is missing`() {
        val seen = mutableListOf<Pair<String, Long>>()
        val result =
            partitionCachedSongs(
                flagged = listOf(song("a", contentLength = null)),
                resolveContentLength = { 2_000L },
            ) { id, length ->
                seen += id to length
                true
            }

        assertEquals(listOf("a"), result.stillCached.map { it.id })
        assertEquals(listOf("a" to 2_000L), seen)
    }

    @Test
    fun `song with unknown content length is stale and is never looked up`() {
        var lookups = 0
        val result = partitionCachedSongs(listOf(song("a", contentLength = null))) { _, _ ->
            lookups++
            true
        }

        assertEquals(emptyList<String>(), result.stillCached.map { it.id })
        assertEquals(listOf("a"), result.stale.map { it.id })
        assertEquals(0, lookups)
    }

    @Test
    fun `each song is checked with its own id and content length`() {
        val seen = mutableListOf<Pair<String, Long>>()
        val songs = listOf(song("a", contentLength = 10L), song("b", contentLength = 20L))

        partitionCachedSongs(songs) { id, length ->
            seen += id to length
            true
        }

        assertEquals(listOf("a" to 10L, "b" to 20L), seen)
    }

    @Test
    fun `mixed input is split preserving input order in both groups`() {
        val songs = listOf(song("keep1"), song("drop1"), song("keep2"), song("drop2"))

        val result = partitionCachedSongs(songs) { id, _ -> id.startsWith("keep") }

        assertEquals(listOf("keep1", "keep2"), result.stillCached.map { it.id })
        assertEquals(listOf("drop1", "drop2"), result.stale.map { it.id })
    }

    @Test
    fun `empty input produces empty groups`() {
        val result = partitionCachedSongs(emptyList()) { _, _ -> true }

        assertEquals(emptyList<String>(), result.stillCached.map { it.id })
        assertEquals(emptyList<String>(), result.stale.map { it.id })
    }
}
