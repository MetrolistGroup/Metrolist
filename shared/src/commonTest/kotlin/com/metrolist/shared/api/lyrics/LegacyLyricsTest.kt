package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.AgentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LegacyLyricsTest {
    private val pro =
        """
        [00:00.64]
        <00:00.64>v1:<00:00.64>Li<00:00.841>ly <00:01.033>was <00:01.233>a <00:01.443>li<00:01.823>ttle <00:02.191>girl<00:02.932>
        """.trimIndent()

    private val advanced =
        """
        [03:06.30]{agent:v2}you got the good feelin'
        <you:186.300171:186.384611|got:186.384611:186.61761800000002|the:186.61761800000002:186.718364|good:186.718364:186.94906400000002|fee:186.94906400000002:187.19684|lin':187.19684:187.545367>
        [03:07.70]{agent:v1}Let's get it, let's get it
        <Let's:187.698776:187.93201200000001|get:187.93201200000001:188.156681000000002|it,:188.15668100000002:188.60991900000002|let's:188.60991900000002:188.845089|get:188.845089:189.064296|it:189.064296:189.289883>
        """.trimIndent()

    @Test
    fun proJoinsHeadersAndWordLinesAndRemovesTimedSingerPrefixes() {
        val source = listOf("v1", "v2", "v1000", "bg").joinToString("\n") { pro.replace("v1:", "$it:") }
        val document = MlxParser.parse(source)
        assertEquals(4, document.lines.size)
        assertEquals(listOf("v1", "v2", "v1000", "v1000"), document.lines.map { it.agent })
        assertEquals(AgentType.GROUP, document.agents["v1000"]?.type)
        for (line in document.lines) {
            assertEquals(640L, line.time)
            assertEquals(2932L, line.endTime)
            assertEquals("Lily was a little girl", line.text)
            val words = assertNotNull(line.words)
            assertEquals(listOf("Li", "ly", "was", "a", "li", "ttle", "girl"), words.map { it.text })
            assertEquals(listOf(0.64, 0.841, 1.033, 1.233, 1.443, 1.823, 2.191), words.map { it.startTime })
            assertEquals(listOf(0.841, 1.033, 1.233, 1.443, 1.823, 2.191, 2.932), words.map { it.endTime })
            assertEquals(listOf(false, true, true, true, false, true, false), words.map { it.hasTrailingSpace })
        }
        assertEquals(listOf(false, false, false, true), document.lines.map { it.isBackground })
        assertTrue(
            document.lines
                .last()
                .words!!
                .all { it.isBackground },
        )
        assertEquals(document.lines, LyricsUtils.parseLyrics(source))
        assertEquals(document.lines, MlxParser.parse(source.replace("]\n<", "]<")).lines)
    }

    @Test
    fun advancedKeepsPunctuationFloatPrecisionAndAgents() {
        val source = advanced + "\n" + advanced.replace("{agent:v2}", "{agent:v1000}").replace("{agent:v1}", "{agent:bg}")
        val document = MlxParser.parse(source)
        assertEquals(listOf("v2", "v1000", "v1", "v1000"), document.lines.map { it.agent })
        assertEquals(AgentType.GROUP, document.agents["v1000"]?.type)
        val line = document.lines[2]
        assertEquals(187_700L, line.time)
        assertEquals("Let's get it, let's get it", line.text)
        val words = assertNotNull(line.words)
        assertEquals(listOf("Let's", "get", "it,", "let's", "get", "it"), words.map { it.text })
        assertEquals(187.698776, words.first().startTime)
        assertEquals(187.93201200000001, words.first().endTime)
        assertEquals(188.15668100000002, words[2].startTime)
        assertEquals(189.289883, words.last().endTime)
        assertEquals(
            "lin'",
            document.lines
                .first()
                .words!!
                .last()
                .text,
        )
        assertTrue(document.lines.last().isBackground)
        assertTrue(
            document.lines
                .last()
                .words!!
                .all { it.isBackground },
        )
        assertEquals(document.lines, LyricsUtils.parseLyrics(source))
    }

    @Test
    fun legacyEditorOpensPastesImportsAndSavesBothFormats() {
        for (source in listOf(
            pro,
            advanced,
            pro.replace("v1:", "v1000:"),
            pro.replace("v1:", "bg:"),
            advanced.replace("{agent:v1}", "{agent:bg}"),
        )) {
            val editor = LyricsEditor(source)
            val opened = editor.current
            assertTrue(opened.isValid, source)
            editor.edit(opened.text, 0)
            assertEquals(opened, editor.current)
            assertFalse(editor.canUndo)

            val pasted = LyricsEditor("")
            pasted.edit(source, 0)
            assertTrue(pasted.current.isValid, source)
            assertEquals(opened.document, pasted.current.document)
            assertEquals(LyricsUtils.parseLyrics(opened.json), LyricsUtils.parseLyrics(pasted.current.recoveryJson))
            assertFalse(pasted.current.text.contains("{agent:"))
            assertFalse(pasted.current.text.contains("v1000:"))
            pasted.undo()
            assertEquals("", pasted.current.text)
            pasted.redo()
            assertEquals(opened.document, pasted.current.document)

            val imported = LyricsEditor("")
            assertTrue(imported.import(source), source)
            assertEquals(opened.document, imported.current.document)
            imported.edit(
                imported.current.text
                    .replace("girl", "world")
                    .replace("get", "sing"),
                0,
            )
            assertTrue(imported.current.isValid)
            assertEquals(opened.document["agents"], imported.current.document["agents"])
            assertTrue(LyricsUtils.parseLyrics(imported.current.recoveryJson).isNotEmpty())
        }
    }

    @Test
    fun malformedLegacyPastesRetainTheRecoverableDocument() {
        val invalid =
            listOf(
                pro.replace("<00:00.841>", "<00:99.841>"),
                pro.replace("<00:00.841>", "<00:00.bad>"),
                pro.replace("[00:00.64]", "[00:99.64]"),
                "[00:01.00]\n<00:99.841>bad:1:2>",
                advanced.replace("187.698776", "NaN"),
                advanced.replace("187.93201200000001", "Infinity"),
                advanced.replace("187.698776", "188.698776"),
                advanced.replace("187.698776", "-187.698776"),
                advanced.dropLast(1),
            )
        for (source in invalid) {
            val editor = LyricsEditor(pro)
            val original = editor.current
            editor.edit(source, 0)
            assertFalse(editor.current.isValid, source)
            assertEquals(source, editor.current.text)
            assertEquals(original.json, editor.current.recoveryJson)
        }
    }

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
