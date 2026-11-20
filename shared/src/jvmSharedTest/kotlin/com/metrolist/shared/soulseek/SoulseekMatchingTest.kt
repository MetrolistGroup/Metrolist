package com.metrolist.shared.soulseek

import kotlin.test.Test
import kotlin.test.assertEquals

class SoulseekMatchingTest {
    private fun file(
        name: String,
        vararg attributes: Pair<Int, Int>,
    ) = SoulseekFileResult(name, 1, name.substringAfterLast('.'), attributes.toMap())

    private fun reply(
        vararg files: SoulseekFileResult,
        free: Boolean = true,
        queue: Long = 0,
    ) = SoulseekSearchReply("peer", 1, files.toList(), free, 100, queue)

    private val d = Codes.ATTR_DURATION
    private val flac = file("Music\\Daft Punk\\Discovery\\03 Digital Love.flac", d to 301)
    private val hires = file("Music\\Daft Punk - Discovery (24-96)\\03 Digital Love.flac", d to 301, Codes.ATTR_BIT_DEPTH to 24)
    private val mp3 = file("mp3\\Daft Punk\\03 - Digital Love.mp3", Codes.ATTR_BITRATE to 320, d to 300)
    private val v0 = file("mp3\\Daft Punk\\Digital Love.mp3", Codes.ATTR_BITRATE to 250, Codes.ATTR_VBR to 1, d to 301)
    private val low = file("Daft Punk\\Digital Love.mp3", Codes.ATTR_BITRATE to 128, d to 301)
    private val remix = file("Daft Punk\\Digital Love (Remix).flac", d to 301)
    private val wrongLength = file("Daft Punk\\Digital Love.flac", d to 420)
    private val wrongArtist = file("Someone Else\\Digital Love.flac", d to 301)

    private fun names(
        replies: List<SoulseekSearchReply>,
        title: String = "Digital Love",
    ) = SoulseekMatching.options(replies, "Daft Punk", title, 301).map { it.filename }

    @Test
    fun picksMatchingFilesIn320LosslessHiresOrder() {
        val all = reply(low, mp3, remix, wrongLength, flac, wrongArtist, v0, hires)
        assertEquals(listOf(mp3, v0, flac, hires).map { it.filename }, names(listOf(all)))
        assertEquals(
            listOf(
                "320",
                "320",
                "lossless",
                "hires",
            ),
            SoulseekMatching.options(listOf(all), "Daft Punk", "Digital Love", 301).map {
                it.format
            },
        )
    }

    @Test
    fun aRequestedRemixMatchesRemixFiles() {
        assertEquals(listOf(remix.filename), names(listOf(reply(remix, flac)), title = "Digital Love (Remix)"))
    }

    @Test
    fun youTubeMusicTitleSuffixesDoNotBlockMatching() {
        assertEquals(listOf(flac.filename), names(listOf(reply(flac)), title = "Digital Love (Official Audio)"))
        assertEquals("daft punk digital love", SoulseekMatching.searchQuery("Daft Punk", "Digital Love (feat. Someone)"))
    }

    @Test
    fun aFreeSlotBeatsAQueuedPeer() {
        val busy = reply(flac, free = false, queue = 9).copy(username = "busy")
        val free = reply(flac).copy(username = "free")
        assertEquals(
            listOf("free", "busy"),
            SoulseekMatching.options(listOf(busy, free), "Daft Punk", "Digital Love", 301).map { it.username },
        )
    }

    @Test
    fun localFileNamesCannotEscapeTheDownloadFolder() {
        assertEquals("evil.flac", SoulseekMatching.localFileName("..\\..\\evil.flac"))
        assertEquals("evil.flac", SoulseekMatching.localFileName("../../evil.flac"))
        assertEquals("passwd", SoulseekMatching.localFileName("/etc/.passwd"))
        assertEquals("a_b_.mp3", SoulseekMatching.localFileName("Music\\a:b?.mp3"))
        assertEquals("soulseek-download", SoulseekMatching.localFileName("..\\"))
    }
}
