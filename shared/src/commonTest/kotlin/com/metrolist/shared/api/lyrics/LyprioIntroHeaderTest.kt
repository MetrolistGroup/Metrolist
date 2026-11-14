package com.metrolist.shared.api.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class LyprioIntroHeaderTest {
    @Test
    fun removesOnlyTheLeadingExactHeaderInEitherOrder() {
        for (header in listOf("Song - Artist", " ARTIST  –  song ", "Song — Artist")) {
            val mlx =
                MlxBuilder()
                    .song("Song", "Artist")
                    .line(0, header)
                    .line(4_000, "Opening lyric")
                    .line(8_000, header)
                    .build()
            val before = MlxParser.parse(mlx)
            val after = MlxParser.parse(removeLyprioIntroHeader(mlx, "Song", "Artist"))
            assertEquals(before.copy(lines = before.lines.drop(1)), after)
        }
    }

    @Test
    fun preservesPartialMatchesSungWordsAndMissingMetadata() {
        for (opening in listOf(
            "Song",
            "Artist",
            "Song and Artist",
            "Song - Artist forever",
            "Another Song - Artist",
            "Song - Another Artist",
        )) {
            val mlx = MlxBuilder().line(0, opening).line(4_000, "Song - Artist").build()
            assertEquals(mlx, removeLyprioIntroHeader(mlx, "Song", "Artist"))
        }
        val wordTimed = MlxBuilder().line(0, 1_000, words = listOf(MlxWord("Song - Artist", 0, 1_000))).line(4_000, "Next").build()
        assertEquals(wordTimed, removeLyprioIntroHeader(wordTimed, "Song", "Artist"))
        val background = MlxBuilder().line(0, 1_000, "Song - Artist", background = "Singing").line(4_000, "Next").build()
        assertEquals(background, removeLyprioIntroHeader(background, "Song", "Artist"))
        val single = MlxBuilder().unsyncedLine("Song - Artist").build()
        assertEquals(single, removeLyprioIntroHeader(single, "Song", "Artist"))
        val mlx = MlxBuilder().unsyncedLine("Song - Artist").unsyncedLine("Next").build()
        assertEquals(mlx, removeLyprioIntroHeader(mlx, "", "Artist"))
        assertEquals(mlx, removeLyprioIntroHeader(mlx, "Song", " "))
    }

    @Test
    fun matchesNonLatinMetadataWithoutRemovingPunctuation() {
        val mlx = MlxBuilder().unsyncedLine("夜に駆ける - YOASOBI").unsyncedLine("沈むように").build()
        assertEquals(listOf("沈むように"), MlxParser.parse(removeLyprioIntroHeader(mlx, "夜に駆ける", "YOASOBI")).lines.map { it.text })
        val punctuation = MlxBuilder().unsyncedLine("Song! - Artist").unsyncedLine("Next").build()
        assertEquals(punctuation, removeLyprioIntroHeader(punctuation, "Song", "Artist"))
    }

    @Test
    fun preservesJsonMetadataUnknownFieldsAndRichRemainingLines() {
        val mlx =
            """
            {"version":2,"provider":"Lyprio","source":{"name":"TEST","copyright":"Attribution"},"song":{"title":"Song","artist":"Artist"},
            "futureField":{"keep":true},"parts":[{"name":"Verse","startMs":4000}],"agents":{"v1":{"name":"Singer"}},
            "lines":[{"text":""},{"text":"Song - Artist","startMs":0,"romanizedText":"Header"},
            {"text":"Next","startMs":4000,"endMs":5000,"agent":"v1","part":0,"translatedText":"Translation","romanizedText":"Romanization",
            "words":[{"text":"Next","startMs":4000,"endMs":5000}],"futureLineField":42}]}
            """.trimIndent()
        val root = Json.parseToJsonElement(mlx).jsonObject
        val expected = JsonObject(root + ("lines" to JsonArray(root.getValue("lines").jsonArray.filterIndexed { i, _ -> i != 1 })))
        assertEquals(expected, Json.parseToJsonElement(removeLyprioIntroHeader(mlx, "Song", "Artist")))
    }

    @Test
    fun preservesLegacyTranslationAlignmentAndTiming() {
        for (timed in listOf(false, true)) {
            val headerTime = if (timed) "[00:00.000]" else ""
            val nextTime = if (timed) "[00:04.000]" else ""
            val mlx =
                """
                [meta]
                lyric_source=TEST
                [lyrics]
                ${headerTime}Song - Artist
                ${nextTime}Next
                [romanLyrics]
                ${headerTime}Roman header
                ${nextTime}Roman lyric
                [translatedLyrics]
                ${headerTime}Translated header
                ${nextTime}Translated lyric
                """.trimIndent()
            val before = MlxParser.parse(mlx)
            assertEquals(before.copy(lines = before.lines.drop(1)), MlxParser.parse(removeLyprioIntroHeader(mlx, "Song", "Artist")))
        }
    }

    @Test
    fun preservesRepeatedLegacyHeadersSharingOneSourceLine() {
        val mlx = "[lyrics]\n[00:00.000][00:01.000]Song - Artist\n[00:04.000]Next"
        assertEquals(mlx, removeLyprioIntroHeader(mlx, "Song", "Artist"))
    }

    @Test
    fun preservesInvalidDocuments() {
        for (mlx in listOf("{invalid", "")) {
            assertEquals(mlx, removeLyprioIntroHeader(mlx, "Song", "Artist"))
        }
    }
}
