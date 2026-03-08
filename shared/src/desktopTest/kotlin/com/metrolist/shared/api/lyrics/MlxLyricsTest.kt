package com.metrolist.shared.api.lyrics

import com.metrolist.shared.api.musicvideo.CounterpartPositionMap
import com.metrolist.shared.api.musicvideo.CounterpartSegment
import com.metrolist.shared.model.AgentType
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.MlxDocument
import com.metrolist.shared.model.MlxSongPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MlxLyricsTest {
    @Test
    fun mlxBuilderAndParserPreserveSongwritersAndSongParts() {
        val mlx =
            MlxBuilder()
                .meta(source = "Apple")
                .song(
                    title = "MALU MALU",
                    artist = "dia & INDAHKUS",
                    songwriters = listOf("Anangga Surya Dewangga", "Indah Kusuma", "Yosua Albert Simanjuntak"),
                ).agent("v1", AgentType.PERSON)
                .songPart("Chorus", startMs = 25188L, endMs = 43788L)
                .line(
                    startMs = 25188L,
                    endMs = 29495L,
                    agentId = "v1",
                    words =
                        listOf(
                            MlxWord("Pan", 25188L, 25388L, false),
                            MlxWord("dang", 25388L, 25569L, false),
                            MlxWord("ku", 25569L, 25747L, true),
                            MlxWord("ha", 25747L, 26035L, false),
                            MlxWord("nya", 26035L, 26378L, false),
                        ),
                ).songPart("Verse", startMs = 62344L, endMs = 80374L)
                .line(
                    startMs = 62344L,
                    endMs = 64260L,
                    agentId = "v1",
                    text = "Isi pikiranku kamu",
                ).build()

        assertTrue(mlx.contains("songwriters=Anangga Surya Dewangga, Indah Kusuma, Yosua Albert Simanjuntak"))
        assertTrue(mlx.contains("[part:00:25.188~00:43.788]Chorus"))
        assertTrue(mlx.contains("[part:01:02.344~01:20.374]Verse"))
        assertTrue(mlx.contains("<00:25.188>Pan<00:25.388>dang<00:25.569>ku <00:25.747>ha<00:26.035>nya<00:26.378>"))

        val doc = MlxParser.parse(mlx)
        assertNotNull(doc)
        assertEquals(listOf("Anangga Surya Dewangga", "Indah Kusuma", "Yosua Albert Simanjuntak"), doc.song.songwriters)
        assertEquals(2, doc.songParts.size)
        assertEquals("Chorus", doc.songParts[0].name)
        assertEquals(25188L, doc.songParts[0].startMs)
        assertEquals(43788L, doc.songParts[0].endMs)
        assertEquals("Verse", doc.songParts[1].name)
        assertEquals(62344L, doc.songParts[1].startMs)
        assertEquals(80374L, doc.songParts[1].endMs)

        assertEquals(2, doc.lines.size)
        assertEquals("Chorus", doc.lines[0].songPart)
        assertEquals("Verse", doc.lines[1].songPart)
    }

    @Test
    fun parseTtmlExtractsSongwritersAndSongParts() {
        val ttml =
            """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
                <head>
                    <metadata>
                        <ttm:agent type="person" xml:id="v1"/>
                        <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal">
                            <songwriters>
                                <songwriter>Songwriter One</songwriter>
                                <songwriter>Songwriter Two</songwriter>
                            </songwriters>
                        </iTunesMetadata>
                    </metadata>
                </head>
                <body>
                    <div begin="10.000" end="20.000" itunes:songPart="Chorus">
                        <p begin="10.000" end="15.000" ttm:agent="v1">
                            <span begin="10.000" end="12.000">Hello </span>
                            <span begin="12.000" end="15.000">World</span>
                        </p>
                    </div>
                    <div begin="25.000" end="35.000" itunes:songPart="Verse">
                        <p begin="25.000" end="30.000" ttm:agent="v1">
                            <span>Simple line</span>
                        </p>
                    </div>
                </body>
            </tt>
            """.trimIndent()

        val mlx = parseTtmlToMlx(ttml, "Test Title", "Test Artist", "Apple")
        assertTrue(mlx.contains("songwriters=Songwriter One, Songwriter Two"))
        assertTrue(mlx.contains("[part:00:10.000~00:20.000]Chorus"))
        assertTrue(mlx.contains("[part:00:25.000~00:35.000]Verse"))

        val doc = MlxParser.parse(mlx)
        assertNotNull(doc)
        assertEquals(listOf("Songwriter One", "Songwriter Two"), doc.song.songwriters)
        assertEquals(2, doc.songParts.size)
        assertEquals("Chorus", doc.songParts[0].name)
        assertEquals(10000L, doc.songParts[0].startMs)
        assertEquals(20000L, doc.songParts[0].endMs)
        assertEquals("Chorus", doc.lines[0].songPart)
        assertEquals("Verse", doc.lines[1].songPart)
    }

    @Test
    fun retimerRetimesSongParts() {
        val doc =
            MlxDocument(
                lines = listOf(LyricsEntry(time = 10000L, text = "Hello", endTime = 15000L)),
                songParts = listOf(MlxSongPart(name = "Chorus", startMs = 10000L, endMs = 20000L)),
            )

        val positionMap =
            CounterpartPositionMap(
                segments =
                    listOf(
                        CounterpartSegment(
                            sourceStartMs = 0L,
                            targetStartMs = 2000L,
                            durationMs = 30000L,
                        ),
                    ),
            )

        val retimed = MusicVideoLyricsRetimer.retimeLyricsWithPositionMap(doc, positionMap)
        assertEquals(1, retimed.songParts.size)
        assertEquals("Chorus", retimed.songParts[0].name)
        assertEquals(12000L, retimed.songParts[0].startMs)
        assertEquals(22000L, retimed.songParts[0].endMs)
        assertEquals(12000L, retimed.lines[0].time)
    }

    @Test
    fun extractSongwritersFromYouTubeBrowseResponse() {
        val jsonString =
            """
            {
                "onResponseReceivedActions": [
                    {
                        "openPopupAction": {
                            "popup": {
                                "dismissableDialogRenderer": {
                                    "title": "Song credits",
                                    "sections": [
                                        {
                                            "dismissableDialogContentSectionRenderer": {
                                                "title": { "runs": [{ "text": "Performed by" }] },
                                                "subtitle": {
                                                    "runs": [
                                                        { "text": "Toton Caribo" },
                                                        { "text": "\n" },
                                                        { "text": "Justy Aldrin" }
                                                    ]
                                                }
                                            }
                                        },
                                        {
                                            "dismissableDialogContentSectionRenderer": {
                                                "title": { "runs": [{ "text": "Written by" }] },
                                                "subtitle": {
                                                    "runs": [
                                                        { "text": "Anthon Batkormbawa" }
                                                    ]
                                                }
                                            }
                                        },
                                        {
                                            "dismissableDialogContentSectionRenderer": {
                                                "title": { "runs": [{ "text": "Produced by" }] },
                                                "subtitle": {
                                                    "runs": [
                                                        { "text": "Toton Caribo" }
                                                    ]
                                                }
                                            }
                                        }
                                    ]
                                }
                            }
                        }
                    }
                ]
            }
            """.trimIndent()

        val json = Json { ignoreUnknownKeys = true }
        val jsonObject = json.parseToJsonElement(jsonString).jsonObject
        val songwriters = YouTubeLyricsProvider.extractSongwriters(jsonObject)
        assertEquals(listOf("Anthon Batkormbawa"), songwriters)
    }

    @Test
    fun ttmlPreservesSpacesBetweenSiblingSpans() {
        val ttml =
            """
            <tt xmlns="http://www.w3.org/ns/ttml">
                <body>
                    <div>
                        <p begin="25.188" end="29.495">
                            <span begin="25.188" end="25.388">Pan</span><span begin="25.388" end="25.569">dang</span><span begin="25.569" end="25.747">ku</span> <span begin="25.747" end="26.035">ha</span><span begin="26.035" end="26.378">nya</span>
                        </p>
                    </div>
                </body>
            </tt>
            """.trimIndent()

        val mlx = parseTtmlToMlx(ttml, "Title", "Artist", "Apple")
        val doc = MlxParser.parse(mlx)
        assertNotNull(doc)
        assertEquals(1, doc.lines.size)
        val line = doc.lines[0]
        assertEquals("Pandangku hanya", line.text)
        assertNotNull(line.words)
        assertEquals(5, line.words.size)
        assertEquals("Pan", line.words[0].text)
        assertEquals(false, line.words[0].hasTrailingSpace)
        assertEquals("dang", line.words[1].text)
        assertEquals(false, line.words[1].hasTrailingSpace)
        assertEquals("ku", line.words[2].text)
        assertEquals(true, line.words[2].hasTrailingSpace)
        assertEquals("ha", line.words[3].text)
        assertEquals(false, line.words[3].hasTrailingSpace)
        assertEquals("nya", line.words[4].text)
        assertEquals(false, line.words[4].hasTrailingSpace)
    }

    @Test
    fun mlxBuilderAndParserSupportLastFetchedAndIgnoreRetiming() {
        val mlx =
            MlxBuilder()
                .meta(source = "BetterLyrics", ignoreRetiming = true)
                .song(title = "あぶく - Bubble", artist = "Yorushika")
                .line(1000L, "test line")
                .build()

        assertTrue(mlx.contains("mlx_version=1.0"))
        assertTrue(mlx.contains("lyric_source=BetterLyrics"))
        assertTrue(mlx.contains("ignore_retiming=true"))
        assertTrue(mlx.contains("last_fetched="))
        val lastFetchedLine = mlx.lines().first { it.startsWith("last_fetched=") }
        assertTrue(lastFetchedLine.substringAfter("last_fetched=").isNotBlank())

        val doc = MlxParser.parse(mlx)
        assertNotNull(doc)
        assertTrue(doc.meta.ignoreRetiming)
        assertNotNull(doc.meta.lastFetched)
        assertTrue(doc.meta.lastFetched.isNotBlank())

        val mlxNormal =
            MlxBuilder()
                .meta(source = "Unison", ignoreRetiming = false)
                .song(title = "Song", artist = "Artist")
                .line(1000L, "line")
                .build()

        assertTrue(mlxNormal.contains("ignore_retiming=false"))
        val docNormal = MlxParser.parse(mlxNormal)
        assertNotNull(docNormal)
        assertFalse(docNormal.meta.ignoreRetiming)
    }

    @Test
    fun musicVideoLyricsRetimerRespectsIgnoreRetiming() {
        val doc =
            MlxParser.parse(
                """
                [meta]
                mlx_version=1.0
                lyric_source=Unison
                ignore_retiming=true

                [song]
                title=Test
                artist=Artist

                [lyrics]
                [00:10.000]Test line
                """.trimIndent(),
            )
        assertTrue(doc.meta.ignoreRetiming)

        val positionMap =
            CounterpartPositionMap(
                segments =
                    listOf(
                        CounterpartSegment(
                            sourceStartMs = 0L,
                            targetStartMs = 5000L,
                            durationMs = 20000L,
                        ),
                    ),
            )

        val mapped = MusicVideoLyricsRetimer.retimeLyricsWithPositionMap(doc, positionMap)
        assertEquals(10000L, mapped.lines[0].time)

        val cue = YouTubeTranscriptCue(text = "Test line", startMs = 15000L, durationMs = 3000L)
        val retimedResult = MusicVideoLyricsRetimer.retimeLyrics(doc, listOf(cue))
        assertNull(retimedResult)
    }
}
