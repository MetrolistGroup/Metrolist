package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.AgentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LegacyLyricsTest {
    @Test
    fun plainCopyStripsBothFormatsAndKeepsBackgroundVocals() {
        for (text in listOf(
            "[00:01.00]{agent:v2}Lead\n<Lead:1:2>\n[00:02.00]{bg}Echo",
            "[lyrics]\n[00:01.000~00:02.000|v2]<00:01.000>Lead<00:02.000> [bg:Echo]",
        )) {
            assertEquals("Lead\nEcho", LyricsUtils.toPlainText(LyricsUtils.parseLyrics(text)))
        }
    }

    @Test
    fun voxenWorksWithAndWithoutMlxHeader() {
        val lyrics =
            """
            [00:22.50]{agent:v1}I'm all out of hope
            <I'm:22.499433:23.172395|all:23.172395:23.893787|out:23.893787:24.239579|of:24.239579:24.915085|hope:24.915085:25.924604000000002>
            [00:26.73]{agent:v1}One more bad break
            <One:26.732642000000002:27.033055|more:27.033055:27.381684|bad:27.381684:27.89289|break:27.89289:29.084351>
            [02:46.89]{agent:v2}They don't, they don't see the work that it takes, takes, takes
            [01:47.50]Say I feel it in the sky above
            <Say:107.59:107.89|I:107.89:108.023|feel:108.023:108.172|it:108.172:108.306|in:108.306:108.439|the:108.439:108.556|sky:108.556:108.708|above:108.708:109.621>
            """.trimIndent()
        for (input in listOf(lyrics, "[lyrics]\n$lyrics")) {
            val document = assertNotNull(LyricsUtils.parseMlxDocument(input))
            val lines = document.lines
            assertEquals(listOf(22_500L, 26_730L, 107_500L, 166_890L), lines.map { it.time })
            assertEquals(listOf("v1", "v1", null, "v2"), lines.map { it.agent })
            assertEquals("I'm all out of hope", lines.first().text)
            assertEquals(listOf(5, 4, 8, null), lines.map { it.words?.size })
            assertEquals(
                22.499433,
                lines
                    .first()
                    .words!!
                    .first()
                    .startTime,
            )
            assertEquals(
                25.924604000000002,
                lines
                    .first()
                    .words!!
                    .last()
                    .endTime,
            )
            assertEquals(25_924L, lines.first().endTime)
            assertTrue(lines.none { '{' in it.text || '<' in it.text })
            assertEquals(lines, LyricsUtils.parseLyrics(input))
        }
    }

    @Test
    fun legacyAgentsAndBackgroundsMapToDocumentModel() {
        val document =
            assertNotNull(
                LyricsUtils.parseMlxDocument(
                    """
                    [00:01.00]{agent:v1}Lead
                    [00:02.00]{bg}Harmony
                    <Harmony:2:2.9>
                    [00:03.00]{agent:v2}Second
                    [00:04.00]{agent:v1000}Everyone
                    [00:05.00]{agent:v2000}Other
                    [00:06.00]{agent:bg}Backing
                    [00:07.00]v3: <00:07.00>Third<00:08.00>
                    [bg:<00:07.20>Echo<00:08.10>]
                    """.trimIndent(),
                ),
            )
        assertEquals(AgentType.GROUP, document.agents["v1000"]?.type)
        assertEquals(AgentType.OTHER, document.agents["v2000"]?.type)
        assertEquals(listOf("v1", "v2000", "v3"), document.lines.filter { it.isBackground }.map { it.agent })
        assertTrue(
            document.lines
                .filter { it.isBackground }
                .flatMap { it.words.orEmpty() }
                .all { it.isBackground },
        )
        assertTrue(document.lines.none { '{' in it.text || "v3:" in it.text })
    }

    @Test
    fun lrcMetadataRepeatedTimestampsAndPlainTextAreReadable() {
        val lines = LyricsUtils.parseLyrics("[ar:Artist]\n[ti:Title]\n[offset:200]\n[00:01] [00:03.5]Again\n<Again:1:2>\n[00:05.123]Last")
        assertEquals(listOf(1_200L, 3_700L, 5_323L), lines.map { it.time })
        assertEquals(listOf("Again", "Again", "Last"), lines.map { it.text })
        assertEquals(lines[0].words, lines[1].words)
        assertEquals(listOf("Plain", "lyrics"), LyricsUtils.parseLyrics("Plain\nlyrics").map { it.text })
    }

    @Test
    fun invalidWordTimesAreSkippedAndInputBudgetStillApplies() {
        val line = LyricsUtils.parseLyrics("[00:01.00]Valid\n<bad:NaN:2|bad:2:Infinity|bad:3:2|bad:-1:2|Valid:1:2>").single()
        assertEquals(listOf("Valid"), line.words?.map { it.text })
        assertTrue(LyricsUtils.parseLyrics("[00:01.00]" + "x".repeat(513 * 1024)).isEmpty())
    }
}
