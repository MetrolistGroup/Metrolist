package com.metrolist.shared.api.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsEditorTest {
    private val raw =
        """
        {
          "version": 2,
          "custom": {
            "keep": true
          },
          "source": {
            "name": "Provider",
            "copyright": "Notice"
          },
          "song": {
            "title": "Song"
          },
          "agents": {
            "v1": {
              "name": "Singer"
            }
          },
          "parts": [
            {
              "name": "Verse",
              "startMs": 1234
            }
          ],
          "lines": [
            {
              "startMs": 1234,
              "endMs": 3000,
              "text": "Hello world",
              "agent": "v1",
              "part": 0,
              "custom": 42,
              "words": [
                {
                  "text": "Hello ",
                  "startMs": 1234,
                  "endMs": 2100,
                  "syllables": [
                    {
                      "startMs": 1234,
                      "endMs": 1800
                    }
                  ]
                },
                {
                  "text": "world",
                  "startMs": 2100,
                  "endMs": 3000
                }
              ],
              "backgroundWords": [
                {
                  "text": "Hey",
                  "startMs": 1500,
                  "endMs": 1900
                }
              ],
              "translatedText": "Bonjour",
              "romanizedText": "Hello",
              "romanizedWords": [
                {
                  "text": "Hello",
                  "startMs": 1234,
                  "endMs": 3000
                }
              ]
            },
            {
              "startMs": 4000,
              "text": ""
            },
            {
              "startMs": 5000,
              "text": "Last"
            }
          ]
        }
        """.trimIndent()

    @Test
    fun unchangedJsonRoundTripPreservesEveryFieldAndSubCentisecond() {
        val editor = LyricsEditor(raw)
        assertEquals("[00:01.234] <00:01.234>Hello <00:02.10>world\n[00:04.00] \n[00:05.00] Last", editor.current.text)
        editor.edit(editor.current.text, 12)
        assertEquals(Json.parseToJsonElement(raw), Json.parseToJsonElement(editor.current.json))
        assertFalse(editor.canUndo)
    }

    @Test
    fun changingOnlyTimestampShiftsAllWordAndSyllableTiming() {
        val editor = LyricsEditor(raw)
        editor.edit(editor.current.text.replace("01.234", "01.734"), 0)
        val line =
            editor.current.document
                .getValue("lines")
                .jsonArray[0]
                .jsonObject
        assertEquals(JsonPrimitive(1734), line["startMs"])
        assertEquals(JsonPrimitive(3500), line["endMs"])
        assertEquals(JsonPrimitive(1734), line.getValue("words").jsonArray[0].jsonObject["startMs"])
        assertEquals(JsonPrimitive(2000), line.getValue("backgroundWords").jsonArray[0].jsonObject["startMs"])
        assertEquals(JsonPrimitive(1734), line.getValue("romanizedWords").jsonArray[0].jsonObject["startMs"])
        assertEquals(
            JsonPrimitive(2300),
            line
                .getValue("words")
                .jsonArray[0]
                .jsonObject
                .getValue("syllables")
                .jsonArray[0]
                .jsonObject["endMs"],
        )
        assertEquals(JsonPrimitive("Bonjour"), line["translatedText"])
    }

    @Test
    fun textChangesUseLineTimingAndPreserveOtherLinesAndBackground() {
        val editor = LyricsEditor(raw)
        editor.edit(editor.current.text.replace("<00:01.234>Hello <00:02.10>world", "New words"), 0)
        val original =
            Json
                .parseToJsonElement(raw)
                .jsonObject
                .getValue("lines")
                .jsonArray
        val lines =
            editor.current.document
                .getValue("lines")
                .jsonArray
        val line = lines[0].jsonObject
        assertFalse("words" in line)
        assertFalse("romanizedWords" in line)
        assertFalse("translatedText" in line)
        assertEquals(original[0].jsonObject["backgroundWords"], line["backgroundWords"])
        assertEquals(original[0].jsonObject["custom"], line["custom"])
        assertEquals(original.drop(1), lines.drop(1))
    }

    @Test
    fun insertDeleteAndUndoKeepOriginalObjectsAttached() {
        val editor = LyricsEditor(raw)
        val original = editor.current
        editor.edit("[00:00.00] Intro\n" + original.text, 0)
        assertEquals(
            original.document.getValue("lines").jsonArray,
            editor.current.document
                .getValue("lines")
                .jsonArray
                .drop(1),
        )
        editor.deleteLines()
        assertEquals(original.document, editor.current.document)
        editor.undo()
        assertTrue(editor.current.text.startsWith("[00:00.00] Intro"))
        editor.undo()
        assertEquals(original, editor.current)
        editor.redo()
        editor.edit(editor.current.text + "\n[00:06.00] End", 0)
        assertFalse(editor.canRedo)
    }

    @Test
    fun invalidTimestampRetainsTextAndSourceUntilCorrected() {
        val editor = LyricsEditor(raw)
        val original = editor.current
        editor.edit(original.text.replace("01.234", "99.bad"), 10)
        assertEquals(listOf(0), editor.current.errors)
        assertEquals(original.document, editor.current.document)
        editor.shift(500)
        assertTrue("99.bad" in editor.current.text)
        val recovered = LyricsEditor(editor.current.json)
        recovered.edit(editor.current.text, 10)
        recovered.edit(recovered.current.text.replace("99.bad", "01.234"), 10)
        assertEquals(original.document, recovered.current.document)
        editor.undo()
        assertEquals(original, editor.current)
    }

    @Test
    fun selectionAndCursorControlsOnlyChangeTheirLines() {
        val editor = LyricsEditor(raw)
        val original = editor.current
        val second = original.text.indexOf('\n') + 1
        val third = original.text.lastIndexOf('\n') + 1
        editor.edit(original.text, second, third)
        editor.shift(500)
        assertTrue("[00:04.50] " in editor.current.text)
        assertTrue(editor.current.text.startsWith("[00:01.234]"))
        assertTrue(editor.current.text.endsWith("[00:05.00] Last"))
        editor.timestamp(4250)
        assertTrue("[00:04.25] " in editor.current.text)
        editor.shift(-5000, all = true)
        assertTrue(
            editor.current.document
                .getValue("lines")
                .jsonArray
                .all {
                    it.jsonObject
                        .getValue("startMs")
                        .toString()
                        .toLong() >= 0
                },
        )
    }

    @Test
    fun shiftClampsTheWholeSelectionToPreserveRelativeTiming() {
        val editor = LyricsEditor(raw)
        editor.shift(-5000, all = true)
        val lines =
            editor.current.document
                .getValue("lines")
                .jsonArray
        assertEquals(JsonPrimitive(0), lines[0].jsonObject["startMs"])
        assertEquals(JsonPrimitive(2766), lines[1].jsonObject["startMs"])
        assertEquals(JsonPrimitive(3766), lines[2].jsonObject["startMs"])
    }

    @Test
    fun anEarlierBackgroundVocalCannotBeClippedByManualTimestampEdits() {
        val editor =
            LyricsEditor(
                """
                {"version":2,"lines":[{"startMs":1000,"text":"Main",
                  "backgroundWords":[{"text":"Hey","startMs":500,"endMs":1000}]}]}
                """.trimIndent(),
            )
        val original = editor.current
        editor.edit("[00:00.00] Main", 0)
        assertEquals(listOf(0), editor.current.errors)
        assertEquals(original.document, editor.current.document)
        assertEquals(original.json, editor.current.recoveryJson)
    }

    @Test
    fun emptyEditorAndImportKeepUndoAndRejectInvalidJson() {
        val editor = LyricsEditor("")
        assertEquals("", editor.current.text)
        editor.shift(500)
        assertFalse(editor.canUndo)
        editor.timestamp(1234)
        assertEquals("[00:01.234] ", editor.current.text)
        editor.edit(editor.current.text + "My lyric", 0)
        val before = editor.current
        assertFalse(editor.import("{bad json"))
        assertEquals(before, editor.current)
        assertTrue(editor.import("[offset:500]\n[00:01.00] Imported"))
        assertEquals("[00:01.50] Imported", editor.current.text)
        editor.undo()
        assertEquals(before, editor.current)
    }

    @Test
    fun legacyConversionRetainsTimingMetadataAndTranslations() {
        val raw =
            """
            [meta]
            mlx_version=1.0
            lyric_source=Provider
            ignore_retiming=true
            [song]
            title=Song
            [agents]
            v1=person,Singer
            [lyrics]
            [00:01.000~00:02.000|v1]<00:01.000>Hello<00:02.000> [bg:Hey]
            [translatedLyrics]
            [00:01.000]Bonjour
            """.trimIndent()
        val original = MlxParser.parse(raw)
        val result = MlxParser.parse(LyricsEditor(raw).current.json)
        assertEquals(
            original.lines.map { it.copy(words = it.words?.filterNot { word -> word.isBackground && word.isLineTimed }) },
            result.lines.map {
                it.copy(
                    words =
                        it.words?.filterNot { word ->
                            word.isBackground &&
                                word.isLineTimed
                        },
                )
            },
        )
        assertTrue(
            result.lines
                .single()
                .words
                .orEmpty()
                .any { it.isBackground && it.isLineTimed },
        )
        assertEquals(original.song, result.song)
        assertEquals(original.agents, result.agents)
        assertEquals(original.meta.ignoreRetiming, result.meta.ignoreRetiming)
    }

    @Test
    fun editingWordStartPreservesTextEndsAndProviderFields() {
        val editor = LyricsEditor(raw)
        val original = editor.current
        editor.edit(original.text.replace("<00:01.234>", "<00:01.40>"), 0)
        val before =
            original.document
                .getValue("lines")
                .jsonArray[0]
                .jsonObject
        val after =
            editor.current.document
                .getValue("lines")
                .jsonArray[0]
                .jsonObject
        assertTrue(editor.current.isValid)
        assertEquals(before - "words", after - "words")
        val word = after.getValue("words").jsonArray[0].jsonObject
        assertEquals(JsonPrimitive(1400), word["startMs"])
        assertEquals(JsonPrimitive(2100), word["endMs"])
        assertEquals(before.getValue("words").jsonArray[0].jsonObject["syllables"], word["syllables"])
        assertEquals(before.getValue("words").jsonArray[0].jsonObject - "startMs", word - "startMs")
        assertEquals(before.getValue("words").jsonArray[1], after.getValue("words").jsonArray[1])
        val parsed = LyricsUtils.parseLyrics(editor.current.json).first()
        assertEquals(1.4, parsed.words!!.first().startTime)
        assertEquals("Hello world", parsed.text)
        editor.undo()
        assertEquals(original, editor.current)
        editor.redo()
        assertEquals(
            1.4,
            LyricsUtils
                .parseLyrics(editor.current.json)
                .first()
                .words!!
                .first()
                .startTime,
        )
    }

    @Test
    fun manualLineTimeAndPlaybackTimestampShiftInlineTags() {
        val editor = LyricsEditor(raw)
        editor.edit(editor.current.text.replace("[00:01.234]", "[00:01.734]"), 0)
        assertTrue(editor.current.isValid)
        assertTrue(editor.current.text.startsWith("[00:01.734] <00:01.734>Hello <00:02.60>world"))
        editor.timestamp(2234)
        assertTrue(editor.current.isValid)
        assertTrue(editor.current.text.startsWith("[00:02.234] <00:02.234>Hello <00:03.10>world"))
        editor.shift(-500)
        assertTrue(editor.current.isValid)
        assertTrue(editor.current.text.startsWith("[00:01.734] <00:01.734>Hello <00:02.60>world"))
        assertEquals(
            JsonPrimitive(2000),
            editor.current.document
                .getValue("lines")
                .jsonArray[0]
                .jsonObject
                .getValue("backgroundWords")
                .jsonArray[0]
                .jsonObject["startMs"],
        )
    }

    @Test
    fun removingTagsUsesLineTimingAndRetainsBackgroundAndTranslations() {
        val editor = LyricsEditor(raw)
        val original =
            editor.current.document
                .getValue("lines")
                .jsonArray[0]
                .jsonObject
        editor.edit(
            editor.current.text
                .replace("<00:01.234>", "")
                .replace("<00:02.10>", ""),
            0,
        )
        val line =
            editor.current.document
                .getValue("lines")
                .jsonArray[0]
                .jsonObject
        assertTrue(editor.current.isValid)
        assertEquals(original - setOf("words", "romanizedWords"), line)
        val parsed = LyricsUtils.parseLyrics(editor.current.json).first()
        assertTrue(parsed.words.orEmpty().all { it.isBackground })
        assertEquals("Hello world", parsed.text)
    }

    @Test
    fun addingTagsCreatesWordTimingForPlayback() {
        val editor = LyricsEditor("[00:08.20] Hvað með …\n[00:10.00] Next")
        editor.edit("[00:08.20] <00:08.20>Hvað <00:08.51>með <00:09.00>…\n[00:10.00] Next", 0)
        assertTrue(editor.current.isValid)
        val line = LyricsUtils.parseLyrics(editor.current.json).first()
        assertEquals("Hvað með …", line.text)
        assertEquals(listOf(8.2, 8.51, 9.0), line.words!!.map { it.startTime })
        assertEquals(listOf(8.51, 9.0, 10.0), line.words!!.map { it.endTime })
        assertEquals(listOf("Hvað", "með", "…"), line.words!!.map { it.text })
        assertEquals(listOf(true, true, false), line.words!!.map { it.hasTrailingSpace })
        assertEquals(editor.current.document, LyricsEditor(editor.current.json).current.document)
    }

    @Test
    fun malformedWordTagsKeepTheLastValidDocumentAndDraft() {
        val invalid =
            listOf(
                "<00:99.20>",
                "<00:08.x>",
                "<00:08.2>",
                "<00:08.2000>",
                "<00:08.20",
                "00:08.20>",
                "<-01:08.20>",
                "<999999999999999999999:08.20>",
            )
        for (tag in invalid) {
            val editor = LyricsEditor(raw)
            val original = editor.current
            val text = original.text.replace("<00:01.234>", tag)
            editor.edit(text, 0)
            assertFalse(editor.current.isValid, tag)
            assertEquals(listOf(0), editor.current.errors, tag)
            assertEquals(original.document, editor.current.document, tag)
            assertEquals(original.json, editor.current.recoveryJson, tag)
            assertEquals(text, editor.current.text, tag)
            editor.shift(500)
            assertEquals(text, editor.current.text, tag)
            val recovered = LyricsEditor(editor.current.recoveryJson)
            recovered.edit(editor.current.text, 0)
            assertFalse(recovered.current.isValid, tag)
            recovered.edit(original.text, 0)
            assertEquals(original.document, recovered.current.document, tag)
        }
    }

    @Test
    fun taggedTextEditsRetainTimingAndClearStaleForegroundTranslations() {
        val editor = LyricsEditor(raw)
        editor.edit(editor.current.text.replace("Hello ", "Hi  "), 0)
        assertTrue(editor.current.isValid)
        val line =
            editor.current.document
                .getValue("lines")
                .jsonArray[0]
                .jsonObject
        assertEquals(JsonPrimitive("Hi  world"), line["text"])
        assertFalse("translatedText" in line)
        assertFalse("romanizedWords" in line)
        assertFalse("romanizedText" in line)
        assertTrue("backgroundWords" in line)
        assertEquals(listOf(JsonPrimitive(1234), JsonPrimitive(2100)), line.getValue("words").jsonArray.map { it.jsonObject["startMs"] })
        assertFalse("syllables" in line.getValue("words").jsonArray[0].jsonObject)
    }

    @Test
    fun addingAndRemovingIndividualTagsPreservesEveryCharacter() {
        val editor = LyricsEditor("[00:01.00] One two three\n[00:05.00] End")
        editor.edit("[00:01.00] One <00:02.00>two <00:03.00>three\n[00:05.00] End", 0)
        assertTrue(editor.current.isValid)
        assertEquals("One two three", LyricsUtils.parseLyrics(editor.current.json).first().text)
        assertEquals(
            listOf(1.0, 2.0, 3.0),
            LyricsUtils
                .parseLyrics(editor.current.json)
                .first()
                .words!!
                .map { it.startTime },
        )
        editor.edit(editor.current.text.replace("<00:02.00>", ""), 0)
        assertTrue(editor.current.isValid)
        val line = LyricsUtils.parseLyrics(editor.current.json).first()
        assertEquals("One two three", line.text)
        assertEquals(listOf("One two", "three"), line.words!!.map { it.text })
    }

    @Test
    fun wordTextEscapesAndDifferentLineTextSurviveRoundTripAndTimingEdits() {
        val source = Json.parseToJsonElement(raw).jsonObject
        val lines = source.getValue("lines").jsonArray.toMutableList()
        val line = lines[0].jsonObject
        val words = line.getValue("words").jsonArray.toMutableList()
        val literal = "  A\\n\n\r<00:12.34>  "
        words[0] = JsonObject(words[0].jsonObject + ("text" to JsonPrimitive(literal)))
        lines[0] = JsonObject(line + ("words" to JsonArray(words)))
        val document = JsonObject(source + ("lines" to JsonArray(lines)))
        val editor = LyricsEditor(document.toString())
        assertTrue(editor.current.isValid)
        assertTrue("\\<00:12.34\\>" in editor.current.text)
        editor.edit(editor.current.text, 0)
        assertEquals(document, editor.current.document)
        editor.edit(editor.current.text.replace("<00:01.234>", "<00:01.40>"), 0)
        assertTrue(editor.current.isValid)
        val changed =
            editor.current.document
                .getValue("lines")
                .jsonArray[0]
                .jsonObject
        assertEquals(line["text"], changed["text"])
        assertEquals(JsonPrimitive(literal), changed.getValue("words").jsonArray[0].jsonObject["text"])
        assertEquals(editor.current.text, LyricsEditor(editor.current.json).current.text)
    }

    @Test
    fun legacyWordMillisecondsDoNotTruncateDuringConversion() {
        val editor = LyricsEditor("[00:01.001]<00:01.001>First<00:01.003> word<00:04.007>")
        assertEquals("[00:01.001] <00:01.001>First<00:01.003> word", editor.current.text)
        val line = LyricsUtils.parseLyrics(editor.current.json).single()
        assertEquals(listOf(1.001, 1.003), line.words!!.map { it.startTime })
        assertEquals(listOf(1.003, 4.007), line.words!!.map { it.endTime })
        assertEquals(editor.current.text, LyricsEditor(editor.current.json).current.text)
    }

    @Test
    fun newFinalWordUsesSongDurationOrTheEnhancedLrcEndRule() {
        for ((song, expectedEnd) in listOf("\"song\":{\"durationMs\":5000}," to 5.0, "" to 2.5)) {
            val editor = LyricsEditor("{\"version\":2,$song\"lines\":[{\"startMs\":1000,\"text\":\"One two\"}]}")
            editor.edit("[00:01.00] <00:01.00>One <00:02.00>two", 0)
            assertTrue(editor.current.isValid)
            val words = LyricsUtils.parseLyrics(editor.current.json).single().words!!
            assertEquals(listOf(2.0, expectedEnd), words.map { it.endTime })
        }
    }

    @Test
    fun addingTooManyWordTagsRetainsTheRecoverableDocument() {
        val editor = LyricsEditor("[00:01.00] Original")
        val original = editor.current
        val row = "[00:01.00] " + "<00:01.00>a".repeat(1000)
        val text = List(MlxParser.MAX_WORD_MARKERS / 1000 + 1) { row }.joinToString("\n")
        editor.edit(text, 0)
        assertTrue(editor.current.exceedsLimits)
        assertFalse(editor.current.isValid)
        assertEquals(original.json, editor.current.recoveryJson)
        assertEquals(text, editor.current.text)
    }

    @Test
    fun wordTextCountsTowardTheSavedDocumentLineLimit() {
        val editor = LyricsEditor("[00:01.00] " + "a".repeat(MlxParser.MAX_LINE_CHARS / 2 + 1))
        val original = editor.current
        val text = original.text.replace("] ", "] <00:01.00>")
        editor.edit(text, 0)
        assertTrue(editor.current.exceedsLimits)
        assertFalse(editor.current.isValid)
        assertEquals(original.json, editor.current.recoveryJson)
        assertEquals(text, editor.current.text)
    }

    @Test
    fun addingTagsToLineTimedWordsEnablesWordSync() {
        val editor =
            LyricsEditor(
                """{"version":2,"lines":[{"startMs":1000,"endMs":3000,"text":"Word",
                    "words":[{"text":"Word","startMs":1000,"endMs":3000,"isLineTimed":true}]}]}""",
            )
        assertEquals("[00:01.00] Word", editor.current.text)
        val original = editor.current
        editor.timestamp(1500)
        assertTrue(editor.current.isValid)
        val shiftedWord =
            LyricsUtils
                .parseLyrics(editor.current.json)
                .single()
                .words!!
                .single()
        assertTrue(shiftedWord.isLineTimed)
        assertEquals(1.5, shiftedWord.startTime)
        assertEquals(3.5, shiftedWord.endTime)
        editor.undo()
        assertEquals(original, editor.current)
        editor.edit("[00:01.00] <00:01.00>Word", 0)
        assertTrue(editor.current.isValid)
        assertFalse(
            LyricsUtils
                .parseLyrics(editor.current.json)
                .single()
                .words!!
                .single()
                .isLineTimed,
        )
    }

    @Test
    fun shorterShiftedTagsKeepSelectionInsideTheText() {
        val editor = LyricsEditor(raw.replace("\"startMs\": 2100", "\"startMs\": 2134"))
        val text = editor.current.text.replace("[00:01.234]", "[00:01.24]")
        editor.edit(text, text.length, 0)
        assertTrue(editor.current.isValid)
        assertTrue(editor.current.text.length < text.length)
        assertEquals(editor.current.text.length, editor.current.selectionStart)
        assertEquals(0, editor.current.selectionEnd)
        assertTrue(editor.current.text.startsWith("[00:01.24] <00:01.24>Hello <00:02.14>world"))
    }
}
