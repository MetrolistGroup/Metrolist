package com.metrolist.shared.api.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsEditingTest {
    @Test
    fun editorAddsTranslationsWithoutChangingSourceTimingOrMetadata() {
        val raw =
            """
            [meta]
            mlx_version=1.0
            lyric_source=Provider
            ignore_retiming=true
            // Keep this provenance comment.
            [song]
            title=Song
            songwriters=Writer
            [agents]
            v1=person,Singer
            [colors]
            background=#123456
            [lyrics]
            [part:00:01.000~00:05.000]Verse
            [00:01.000~00:02.000|v1]<00:01.000>Hello<00:02.000> [bg:Hey]
            [00:01.000~00:03.000]Again
            [00:03.000~00:04.000]
            [00:04.000~00:05.000]World
            [romanLyrics]
            [00:01.000]Hello
            [00:01.000]Again
            [00:03.000]
            [00:04.000]World
            """.trimIndent()
        val original = MlxParser.parse(raw)
        val translations = listOf("Bonjour", "Encore", null, "Monde")
        val displayed =
            original.lines.mapIndexed { index, line ->
                line.copy(time = line.time + 500, translatedText = translations[index])
            }

        val edited = LyricsUtils.lyricsForEditing(raw, displayed)
        assertTrue(edited.startsWith(raw))
        assertTrue("[translatedLyrics]" in edited)
        val parsed = MlxParser.parse(edited)
        assertEquals(
            original.copy(
                lines =
                    original.lines.mapIndexed {
                        index,
                        line,
                        ->
                        line.copy(translatedText = translations[index])
                    },
            ),
            parsed,
        )
        assertEquals(edited, LyricsUtils.lyricsForEditing(edited, parsed.lines))
    }

    @Test
    fun editorMergesPartialTranslationsAndPreservesFollowingSections() {
        val raw =
            """
            [lyrics]
            [00:01.000]Hello
            [00:02.000]World
            [translated_lyrics]
            [00:01.000]Edited greeting
            [00:02.000]
            [song]
            title=Keep me
            [romanLyrics]
            [00:01.000]Hello
            [00:02.000]World
            """.trimIndent()
        val original = MlxParser.parse(raw)
        val displayed = original.lines.map { it.copy(translatedText = "Generated") }
        val edited = MlxParser.parse(LyricsUtils.lyricsForEditing(raw, displayed))
        assertEquals(listOf("Edited greeting", "Generated"), edited.lines.map { it.translatedText })
        assertEquals(original.song, edited.song)
        assertEquals(original.lines.map { it.romanizedText }, edited.lines.map { it.romanizedText })
    }

    @Test
    fun editorKeepsUnsyncedAndLegacyLrcTranslationsAligned() {
        for (raw in listOf("Hello\nWorld", "[ar:Singer]\n[00:02.000]World\n[00:01.000]Hello")) {
            val original = MlxParser.parse(raw)
            val displayed = original.lines.map { it.copy(translatedText = "Translated ${it.text}") }
            val edited = MlxParser.parse(LyricsUtils.lyricsForEditing(raw, displayed))
            assertEquals(original.copy(lines = displayed), edited)
        }
    }

    @Test
    fun editorPreservesJsonFieldsAndEscapesTranslatedText() {
        val raw =
            """
            {
              "version": 2,
              "source": {"name": "Provider", "copyright": "Notice"},
              "song": {"title": "Song"},
              "custom": {"retain": true},
              "lines": [
                {"startMs": 2000, "text": "World", "custom": 42},
                {"startMs": 1000, "text": "Hello", "romanizedText": "Hello"}
              ]
            }
            """.trimIndent()
        val original = MlxParser.parse(raw)
        val displayed = original.lines.map { it.copy(translatedText = "Translated \"${it.text}\"\nsecond line") }
        val edited = LyricsUtils.lyricsForEditing(raw, displayed)
        assertEquals(original.copy(lines = displayed), MlxParser.parse(edited))
        val sourceJson = Json.parseToJsonElement(raw).jsonObject
        val editedJson = Json.parseToJsonElement(edited).jsonObject
        assertEquals(sourceJson - "lines", editedJson - "lines")
        assertEquals(
            sourceJson.getValue("lines").jsonArray.map { it.jsonObject },
            editedJson.getValue("lines").jsonArray.map {
                it.jsonObject -
                    "translatedText"
            },
        )
    }

    @Test
    fun editorLeavesRawTextAloneWithoutMatchingGeneratedTranslations() {
        val raw = "[00:01.000]Hello"
        val original = LyricsUtils.parseLyrics(raw)
        assertEquals(raw, LyricsUtils.lyricsForEditing(raw, original))
        assertEquals(raw, LyricsUtils.lyricsForEditing(raw, emptyList()))
        assertEquals(
            raw,
            LyricsUtils.lyricsForEditing(raw, original.map { it.copy(text = "Another song", translatedText = "Translation") }),
        )
        assertEquals("", LyricsUtils.lyricsForEditing(null, original))
    }
}
