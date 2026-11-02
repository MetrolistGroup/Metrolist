package com.metrolist.music.viewmodels

import com.metrolist.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSpeedDialTest {
    @Test
    fun `home recommendations fill sparse speed dial without replacing pinned items`() {
        val pinned = song("pinned")
        val home = song("home")

        val result =
            buildSpeedDialItems(
                pinned = listOf(pinned),
                keepListening = emptyList(),
                quickPicks = emptyList(),
                home = listOf(pinned.copy(title = "duplicate"), home),
            )

        assertEquals(listOf(pinned, home), result)
    }

    @Test
    fun `hiding videos fills speed dial with audio and disabling restores videos`() {
        val video = song("video").copy(musicVideoType = "MUSIC_VIDEO_TYPE_OMV")
        val audio = (1..27).map { song("audio$it") }

        assertEquals(
            audio,
            buildSpeedDialItems(
                pinned = listOf(video),
                keepListening = emptyList(),
                quickPicks = emptyList(),
                home = audio,
                hideVideoSongs = true,
            ),
        )
        assertEquals(
            listOf(video) + audio.take(26),
            buildSpeedDialItems(
                pinned = listOf(video),
                keepListening = emptyList(),
                quickPicks = emptyList(),
                home = audio,
                hideVideoSongs = false,
            ),
        )
    }

    @Test
    fun `unclassified pins stay hidden until metadata is available`() {
        val pin = song("pin")
        val audio = song("audio").copy(musicVideoType = "MUSIC_VIDEO_TYPE_ATV")

        assertEquals(
            listOf(audio),
            buildSpeedDialItems(
                pinned = listOf(pin, audio),
                keepListening = emptyList(),
                quickPicks = emptyList(),
                home = emptyList(),
                hideVideoSongs = true,
            ),
        )
        assertEquals(
            listOf(pin, audio),
            buildSpeedDialItems(
                pinned = listOf(pin, audio),
                keepListening = emptyList(),
                quickPicks = emptyList(),
                home = emptyList(),
                hideVideoSongs = false,
            ),
        )
    }

    @Test
    fun `a duplicate without metadata cannot reintroduce a known video`() {
        val video = song("video").copy(musicVideoType = "MUSIC_VIDEO_TYPE_OMV")
        val audio = song("audio")

        assertEquals(
            listOf(audio),
            buildSpeedDialItems(
                pinned = listOf(video),
                keepListening = emptyList(),
                quickPicks = emptyList(),
                home = listOf(video.copy(musicVideoType = null), audio),
                hideVideoSongs = true,
            ),
        )
    }

    private fun song(id: String) =
        SongItem(
            id = id,
            title = id,
            artists = emptyList(),
            thumbnail = "",
        )
}
