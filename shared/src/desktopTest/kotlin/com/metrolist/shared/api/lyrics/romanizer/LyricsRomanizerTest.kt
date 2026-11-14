package com.metrolist.shared.api.lyrics.romanizer

import com.metrolist.shared.model.WordTimestamp
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsRomanizerTest {
    @BeforeTest
    fun installJapaneseDictionaryFixture() {
        JapaneseRomanizerTestFixture.installDictionary()
    }

    @Test
    fun oversizedRomanizationAlignmentFallsBackToIsolatedGuides() {
        val guides = List(500) { "guide" }
        val words = List(500) { WordTimestamp("word", 0.0, 1.0) }

        assertEquals(guides, LyricsRomanizer.alignRomanizationAnchors("x".repeat(500), guides, words))
    }

    @Test
    fun romanizesJapaneseKanjiAndKanaWithKuromoji() =
        runBlocking {
            val result =
                LyricsRomanizer.romanize(
                    fullText = "君のことが好き",
                    line = "君のことが好き",
                    enabledLanguages = setOf("Japanese"),
                    cyrillicByLine = false,
                )

            assertEquals("kimi no koto ga suki", result)
        }

    @Test
    fun japaneseLyricsContextDisambiguatesKanjiOnlyLineWhenChineseIsEnabled() =
        runBlocking {
            val words = listOf(WordTimestamp("君", 0.0, 1.0))
            val result =
                LyricsRomanizer.romanizeWords(
                    fullText = "君\n君のことが好き",
                    line = "君",
                    words = words,
                    enabledLanguages = setOf("Japanese", "Chinese"),
                    cyrillicByLine = false,
                )

            assertEquals("kimi", result?.first)
            assertEquals("kimi", result?.second?.single()?.text)
        }

    @Test
    fun chineseDominantLyricsKeepHanOnlyLinesMandarinWhenJapaneseAppears() =
        runBlocking {
            val fullText = "君\n你好世界\n我们一起唱歌\n君のことが好き"
            val words = listOf(WordTimestamp("君", 0.0, 1.0))
            val hanOnly =
                LyricsRomanizer.romanizeWords(
                    fullText = fullText,
                    line = "君",
                    words = words,
                    enabledLanguages = setOf("Japanese", "Chinese"),
                    cyrillicByLine = false,
                )
            val kanaLine =
                LyricsRomanizer.romanize(
                    fullText = fullText,
                    line = "君のことが好き",
                    enabledLanguages = setOf("Japanese", "Chinese"),
                    cyrillicByLine = false,
                )

            assertEquals("jūn", hanOnly?.first)
            assertEquals("jūn", hanOnly?.second?.single()?.text)
            assertEquals("kimi no koto ga suki", kanaLine)
        }

    @Test
    fun japaneseDominantLyricsKeepHanOnlyLinesJapanese() =
        runBlocking {
            val words = listOf(WordTimestamp("君", 0.0, 1.0))
            val result =
                LyricsRomanizer.romanizeWords(
                    fullText = "君\n君のことが好き\n明日の空を見る",
                    line = "君",
                    words = words,
                    enabledLanguages = setOf("Japanese", "Chinese"),
                    cyrillicByLine = false,
                )

            assertEquals("kimi", result?.first)
            assertEquals("kimi", result?.second?.single()?.text)
        }

    @Test
    fun preparesJapaneseDictionaryForJvmKanaAndKanjiReadings() =
        runBlocking {
            assertEquals(true, LyricsRomanizer.prepareJapaneseRomanizationIfNeeded("かなカタカナ", setOf("Japanese")))
            assertEquals(true, LyricsRomanizer.prepareJapaneseRomanizationIfNeeded("東京かな", setOf("Japanese")))
        }

    @Test
    fun romanizesJapaneseKanaOnlyWithKuromoji() =
        runBlocking {
            val result =
                LyricsRomanizer.romanize(
                    fullText = "こんにちは",
                    line = "こんにちは",
                    enabledLanguages = setOf("Japanese"),
                    cyrillicByLine = false,
                )

            assertEquals("konnichiwa", result)
        }

    @Test
    fun boundsRecursiveArabicPrefixes() {
        val result = romanizeArabic("و".repeat(10_000))

        assertEquals(true, result.isNotEmpty())
    }

    @Test
    fun romanizesArabicCommonWordsAndMultiWord() =
        runBlocking {
            val result =
                LyricsRomanizer.romanize(
                    fullText = "يا حبيبي السلام عليكم",
                    line = "يا حبيبي السلام عليكم",
                    enabledLanguages = setOf("Arabic"),
                    cyrillicByLine = false,
                )

            assertEquals("ya habibi as-salam alaykum", result)
        }

    @Test
    fun romanizesArabicSunAndMoonLetters() =
        runBlocking {
            val resultSun =
                LyricsRomanizer.romanize(
                    fullText = "الشمس",
                    line = "الشمس",
                    enabledLanguages = setOf("Arabic"),
                    cyrillicByLine = false,
                )
            val resultMoon =
                LyricsRomanizer.romanize(
                    fullText = "القمر",
                    line = "القمر",
                    enabledLanguages = setOf("Arabic"),
                    cyrillicByLine = false,
                )

            assertEquals("ash-shams", resultSun)
            assertEquals("al-qamar", resultMoon)
        }

    @Test
    fun romanizesArabicTaMarbutaAndPrepositions() =
        runBlocking {
            val resultPreposition =
                LyricsRomanizer.romanize(
                    fullText = "بالشمس والقمر",
                    line = "بالشمس والقمر",
                    enabledLanguages = setOf("Arabic"),
                    cyrillicByLine = false,
                )
            val resultTaMarbutaIdafa =
                LyricsRomanizer.romanize(
                    fullText = "مدينة السلام",
                    line = "مدينة السلام",
                    enabledLanguages = setOf("Arabic"),
                    cyrillicByLine = false,
                )

            assertEquals("bish-shams wal-qamar", resultPreposition)
            assertEquals("madinat as-salam", resultTaMarbutaIdafa)
        }

    @Test
    fun romanizesUnvoweledArabicWordsAndPhrases() =
        runBlocking {
            val resultPhrase =
                LyricsRomanizer.romanize(
                    fullText = "صباح الخير يا حبيبي أين أنت",
                    line = "صباح الخير يا حبيبي أين أنت",
                    enabledLanguages = setOf("Arabic"),
                    cyrillicByLine = false,
                )
            val resultRootWord =
                LyricsRomanizer.romanize(
                    fullText = "كتب",
                    line = "كتب",
                    enabledLanguages = setOf("Arabic"),
                    cyrillicByLine = false,
                )

            assertEquals("sabah al-khayr ya habibi ayn anta", resultPhrase)
            assertEquals("katab", resultRootWord)
        }

    @Test
    fun romanizesArabicMultiWordIdiomsAndProperNames() =
        runBlocking {
            val resultPhrase =
                LyricsRomanizer.romanize(
                    fullText = "إن شاء الله شو عم تعمل عبد الله",
                    line = "إن شاء الله شو عم تعمل عبد الله",
                    enabledLanguages = setOf("Arabic"),
                    cyrillicByLine = false,
                )
            val resultName =
                LyricsRomanizer.romanize(
                    fullText = "محمد وأحمد علي",
                    line = "محمد وأحمد علي",
                    enabledLanguages = setOf("Arabic"),
                    cyrillicByLine = false,
                )

            assertEquals("in sha allah shu am ta'mal abd allah", resultPhrase)
            assertEquals("Muhammad wa-Ahmad Ali", resultName)
        }

    @Test
    fun romanizesArabicMorphologicalPatternsAndDialects() =
        runBlocking {
            val resultMorph =
                LyricsRomanizer.romanize(
                    fullText = "مكتوب كاتب قلوب",
                    line = "مكتوب كاتب قلوب",
                    enabledLanguages = setOf("Arabic"),
                    cyrillicByLine = false,
                )
            val resultDialect =
                LyricsRomanizer.romanize(
                    fullText = "إزيك يا حبيبي ما فيش مشكلة",
                    line = "إزيك يا حبيبي ما فيش مشكلة",
                    enabledLanguages = setOf("Arabic"),
                    cyrillicByLine = false,
                )

            assertEquals("maktub katib qulub", resultMorph)
            assertEquals("ezzayyak ya habibi ma fish mushkilah", resultDialect)
        }

    @Test
    fun smallTsuKeepsItsConsonantWhenSyllablesAreSplit() =
        runBlocking {
            val words =
                listOf(
                    WordTimestamp("わ", 0.0, 1.0, hasTrailingSpace = false),
                    WordTimestamp("ら", 1.0, 2.0, hasTrailingSpace = false),
                    WordTimestamp("っ", 2.0, 3.0, hasTrailingSpace = false),
                    WordTimestamp("て", 3.0, 4.0, hasTrailingSpace = false),
                )
            val result =
                LyricsRomanizer.romanizeWords(
                    fullText = "わらって",
                    line = "わらって",
                    words = words,
                    enabledLanguages = setOf("Japanese"),
                    cyrillicByLine = false,
                )

            assertEquals("waratte", result?.first)
            assertEquals("waratte", result?.second?.joinToString("") { it.text })
        }
}
