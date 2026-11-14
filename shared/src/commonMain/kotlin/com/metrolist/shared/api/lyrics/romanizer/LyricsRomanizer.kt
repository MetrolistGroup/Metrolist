/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics.romanizer

import com.metrolist.shared.model.WordTimestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LyricsRomanizer {
    private const val MAX_ALIGNMENT_CELLS = 250_000L
    private val kana =
        mapOf(
            "あ" to "a",
            "い" to "i",
            "う" to "u",
            "え" to "e",
            "お" to "o",
            "か" to "ka",
            "き" to "ki",
            "く" to "ku",
            "け" to "ke",
            "こ" to "ko",
            "さ" to "sa",
            "し" to "shi",
            "す" to "su",
            "せ" to "se",
            "そ" to "so",
            "た" to "ta",
            "ち" to "chi",
            "つ" to "tsu",
            "て" to "te",
            "と" to "to",
            "な" to "na",
            "に" to "ni",
            "ぬ" to "nu",
            "ね" to "ne",
            "の" to "no",
            "は" to "ha",
            "ひ" to "hi",
            "ふ" to "fu",
            "へ" to "he",
            "ほ" to "ho",
            "ま" to "ma",
            "み" to "mi",
            "む" to "mu",
            "め" to "me",
            "も" to "mo",
            "や" to "ya",
            "ゆ" to "yu",
            "よ" to "yo",
            "ら" to "ra",
            "り" to "ri",
            "る" to "ru",
            "れ" to "re",
            "ろ" to "ro",
            "わ" to "wa",
            "を" to "wo",
            "ん" to "n",
            "が" to "ga",
            "ぎ" to "gi",
            "ぐ" to "gu",
            "げ" to "ge",
            "ご" to "go",
            "ざ" to "za",
            "じ" to "ji",
            "ず" to "zu",
            "ぜ" to "ze",
            "ぞ" to "zo",
            "だ" to "da",
            "ぢ" to "ji",
            "づ" to "zu",
            "で" to "de",
            "ど" to "do",
            "ば" to "ba",
            "び" to "bi",
            "ぶ" to "bu",
            "べ" to "be",
            "ぼ" to "bo",
            "ぱ" to "pa",
            "ぴ" to "pi",
            "ぷ" to "pu",
            "ぺ" to "pe",
            "ぽ" to "po",
            "きゃ" to "kya",
            "きゅ" to "kyu",
            "きょ" to "kyo",
            "しゃ" to "sha",
            "しゅ" to "shu",
            "しょ" to "sho",
            "ちゃ" to "cha",
            "ちゅ" to "chu",
            "ちょ" to "cho",
            "にゃ" to "nya",
            "にゅ" to "nyu",
            "にょ" to "nyo",
            "ひゃ" to "hya",
            "ひゅ" to "hyu",
            "ひょ" to "hyo",
            "みゃ" to "mya",
            "みゅ" to "myu",
            "みょ" to "myo",
            "りゃ" to "rya",
            "りゅ" to "ryu",
            "りょ" to "ryo",
            "ぎゃ" to "gya",
            "ぎゅ" to "gyu",
            "ぎょ" to "gyo",
            "じゃ" to "ja",
            "じゅ" to "ju",
            "じょ" to "jo",
            "ぢゃ" to "ja",
            "ぢゅ" to "ju",
            "ぢょ" to "jo",
            "びゃ" to "bya",
            "びゅ" to "byu",
            "びょ" to "byo",
            "ぴゃ" to "pya",
            "ぴゅ" to "pyu",
            "ぴょ" to "pyo",
            "ふぁ" to "fa",
            "ふぃ" to "fi",
            "ふぇ" to "fe",
            "ふぉ" to "fo",
            "てぃ" to "ti",
            "でぃ" to "di",
            "とぅ" to "tu",
            "どぅ" to "du",
            "うぃ" to "wi",
            "うぇ" to "we",
            "うぉ" to "wo",
            "ゔぁ" to "va",
            "ゔぃ" to "vi",
            "ゔ" to "v",
            "ゔぇ" to "ve",
            "ゔぉ" to "vo",
            "ぁ" to "a",
            "ぃ" to "i",
            "ぅ" to "u",
            "ぇ" to "e",
            "ぉ" to "o",
        )

    private val cyrillic =
        mapOf(
            'А' to "A",
            'а' to "a",
            'Б' to "B",
            'б' to "b",
            'В' to "V",
            'в' to "v",
            'Г' to "G",
            'г' to "g",
            'Д' to "D",
            'д' to "d",
            'Е' to "E",
            'е' to "e",
            'Ё' to "Yo",
            'ё' to "yo",
            'Ж' to "Zh",
            'ж' to "zh",
            'З' to "Z",
            'з' to "z",
            'И' to "I",
            'и' to "i",
            'Й' to "Y",
            'й' to "y",
            'К' to "K",
            'к' to "k",
            'Л' to "L",
            'л' to "l",
            'М' to "M",
            'м' to "m",
            'Н' to "N",
            'н' to "n",
            'О' to "O",
            'о' to "o",
            'П' to "P",
            'п' to "p",
            'Р' to "R",
            'р' to "r",
            'С' to "S",
            'с' to "s",
            'Т' to "T",
            'т' to "t",
            'У' to "U",
            'у' to "u",
            'Ф' to "F",
            'ф' to "f",
            'Х' to "Kh",
            'х' to "kh",
            'Ц' to "Ts",
            'ц' to "ts",
            'Ч' to "Ch",
            'ч' to "ch",
            'Ш' to "Sh",
            'ш' to "sh",
            'Щ' to "Shch",
            'щ' to "shch",
            'Ы' to "Y",
            'ы' to "y",
            'Э' to "E",
            'э' to "e",
            'Ю' to "Yu",
            'ю' to "yu",
            'Я' to "Ya",
            'я' to "ya",
            'Є' to "Ye",
            'є' to "ye",
            'І' to "I",
            'і' to "i",
            'Ї' to "Yi",
            'ї' to "yi",
            'Ґ' to "G",
            'ґ' to "g",
            'Љ' to "Lj",
            'љ' to "lj",
            'Њ' to "Nj",
            'њ' to "nj",
            'Ћ' to "C",
            'ћ' to "c",
            'Џ' to "Dz",
            'џ' to "dz",
            'Ђ' to "Dj",
            'ђ' to "dj",
        )

    private val devanagari =
        mapOf(
            'अ' to "a",
            'आ' to "aa",
            'इ' to "i",
            'ई' to "ii",
            'उ' to "u",
            'ऊ' to "uu",
            'ए' to "e",
            'ऐ' to "ai",
            'ओ' to "o",
            'औ' to "au",
            'क' to "ka",
            'ख' to "kha",
            'ग' to "ga",
            'घ' to "gha",
            'च' to "cha",
            'छ' to "chha",
            'ज' to "ja",
            'झ' to "jha",
            'ट' to "ta",
            'ठ' to "tha",
            'ड' to "da",
            'ढ' to "dha",
            'त' to "ta",
            'थ' to "tha",
            'द' to "da",
            'ध' to "dha",
            'न' to "na",
            'प' to "pa",
            'फ' to "pha",
            'ब' to "ba",
            'भ' to "bha",
            'म' to "ma",
            'य' to "ya",
            'र' to "ra",
            'ल' to "la",
            'व' to "va",
            'श' to "sha",
            'ष' to "sha",
            'स' to "sa",
            'ह' to "ha",
            'ा' to "a",
            'ि' to "i",
            'ी' to "ii",
            'ु' to "u",
            'ू' to "uu",
            'े' to "e",
            'ै' to "ai",
            'ो' to "o",
            'ौ' to "au",
            'ं' to "n",
            'ः' to "h",
            'ँ' to "n",
            '्' to "",
        )

    private val gurmukhi =
        mapOf(
            'ਅ' to "a",
            'ਆ' to "aa",
            'ਇ' to "i",
            'ਈ' to "ii",
            'ਉ' to "u",
            'ਊ' to "uu",
            'ਏ' to "e",
            'ਐ' to "ai",
            'ਓ' to "o",
            'ਔ' to "au",
            'ਕ' to "ka",
            'ਖ' to "kha",
            'ਗ' to "ga",
            'ਘ' to "gha",
            'ਚ' to "cha",
            'ਛ' to "chha",
            'ਜ' to "ja",
            'ਝ' to "jha",
            'ਟ' to "ta",
            'ਠ' to "tha",
            'ਡ' to "da",
            'ਢ' to "dha",
            'ਤ' to "ta",
            'ਥ' to "tha",
            'ਦ' to "da",
            'ਧ' to "dha",
            'ਨ' to "na",
            'ਪ' to "pa",
            'ਫ' to "pha",
            'ਬ' to "ba",
            'ਭ' to "bha",
            'ਮ' to "ma",
            'ਯ' to "ya",
            'ਰ' to "ra",
            'ਲ' to "la",
            'ਵ' to "va",
            'ਸ' to "sa",
            'ਹ' to "ha",
            'ਾ' to "a",
            'ਿ' to "i",
            'ੀ' to "ii",
            'ੁ' to "u",
            'ੂ' to "uu",
            'ੇ' to "e",
            'ੈ' to "ai",
            'ੋ' to "o",
            'ੌ' to "au",
            'ੰ' to "n",
            'ਂ' to "n",
            '੍' to "",
        )

    fun stripAccents(text: String): String =
        text
            .map { character ->
                when (character) {
                    'à', 'á', 'â', 'ã', 'ä', 'å', 'ā', 'ă', 'ą', 'ǎ' -> 'a'
                    'À', 'Á', 'Â', 'Ã', 'Ä', 'Å', 'Ā', 'Ă', 'Ą', 'Ǎ' -> 'A'
                    'ç', 'ć', 'ĉ', 'ċ', 'č' -> 'c'
                    'Ç', 'Ć', 'Ĉ', 'Ċ', 'Č' -> 'C'
                    'ď', 'đ' -> 'd'
                    'Ď', 'Đ' -> 'D'
                    'è', 'é', 'ê', 'ë', 'ē', 'ĕ', 'ė', 'ę', 'ě' -> 'e'
                    'È', 'É', 'Ê', 'Ë', 'Ē', 'Ĕ', 'Ė', 'Ę', 'Ě' -> 'E'
                    'ĝ', 'ğ', 'ġ', 'ģ' -> 'g'
                    'Ĝ', 'Ğ', 'Ġ', 'Ģ' -> 'G'
                    'ĥ', 'ħ' -> 'h'
                    'Ĥ', 'Ħ' -> 'H'
                    'ì', 'í', 'î', 'ï', 'ĩ', 'ī', 'ĭ', 'į', 'ı', 'ǐ' -> 'i'
                    'Ì', 'Í', 'Î', 'Ï', 'Ĩ', 'Ī', 'Ĭ', 'Į', 'İ', 'Ǐ' -> 'I'
                    'ĵ' -> 'j'
                    'Ĵ' -> 'J'
                    'ķ' -> 'k'
                    'Ķ' -> 'K'
                    'ĺ', 'ļ', 'ľ', 'ŀ', 'ł' -> 'l'
                    'Ĺ', 'Ļ', 'Ľ', 'Ŀ', 'Ł' -> 'L'
                    'ñ', 'ń', 'ņ', 'ň' -> 'n'
                    'Ñ', 'Ń', 'Ņ', 'Ň' -> 'N'
                    'ò', 'ó', 'ô', 'õ', 'ö', 'ø', 'ō', 'ŏ', 'ő', 'ǒ' -> 'o'
                    'Ò', 'Ó', 'Ô', 'Õ', 'Ö', 'Ø', 'Ō', 'Ŏ', 'Ő', 'Ǒ' -> 'O'
                    'ŕ', 'ŗ', 'ř' -> 'r'
                    'Ŕ', 'Ŗ', 'Ř' -> 'R'
                    'ś', 'ŝ', 'ş', 'š' -> 's'
                    'Ś', 'Ŝ', 'Ş', 'Š' -> 'S'
                    'ţ', 'ť', 'ŧ' -> 't'
                    'Ţ', 'Ť', 'Ŧ' -> 'T'
                    'ù', 'ú', 'û', 'ü', 'ũ', 'ū', 'ŭ', 'ů', 'ű', 'ų', 'ǔ', 'ǘ', 'ǚ', 'ǜ' -> 'u'
                    'Ù', 'Ú', 'Û', 'Ü', 'Ũ', 'Ū', 'Ŭ', 'Ů', 'Ű', 'Ų', 'Ǔ', 'Ǚ', 'Ǜ' -> 'U'
                    'ŵ' -> 'w'
                    'Ŵ' -> 'W'
                    'ý', 'ÿ', 'ŷ' -> 'y'
                    'Ý', 'Ÿ', 'Ŷ' -> 'Y'
                    'ź', 'ż', 'ž' -> 'z'
                    'Ź', 'Ż', 'Ž' -> 'Z'
                    else -> character
                }
            }.joinToString("")

    fun romanizeIsolatedWord(text: String): String {
        if (text.isBlank()) return text
        val raw =
            when {
                text.any { it in '\u3040'..'\u30ff' } -> romanizeJapanese(text)
                text.any { it in '\u3400'..'\u9fff' } -> romanizeMandarin(text)
                text.any { it in '\uac00'..'\ud7a3' } -> romanizeHangul(text)
                text.any { it in '\u0600'..'\u06ff' } -> romanizeArabic(text)
                text.any { it in '\u0900'..'\u097f' } -> transliterate(text, devanagari)
                text.any { it in '\u0a00'..'\u0a7f' } -> transliterate(text, gurmukhi)
                text.any { it in '\u0400'..'\u04ff' } -> transliterate(text, cyrillic)
                text.any { it in '\u0e00'..'\u0e7f' } -> romanizeThai(text)
                else -> text
            }.ifBlank { text }
        return stripAccents(raw)
    }

    internal suspend fun prepareJapaneseRomanizationIfNeeded(
        fullText: String,
        enabledLanguages: Set<String>,
        prepare: suspend () -> Boolean = ::prepareJapaneseRomanizer,
    ): Boolean {
        val prefersJapaneseHan = prefersJapaneseHanReading(fullText, enabledLanguages)
        val needsDictionary =
            fullText.lineSequence().any { line ->
                usesJapaneseReading(line, enabledLanguages, prefersJapaneseHan) &&
                    (containsJapaneseKanji(line) || kanaRomanizationNeedsJapaneseDictionary)
            }
        if (!needsDictionary) {
            return false
        }
        return prepare()
    }

    private fun usesJapaneseReading(
        line: String,
        enabledLanguages: Set<String>,
        prefersJapaneseHan: Boolean,
    ): Boolean =
        "Japanese" in enabledLanguages &&
            (line.any { it in '\u3040'..'\u30ff' } || (containsJapaneseKanji(line) && prefersJapaneseHan))

    suspend fun romanize(
        fullText: String,
        line: String,
        enabledLanguages: Set<String>,
        cyrillicByLine: Boolean,
    ): String? =
        withContext(Dispatchers.Default) {
            val detectionText = if (cyrillicByLine) line else fullText
            val hasCjk = containsJapaneseKanji(line)
            val isJapanese = usesJapaneseReading(line, enabledLanguages, prefersJapaneseHanReading(fullText, enabledLanguages))
            val isChinese = !isJapanese && "Chinese" in enabledLanguages && hasCjk

            when {
                isJapanese -> {
                    romanizeJapanese(line)
                }

                isChinese -> {
                    romanizeMandarin(line)
                }

                "Korean" in enabledLanguages && line.any { it in '\uac00'..'\ud7a3' } -> {
                    romanizeHangul(line)
                }

                "Arabic" in enabledLanguages && line.any { it in '\u0600'..'\u06ff' } -> {
                    romanizeArabic(line)
                }

                "Hindi" in enabledLanguages && line.any { it in '\u0900'..'\u097f' } -> {
                    transliterate(line, devanagari)
                }

                "Punjabi" in enabledLanguages && line.any { it in '\u0a00'..'\u0a7f' } -> {
                    transliterate(line, gurmukhi)
                }

                "Thai" in enabledLanguages && line.any { it in '\u0e00'..'\u0e7f' } -> {
                    romanizeThai(line)
                }

                enabledLanguages.any { it in cyrillicLanguages } && detectionText.any { it in '\u0400'..'\u04ff' } -> {
                    transliterate(
                        line,
                        cyrillic,
                    )
                }

                else -> {
                    null
                }
            }?.takeIf { it.isNotBlank() && it != line }
        }

    suspend fun romanizeWords(
        fullText: String,
        line: String,
        words: List<WordTimestamp>,
        enabledLanguages: Set<String>,
        cyrillicByLine: Boolean,
    ): Pair<String, List<WordTimestamp>>? =
        withContext(Dispatchers.Default) {
            if (words.isEmpty()) return@withContext null
            val lineToProcess = words.joinToString("") { it.text + if (it.hasTrailingSpace) " " else "" }.trim().ifBlank { line }
            val detectionText = if (cyrillicByLine) lineToProcess else fullText
            val lineRoman = romanize(fullText, lineToProcess, enabledLanguages, cyrillicByLine) ?: return@withContext null

            val hasCjk = containsJapaneseKanji(lineToProcess)
            val isJapanese = usesJapaneseReading(lineToProcess, enabledLanguages, prefersJapaneseHanReading(fullText, enabledLanguages))
            val isChinese = !isJapanese && "Chinese" in enabledLanguages && hasCjk
            val isKorean = "Korean" in enabledLanguages && lineToProcess.any { it in '\uac00'..'\ud7a3' }
            val isArabic = "Arabic" in enabledLanguages && lineToProcess.any { it in '\u0600'..'\u06ff' }
            val isHindi = "Hindi" in enabledLanguages && lineToProcess.any { it in '\u0900'..'\u097f' }
            val isPunjabi = "Punjabi" in enabledLanguages && lineToProcess.any { it in '\u0a00'..'\u0a7f' }
            val isThai = "Thai" in enabledLanguages && lineToProcess.any { it in '\u0e00'..'\u0e7f' }
            val isCyrillic = enabledLanguages.any { it in cyrillicLanguages } && detectionText.any { it in '\u0400'..'\u04ff' }

            fun romanizeWord(text: String): String =
                when {
                    isJapanese -> romanizeJapanese(text)
                    isChinese -> romanizeMandarin(text)
                    isKorean -> romanizeHangul(text)
                    isArabic -> romanizeArabic(text)
                    isHindi -> transliterate(text, devanagari)
                    isPunjabi -> transliterate(text, gurmukhi)
                    isThai -> romanizeThai(text)
                    isCyrillic -> transliterate(text, cyrillic)
                    else -> text
                }.ifBlank { text }

            val isolatedGuides = words.map { romanizeWord(it.text) }.let { if (isJapanese) doubleSplitSokuon(words, it) else it }
            val aligned = alignRomanizationAnchors(lineRoman, isolatedGuides, words)
            val romanWords =
                words.mapIndexed { i, word ->
                    word.copy(text = aligned.getOrNull(i)?.takeIf { it.isNotBlank() } ?: isolatedGuides[i])
                }

            val wordJoinedText = romanWords.joinToString("") { it.text + if (it.hasTrailingSpace) " " else "" }.trim()
            val finalRomanText = if (wordJoinedText.isNotBlank()) wordJoinedText else lineRoman

            Pair(finalRomanText, romanWords)
        }

    // A small tsu split from its following syllable has no kana to double, so borrow the next guide's consonant.
    private fun doubleSplitSokuon(
        words: List<WordTimestamp>,
        guides: List<String>,
    ): List<String> =
        guides.mapIndexed { i, guide ->
            val endsWithSokuon =
                words[i]
                    .text
                    .trimEnd()
                    .lastOrNull()
                    ?.let { it == 'っ' || it == 'ッ' } == true
            val consonant = guides.getOrNull(i + 1)?.firstOrNull()?.takeIf { it in 'a'..'z' }
            if (endsWithSokuon && consonant != null) guide.filterNot { it == 'っ' || it == 'ッ' } + consonant else guide
        }

    private fun prefersJapaneseHanReading(
        fullText: String,
        enabledLanguages: Set<String>,
    ): Boolean {
        if ("Chinese" !in enabledLanguages) return true
        if ("Japanese" !in enabledLanguages) return false
        var japaneseLines = 0
        var hanOnlyLines = 0
        // shortcut: Infer language from script-bearing lines until providers expose language metadata.
        fullText.lineSequence().forEach { line ->
            when {
                line.any { it in '\u3040'..'\u30ff' } -> japaneseLines++
                line.any { it in '\u3400'..'\u9fff' } -> hanOnlyLines++
            }
        }
        return japaneseLines > 0 && japaneseLines >= hanOnlyLines
    }

    fun alignRomanizationAnchors(
        fullText: String,
        romanizedGuides: List<String>,
        words: List<WordTimestamp>,
    ): List<String> {
        val n = fullText.length
        val m = romanizedGuides.size

        if (m == 0) return emptyList()
        if (n == 0) return romanizedGuides.map { "" }
        if ((m + 1L) * (n + 1L) > MAX_ALIGNMENT_CELLS) return romanizedGuides

        val dp = Array(m + 1) { DoubleArray(n + 1) { Double.MAX_VALUE } }
        val path = Array(m + 1) { IntArray(n + 1) { -1 } }

        dp[0][0] = 0.0

        for (i in 1..m) {
            val guideRom = romanizedGuides[i - 1]
            val origWordText = words.getOrNull(i - 1)?.text.orEmpty()
            val isLatin = isPurelyLatinScript(origWordText)
            val guideLen = guideRom.length

            val minLen = if (isLatin) (guideLen - 1).coerceAtLeast(1) else 1
            val maxLen = if (isLatin) guideLen + 3 else (guideLen * 3 + 3).coerceAtLeast(15)

            for (j in 1..n) {
                var bestLocalCost = Double.MAX_VALUE
                var bestPrevJ = -1

                for (len in minLen..maxLen) {
                    val k = j - len
                    if (k < 0) break

                    if (dp[i - 1][k] == Double.MAX_VALUE) continue

                    val candidate = fullText.substring(k, j)
                    val segmentCost = calculateMatchCost(candidate, guideRom, isLatin)
                    val totalCost = dp[i - 1][k] + segmentCost

                    if (totalCost < bestLocalCost) {
                        bestLocalCost = totalCost
                        bestPrevJ = k
                    }
                }

                dp[i][j] = bestLocalCost
                path[i][j] = bestPrevJ
            }
        }

        val results = MutableList(m) { "" }
        var currJ = n

        if (dp[m][n] == Double.MAX_VALUE) {
            var minEndCost = Double.MAX_VALUE
            for (k in n downTo 0) {
                if (dp[m][k] < minEndCost) {
                    minEndCost = dp[m][k]
                    currJ = k
                }
            }
        }

        for (i in m downTo 1) {
            val prevJ = path[i][currJ]
            if (prevJ == -1) {
                results[i - 1] = ""
            } else {
                results[i - 1] = fullText.substring(prevJ, currJ)
                currJ = prevJ
            }
        }

        if (currJ > 0 && results.isNotEmpty()) {
            results[0] = fullText.substring(0, currJ) + results[0]
        }
        val totalLen = results.sumOf { it.length }
        if (totalLen < n && results.isNotEmpty()) {
            results[results.lastIndex] += fullText.substring(totalLen)
        }

        return results
    }

    private fun calculateMatchCost(
        candidate: String,
        guide: String,
        isLatin: Boolean,
    ): Double {
        val cTrim = candidate.trim()
        val gTrim = guide.trim()

        if (cTrim.isEmpty() && gTrim.isNotEmpty()) return 50.0

        val cLower = stripAccents(cTrim.lowercase())
        val gLower = stripAccents(gTrim.lowercase())

        if (isLatin) {
            val dist = levenshteinDistance(cLower, gLower).toDouble()
            return dist * 50.0 + (kotlin.math.abs(candidate.length - guide.length) * 0.5)
        }

        val gLatin = gLower.filter { char -> char.isWhitespace() || char in 'a'..'z' || char in '0'..'9' }
        val cjkCount = gLower.count { char -> char.code >= 0x3000 }
        val hasTrailingSpace = candidate.endsWith(" ")
        val bonus = if (hasTrailingSpace) -0.5 else 0.0

        if (cjkCount > 0) {
            val expectedLen = (gLatin.length + cjkCount * 2).coerceAtLeast(1)
            val lenDiff = kotlin.math.abs(cTrim.length - expectedLen)
            val latinDist =
                if (gLatin.isNotEmpty()) {
                    val cLatin = cLower.filter { char -> char.isWhitespace() || char in 'a'..'z' || char in '0'..'9' }
                    levenshteinDistance(cLatin, gLatin).toDouble()
                } else {
                    0.0
                }
            return latinDist * 5.0 + (lenDiff * 0.8) + bonus
        }

        val dist = levenshteinDistance(cLower, gLower).toDouble()
        val lenDiff = kotlin.math.abs(cTrim.length - gTrim.length)
        return dist + (lenDiff * 0.8) + bonus
    }

    private fun levenshteinDistance(
        s1: String,
        s2: String,
    ): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] =
                    minOf(
                        dp[i - 1][j] + 1,
                        dp[i][j - 1] + 1,
                        dp[i - 1][j - 1] + cost,
                    )
            }
        }
        return dp[m][n]
    }

    fun isPurelyLatinScript(text: String): Boolean {
        if (text.isBlank()) return true
        return text.all { char ->
            char.isWhitespace() || char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
        }
    }

    private val cyrillicLanguages = setOf("Russian", "Ukrainian", "Serbian", "Bulgarian", "Belarusian", "Kyrgyz", "Macedonian")

    private fun transliterate(
        text: String,
        map: Map<Char, String>,
    ): String =
        buildString(text.length) {
            for (char in text) append(map[char] ?: char)
        }.replace(Regex("\\s+([,.!?;:])"), "$1")

    fun romanizeKana(text: String): String {
        val normalized =
            text
                .map { char ->
                    if (char in 'ァ'..'ン') (char.code - 0x60).toChar() else char
                }.joinToString("")
        return buildString(normalized.length * 2) {
            var index = 0
            while (index < normalized.length) {
                if (normalized[index] == 'ー') {
                    val lastVowel = lastOrNull()?.lowercaseChar()?.takeIf { it in "aeiou" }
                    if (lastVowel != null) append(lastVowel)
                    index++
                    continue
                }
                if (normalized[index] == 'っ') {
                    val next =
                        normalized
                            .getOrNull(index + 1)
                            ?.toString()
                            ?.let { kana[it] }
                            ?.firstOrNull()
                    if (next != null) append(next)
                    index++
                    continue
                }
                val two = normalized.substring(index, (index + 2).coerceAtMost(normalized.length))
                val mappedTwo = kana[two]
                if (mappedTwo != null) {
                    append(mappedTwo)
                    index += 2
                } else {
                    append(kana[normalized[index].toString()] ?: normalized[index])
                    index++
                }
            }
        }
    }

    private fun romanizeHangul(text: String): String {
        val initial = arrayOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
        val medial =
            arrayOf(
                "a",
                "ae",
                "ya",
                "yae",
                "eo",
                "e",
                "yeo",
                "ye",
                "o",
                "wa",
                "wae",
                "oe",
                "yo",
                "u",
                "wo",
                "we",
                "wi",
                "yu",
                "eu",
                "ui",
                "i",
            )
        val final =
            arrayOf(
                "",
                "k",
                "k",
                "ks",
                "n",
                "nj",
                "nh",
                "t",
                "l",
                "lk",
                "lm",
                "lb",
                "ls",
                "lt",
                "lp",
                "lh",
                "m",
                "p",
                "ps",
                "t",
                "t",
                "ng",
                "t",
                "t",
                "k",
                "t",
                "p",
                "h",
            )
        return buildString(text.length * 2) {
            for (char in text) {
                if (char in '\uac00'..'\ud7a3') {
                    val code = char.code - 0xac00
                    append(initial[code / (21 * 28)])
                    append(medial[(code % (21 * 28)) / 28])
                    append(final[code % 28])
                } else {
                    append(char)
                }
            }
        }
    }
}
