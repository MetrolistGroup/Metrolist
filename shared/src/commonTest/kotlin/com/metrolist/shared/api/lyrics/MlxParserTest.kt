package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.LyricsTimingKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MlxParserTest {
    @Test
    fun rejectsKugouChineseTranslationInRomanizationWithoutChangingOtherFields() {
        for (translation in listOf("你好", "Hello 世界", "\uD840\uDC00", "\uFA11")) {
            val mlx =
                """
                {
                  "version":2,"provider":"LYPRIO","subprovider":"KUGOU","sourceFormat":"krc",
                  "song":{"title":"Song"},"agents":{"v1":{"type":"person"}},
                  "lines":[{
                    "startMs":1000,"endMs":2000,"text":"Hello","agent":"v1",
                    "words":[{"text":"Hello","startMs":1000,"endMs":2000}],
                    "backgroundText":"Echo","translatedText":"Bonjour",
                    "romanizedText":"$translation",
                    "romanizedWords":[{"text":"$translation","startMs":1000,"endMs":2000}]
                  }]
                }
                """.trimIndent()
            val unvalidated = requireNotNull(MlxJsonParser.parse(mlx))
            val expected = unvalidated.copy(lines = unvalidated.lines.map { it.copy(romanizedText = null, romanizedWords = null) })
            assertEquals(expected, MlxParser.parse(mlx))
            assertEquals(expected.lines, LyricsUtils.parseLyrics(mlx))
            assertEquals(expected, LyricsUtils.parseMlxDocument(mlx))
        }
    }

    @Test
    fun rejectsMalformedRomanizedWordsEvenWithoutMalformedLineText() {
        for (romanizedText in listOf("null", "\"Hello\"")) {
            val mlx =
                """
                {"version":2,"provider":"lyprio","subprovider":"kugou","lines":[{
                  "text":"Hello","romanizedText":$romanizedText,
                  "romanizedWords":[{"text":"你好","startMs":1000,"endMs":2000}]
                }]}
                """.trimIndent()
            val entry = MlxParser.parse(mlx).lines.single()
            assertNull(entry.romanizedText)
            assertNull(entry.romanizedWords)
        }
    }

    @Test
    fun preservesCorrectedKugouRomanizationAndOtherProviders() {
        val corrected =
            """
            {"version":2,"provider":"LYPRIO","subprovider":"KUGOU","lines":[
              {"text":"你好","romanizedText":"nǐ hǎo","translatedText":"Hello",
               "romanizedWords":[{"text":"nǐ hǎo","startMs":1000,"endMs":2000}]},
              {"text":"こんにちは","romanizedText":"konnichiwa","translatedText":"你好"},
              {"text":"Hello","translatedText":"你好"}
            ]}
            """.trimIndent()
        assertEquals(MlxJsonParser.parse(corrected), MlxParser.parse(corrected))
        for (metadata in listOf("\"provider\":\"OTHER\",\"subprovider\":\"KUGOU\",", "\"provider\":\"LYPRIO\",", "")) {
            val mlx = """{"version":2,$metadata"lines":[{"text":"Hello","romanizedText":"你好"}]}"""
            assertEquals(MlxJsonParser.parse(mlx), MlxParser.parse(mlx))
        }
    }

    @Test
    fun validatesLegacyKugouMlxRomanization() {
        val mlx =
            """
            [meta]
            provider=LYPRIO
            subprovider=KUGOU
            source_format=krc
            [lyrics]
            [00:01.000~00:02.000]<00:01.000>Hello<00:02.000>
            [00:02.000]こんにちは
            [romanLyrics]
            [00:01.000~00:02.000]<00:01.000>你好<00:02.000>
            [00:02.000]konnichiwa
            [translatedLyrics]
            [00:01.000]Bonjour
            [00:02.000]你好
            """.trimIndent()
        val document = MlxParser.parse(mlx)
        val original = MlxParser.parse(mlx.replace("provider=LYPRIO", "provider=OTHER"))
        assertEquals(original.lines[0].copy(romanizedText = null, romanizedWords = null), document.lines[0])
        assertEquals(original.lines[1], document.lines[1])
    }

    @Test
    fun jsonStructuralBudgetsRejectExcessLinesWordsAndLineText() {
        fun document(lines: String) = """{"version":2,"lines":[$lines]}"""
        val line = """{"text":"line"}"""
        assertEquals(5000, MlxParser.parse(document(List(5000) { line }.joinToString(","))).lines.size)
        assertTrue(MlxParser.parse(document(List(5001) { line }.joinToString(","))).lines.isEmpty())
        val word = """{"text":"x","startMs":0,"endMs":1}"""
        val words = List(10000) { word }.joinToString(",")
        for (field in listOf("words", "backgroundWords", "romanizedWords")) {
            val wordLine = """{"$field":[$words]}"""
            assertEquals(2, MlxParser.parse(document("$wordLine,$wordLine")).lines.size)
            val extraWord = """{"$field":[$word]}"""
            assertTrue(MlxParser.parse(document("$wordLine,$wordLine,$extraWord")).lines.isEmpty())
        }
        val mixedWords = List(7000) { word }.joinToString(",")
        val mixedLines = listOf("words", "backgroundWords", "romanizedWords").joinToString(",") { """{"$it":[$mixedWords]}""" }
        assertTrue(MlxParser.parse(document(mixedLines)).lines.isEmpty())
        val backgroundFallback = """{"backgroundText":"background","words":[$words]}"""
        assertTrue(MlxParser.parse(document("$backgroundFallback,$backgroundFallback")).lines.isEmpty())
        for (field in listOf("text", "backgroundText", "romanizedText", "translatedText")) {
            assertEquals(1, MlxParser.parse(document("""{"$field":"${"x".repeat(16384)}"}""")).lines.size)
            assertTrue(MlxParser.parse(document("""{"$field":"${"x".repeat(16385)}"}""")).lines.isEmpty())
        }
        val combined = """{"text":"${"x".repeat(10000)}","backgroundText":"${"x".repeat(10000)}"}"""
        assertTrue(MlxParser.parse(document(combined)).lines.isEmpty())
    }

    @Test
    fun duplicateTimestampsMatchAuxiliaryLinesByOccurrence() {
        val document =
            MlxParser.parse(
                """
                [meta]
                [lyrics]
                [00:01.000]First
                [00:01.000]Second
                [romanlyrics]
                [00:01.000]Roman first
                [00:01.000]Roman second
                [translatedlyrics]
                [00:01.000]Translation first
                [00:01.000]Translation second
                """.trimIndent(),
            )
        assertEquals(listOf("Roman first", "Roman second"), document.lines.map { it.romanizedText })
        assertEquals(listOf("Translation first", "Translation second"), document.lines.map { it.translatedText })
    }

    @Test
    fun jsonDocumentLimitMatchesDownloadLimit() {
        val mlx = """{"version":2,"lines":[{"startMs":1000,"endMs":2000,"text":"Line"}]}""".padEnd(8 * 1024 * 1024)

        assertTrue(MlxParser.isMlxFormat(mlx))
        assertEquals(
            "Line",
            MlxParser
                .parse(mlx)
                .lines
                .single()
                .text,
        )
        assertFalse(MlxParser.isMlxFormat(mlx + " "))
        assertTrue(MlxParser.parse(mlx + " ").lines.isEmpty())
    }

    @Test
    fun parsesTextDocumentsAboveOldLimit() {
        val mlx = "[meta]\n[lyrics]\n" + ("[00:01.000]" + "x".repeat(1_000) + "\n").repeat(600)

        assertTrue(mlx.length > 512 * 1024)
        assertTrue(MlxParser.isMlxFormat(mlx))
        assertEquals(600, MlxParser.parse(mlx).lines.size)
    }

    @Test
    fun formatValidationRejectsDocumentsWithoutUsableParsedLyrics() {
        val documents =
            listOf(
                """{"version":2,"lines":[{}]}""",
                """{"version":1,"lines":[{"text":"Line"}]}""",
                "[meta]\n[lyrics]\n",
                "[meta]\n[lyrics]\n" + "x".repeat(16 * 1024 + 1),
            )

        for (mlx in documents) {
            assertFalse(MlxParser.isMlxFormat(mlx))
        }
    }

    @Test
    fun derivesVisibleTextFromBackgroundContentWhenForegroundIsAbsent() {
        val lines =
            """
            [
              {"background":true,"backgroundText":"Echo"},
              {"background":true,"backgroundWords":[{"text":"Echo ","startMs":1000,"endMs":1500},{"text":"again","startMs":1500,"endMs":2000}]},
              {"text":"Lead","backgroundText":"Echo"},
              {"words":[{"text":"Lead","startMs":1000,"endMs":2000}],"backgroundText":"Echo"}
            ]
            """.trimIndent()
        val mlx = """{"version":2,"lines":$lines}"""

        assertTrue(MlxParser.isMlxFormat(mlx))
        assertEquals(listOf("Echo", "Echo again", "Lead", "Lead"), MlxParser.parse(mlx).lines.map { it.text })
    }

    @Test
    fun untimedBackgroundTextDoesNotCountAsWordTiming() {
        for (foreground in listOf("", "Lead")) {
            val mlx =
                """{"version":2,"lines":[{"startMs":1000,"endMs":2000,"text":"$foreground","backgroundText":"Echo"}]}"""
            val lines = MlxParser.parse(mlx).lines
            val backgroundWord =
                lines
                    .single()
                    .words
                    .orEmpty()
                    .single()

            assertEquals("Echo", backgroundWord.text)
            assertEquals(1.0, backgroundWord.startTime)
            assertEquals(2.0, backgroundWord.endTime)
            assertTrue(backgroundWord.isBackground)
            assertTrue(backgroundWord.isLineTimed)
            assertEquals(LyricsTimingKind.LINE_SYNCED, lyricsTimingKind(lines))
        }

        val timedBackground =
            """{"version":2,"lines":[{"startMs":1000,"endMs":2000,"backgroundWords":[{"text":"Echo","startMs":1000,"endMs":2000}]}]}"""
        assertEquals(LyricsTimingKind.WORD_SYNCED, lyricsTimingKind(MlxParser.parse(timedBackground).lines))
    }
}
