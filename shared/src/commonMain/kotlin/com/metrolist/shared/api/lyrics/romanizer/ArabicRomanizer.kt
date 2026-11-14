/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics.romanizer

private const val MAX_PREFIX_DEPTH = 64

private val sunLetters = setOf('ت', 'ث', 'د', 'ذ', 'ر', 'ز', 'س', 'ش', 'ص', 'ض', 'ط', 'ظ', 'ل', 'ن')

private val sunLetterRoman =
    mapOf(
        'ت' to "t",
        'ث' to "th",
        'د' to "d",
        'ذ' to "dh",
        'ر' to "r",
        'ز' to "z",
        'س' to "s",
        'ش' to "sh",
        'ص' to "s",
        'ض' to "d",
        'ط' to "t",
        'ظ' to "z",
        'ل' to "l",
        'ن' to "n",
    )

private val consonantRoman =
    mapOf(
        'ب' to "b",
        'ت' to "t",
        'ث' to "th",
        'ج' to "j",
        'ح' to "h",
        'خ' to "kh",
        'د' to "d",
        'ذ' to "dh",
        'ر' to "r",
        'ز' to "z",
        'س' to "s",
        'ش' to "sh",
        'ص' to "s",
        'ض' to "d",
        'ط' to "t",
        'ظ' to "z",
        'غ' to "gh",
        'ف' to "f",
        'ق' to "q",
        'ك' to "k",
        'ل' to "l",
        'م' to "m",
        'ن' to "n",
        'ه' to "h",
        'پ' to "p",
        'چ' to "ch",
        'ژ' to "zh",
        'گ' to "g",
        'ڤ' to "v",
    )

private val arabicIndicDigits =
    mapOf(
        '٠' to '0',
        '١' to '1',
        '٢' to '2',
        '٣' to '3',
        '٤' to '4',
        '٥' to '5',
        '٦' to '6',
        '٧' to '7',
        '٨' to '8',
        '٩' to '9',
        '۰' to '0',
        '۱' to '1',
        '۲' to '2',
        '۳' to '3',
        '۴' to '4',
        '۵' to '5',
        '۶' to '6',
        '۷' to '7',
        '۸' to '8',
        '۹' to '9',
    )

private val nounPronounSuffixes =
    listOf(
        "هما" to "huma",
        "كما" to "kuma",
        "كن" to "kunna",
        "هن" to "hunna",
        "هم" to "hum",
        "كم" to "kum",
        "نا" to "na",
        "ها" to "ha",
        "ني" to "ni",
        "ي" to "i",
        "ك" to "ak",
        "ه" to "uh",
    )

private val fixedEndings =
    listOf(
        "ية" to "iyyah",
        "ين" to "in",
        "ون" to "un",
        "ات" to "at",
        "تي" to "ti",
        "ها" to "ha",
    )

private val commonWords: Map<String, String> =
    buildMap {
        putAll(
            mapOf(
                "يا" to "ya",
                "أنا" to "ana",
                "انا" to "ana",
                "أنت" to "anta",
                "انت" to "anta",
                "إنت" to "inta",
                "انتي" to "inti",
                "إنتي" to "inti",
                "هو" to "huwa",
                "هي" to "hiya",
                "نحن" to "nahnu",
                "إحنا" to "ihna",
                "احنا" to "ihna",
                "أنتم" to "antum",
                "انتم" to "antum",
                "هم" to "hum",
                "هن" to "hunna",
                "ده" to "da",
                "دي" to "di",
                "دول" to "dol",
                "هذا" to "hadha",
                "هذه" to "hadhihi",
                "ذلك" to "dhalika",
                "تلك" to "tilka",
                "التي" to "allati",
                "الذي" to "alladhi",
                "الذين" to "alladhina",
            ),
        )
        putAll(
            mapOf(
                "في" to "fi",
                "من" to "min",
                "على" to "ala",
                "عالسريع" to "ala-al-sari",
                "إلى" to "ila",
                "الى" to "ila",
                "عن" to "an",
                "مع" to "maa",
                "بعد" to "baad",
                "قبل" to "qabl",
                "حتى" to "hatta",
                "هنا" to "huna",
                "هناك" to "hunak",
                "مش" to "mush",
                "مو" to "mu",
                "شو" to "shu",
                "ايه" to "eh",
                "إيه" to "eh",
                "ليه" to "leh",
                "وين" to "wen",
                "فين" to "fen",
                "امتى" to "emta",
                "متى" to "mata",
                "ما" to "ma",
                "لا" to "la",
                "لأ" to "la",
                "لو" to "law",
                "لم" to "lam",
                "لن" to "lan",
                "إن" to "in",
                "أن" to "an",
                "ان" to "an",
                "إذا" to "idha",
                "لكن" to "lakin",
                "و" to "wa",
                "أو" to "aw",
                "او" to "aw",
                "ثم" to "thumma",
                "كل" to "kull",
                "السلام" to "as-salam",
                "عليكم" to "alaykum",
                "كمان" to "kaman",
                "برضو" to "bardo",
                "برضه" to "bardoh",
                "عشان" to "ashan",
                "علشان" to "alashan",
                "علي" to "alayya",
                "معايا" to "maaya",
                "معاك" to "maak",
                "معاكي" to "maaki",
                "معانا" to "maana",
                "فيش" to "fish",
            ),
        )
        putAll(
            mapOf(
                "حب" to "hubb",
                "حبك" to "hubbak",
                "حبي" to "hubbi",
                "حبيبي" to "habibi",
                "حبيبتي" to "habibati",
                "حبيبة" to "habiba",
                "احبك" to "uhibbuk",
                "أحبك" to "uhibbuk",
                "بحبك" to "bahebbak",
                "بحبك" to "bahebbik",
                "بحبها" to "bahebbaha",
                "بحبه" to "bahebbo",
                "بيحبك" to "biyhebbak",
                "وحشتني" to "wahashtini",
                "اشتقتلك" to "ishtaqtilak",
                "اشتقتلك" to "ishtaqtilak",
                "شوقي" to "shawqi",
                "شوق" to "shawq",
                "قلبي" to "qalbi",
                "قلبك" to "qalbak",
                "قلب" to "qalb",
                "عمري" to "omri",
                "عيوني" to "uyuni",
                "عيني" to "ayni",
                "عيون" to "uyun",
                "عين" to "ayn",
                "روح" to "ruh",
                "روحي" to "ruhi",
                "حياتي" to "hayati",
                "حياة" to "hayat",
                "مدينة" to "madinat",
                "نور عيني" to "nur ayni",
                "نور" to "nur",
                "ناري" to "nari",
                "أميرة" to "amira",
                "اميرة" to "amira",
                "ملكة" to "malika",
                "غالي" to "ghali",
                "غالية" to "ghaliya",
                "بعيد" to "baid",
                "بعيدة" to "baida",
                "قريب" to "qarib",
                "قريبة" to "qariba",
                "وداع" to "wadaa",
                "فراق" to "firaq",
                "لقاء" to "liqaa",
                "خيانة" to "khiyana",
                "غيرة" to "ghira",
                "حنين" to "hanin",
            ),
        )
        putAll(
            mapOf(
                "حزن" to "huzn",
                "حزين" to "hazin",
                "فرح" to "farah",
                "فرحان" to "farhan",
                "سعادة" to "saada",
                "سعيد" to "saeed",
                "أمل" to "amal",
                "امل" to "amal",
                "خوف" to "khawf",
                "خايف" to "khayef",
                "غضب" to "ghadab",
                "زعلان" to "zaalan",
                "زعل" to "zaal",
                "مبسوط" to "mabsut",
                "تعبان" to "taaban",
                "وحيد" to "waheed",
                "وحدي" to "wahdi",
                "دموع" to "dumu",
                "دمعة" to "damaa",
                "ابتسامة" to "ibtisama",
            ),
        )
        putAll(
            mapOf(
                "الليل" to "al-layl",
                "ليل" to "layl",
                "ليلة" to "layla",
                "نهار" to "nahar",
                "يوم" to "yawm",
                "أيام" to "ayyam",
                "ايام" to "ayyam",
                "زمان" to "zaman",
                "دلوقتي" to "dilwa-ti",
                "دلوقت" to "dilwa-t",
                "بكرة" to "bukra",
                "امبارح" to "embareh",
                "صباح" to "sabah",
                "مساء" to "massa",
                "الخير" to "al-khayr",
                "شمس" to "shams",
                "قمر" to "qamar",
                "نجوم" to "nujum",
                "نجمة" to "najma",
                "بحر" to "bahr",
                "سماء" to "sama",
                "دنيا" to "dunya",
                "عالم" to "aalam",
            ),
        )
        putAll(
            mapOf(
                "كان" to "kan",
                "كانت" to "kanat",
                "يكون" to "yakun",
                "بدي" to "biddi",
                "عايز" to "ayez",
                "عايزة" to "ayza",
                "عاوز" to "aawez",
                "أريد" to "urid",
                "اريد" to "urid",
                "تعال" to "taaal",
                "تعالي" to "taaali",
                "خليك" to "khallik",
                "خليني" to "khallini",
                "سيبني" to "sibni",
                "سيبيني" to "sibini",
                "روح" to "ruh",
                "تروح" to "tiruh",
                "تعرف" to "taaraf",
                "أعرف" to "aaraf",
                "اعرف" to "aaraf",
                "بعرف" to "baaraf",
                "قلت" to "qult",
                "قال" to "qal",
                "تقول" to "taqul",
                "تعمل" to "ta'mal",
                "بقول" to "ba-ul",
                "شفت" to "shuft",
                "أشوف" to "ashuf",
                "اشوف" to "ashuf",
                "بشوفك" to "bashufak",
                "خايف" to "khayef",
                "ناسيني" to "nasini",
                "نسيت" to "nasit",
                "افتكرتك" to "iftakartak",
                "فاكرة" to "fakra",
                "فاكر" to "faker",
                "جيت" to "gayt",
                "جاي" to "gay",
                "جاية" to "gaya",
                "رايح" to "rayeh",
                "رايحة" to "rayha",
            ),
        )
        putAll(
            mapOf(
                "مرحبا" to "marhaban",
                "أهلا" to "ahlan",
                "اهلا" to "ahlan",
                "شكرا" to "shukran",
                "كيف" to "kayf",
                "كيفك" to "kifak",
                "حالك" to "halak",
                "حال" to "hal",
                "الله" to "allah",
                "والله" to "wallah",
                "يالله" to "yalla",
                "يلا" to "yalla",
                "ماشاء الله" to "masha-allah",
                "إن شاء الله" to "in-sha-allah",
                "شاء" to "sha",
                "عبد" to "abd",
            ),
        )
        putAll(
            mapOf(
                "جميل" to "jamil",
                "جميلة" to "jamila",
                "كبير" to "kabir",
                "صغير" to "saghir",
                "طريق" to "tariq",
                "وطن" to "watan",
                "بلد" to "balad",
                "كثيرا" to "kathiran",
                "كتير" to "ktir",
                "جدا" to "jiddan",
                "أوي" to "awi",
                "اوي" to "awi",
                "حلو" to "hilw",
                "حلوة" to "hilwa",
                "أول" to "awwal",
                "اول" to "awwal",
                "آخر" to "akhir",
                "تاني" to "tani",
                "بس" to "bas",
                "خلاص" to "khalas",
                "مكتوب" to "maktub",
                "كاتب" to "katib",
                "قلوب" to "qulub",
                "مشكلة" to "mushkilah",
                "إزيك" to "ezzayyak",
                "محمد" to "Muhammad",
                "أحمد" to "Ahmad",
                "وأحمد" to "wa-Ahmad",
                "علي" to "Ali",
            ),
        )
    }

private val diacriticSet =
    setOf('\u064e', '\u064f', '\u0650', '\u064b', '\u064c', '\u064d', '\u0652', '\u0651', '\u0670')
private val vowelMarkSet =
    setOf('\u064e', '\u064f', '\u0650', '\u064b', '\u064c', '\u064d', '\u0670')

private fun vowelSound(mark: Char?): String =
    when (mark) {
        '\u064e' -> "a"
        '\u064f' -> "u"
        '\u0650' -> "i"
        '\u064b' -> "an"
        '\u064c' -> "un"
        '\u064d' -> "in"
        '\u0670' -> "aa"
        else -> ""
    }

private val nonPrefixedExceptions =
    setOf(
        "واجب",
        "واجبة",
        "واحد",
        "واحدة",
        "واسع",
        "واسعة",
        "وارد",
        "واردة",
        "وادي",
        "والد",
        "والدة",
        "والي",
        "وادع",
        "واقع",
        "واثق",
        "واضح",
        "باسم",
        "بارك",
        "بارد",
        "باردة",
        "بسيط",
        "بسيطة",
        "بعيد",
        "بعيدة",
        "بديع",
        "بديل",
        "باقي",
        "بيت",
        "بحر",
        "بشر",
        "بشرى",
        "فارس",
        "فاتح",
        "فاخر",
        "فائق",
        "فريد",
        "فرح",
        "فرحان",
        "كامل",
        "كاملة",
        "كبير",
        "كبيرة",
        "كريم",
        "كريمة",
        "كثير",
        "كثيرة",
    )
private val multiSpaceRegex = Regex(" {2,}")
private val spaceBeforePunctRegex = Regex("\\s+([,.!?;:])")
private val punctFollowedByAlnumRegex = Regex("([,.!?;:])(?=[a-zA-Z0-9])")
private val tatweelRegex = Regex("[\u0640]")

private fun Char?.isArabicVowelOrAlif(): Boolean = this != null && this in "اأإآٱ "

private fun Char?.isConsonant(): Boolean = this != null && this in consonantRoman

private fun isArabicChar(c: Char): Boolean = c in '\u0600'..'\u06ff' || c in '\u0750'..'\u077f' || c in '\u08a0'..'\u08ff'

internal fun romanizeArabic(text: String): String {
    if (text.isBlank()) return text

    val normalized =
        text
            .replace(tatweelRegex, "")
            .map { arabicIndicDigits[it] ?: it }
            .joinToString("")

    val tokens = mutableListOf<String>()
    val sb = StringBuilder()
    var currentIsArabic: Boolean? = null

    for (ch in normalized) {
        val isAr = isArabicChar(ch)
        when {
            currentIsArabic == null -> {
                currentIsArabic = isAr
                sb.append(ch)
            }

            currentIsArabic == isAr -> {
                sb.append(ch)
            }

            else -> {
                tokens.add(sb.toString())
                sb.clear()
                currentIsArabic = isAr
                sb.append(ch)
            }
        }
    }
    if (sb.isNotEmpty()) tokens.add(sb.toString())

    val result = StringBuilder()
    for (i in tokens.indices) {
        val token = tokens[i]
        if (token.any(::isArabicChar)) {
            val hasNextWord = (i + 1 until tokens.size).any { idx -> tokens[idx].any(::isArabicChar) }
            result.append(romanizeArabicWord(token, hasNextWord))
        } else {
            result.append(token)
        }
    }

    return result
        .toString()
        .replace(multiSpaceRegex, " ")
        .replace(spaceBeforePunctRegex, "$1")
        .replace(punctFollowedByAlnumRegex, "$1 ")
        .trim()
}

private fun romanizeArabicWord(
    word: String,
    hasNextWord: Boolean,
    depth: Int = 0,
): String {
    if (word.isBlank()) return word

    val cleanWord = word.replace("ـ", "")
    if (depth >= MAX_PREFIX_DEPTH) return romanizeArabicWordBase(cleanWord, hasNextWord)
    val unvoweled = cleanWord.filter { it !in '\u064b'..'\u0652' && it != '\u0670' }

    if (unvoweled == "الله") return "allah"

    commonWords[unvoweled]?.let { if (cleanWord == unvoweled) return it }

    checkArticleAndPrepositions(cleanWord)?.let { (prefixRom, remainder) ->
        val remainderRom = if (remainder.isNotEmpty()) romanizeArabicWord(remainder, hasNextWord, depth + 1) else ""
        return prefixRom + remainderRom
    }

    checkSinglePrepositionPrefix(cleanWord, hasNextWord, depth)?.let { return it }

    checkPronounSuffix(unvoweled, cleanWord)?.let { return it }

    if (unvoweled == cleanWord) {
        checkFixedEnding(unvoweled)?.let { return it }
    }

    return romanizeArabicWordBase(cleanWord, hasNextWord)
}

private fun checkArticleAndPrepositions(cleanWord: String): Pair<String, String>? {
    val prefixes =
        listOf(
            "بال" to "bi",
            "فال" to "fa",
            "كال" to "ka",
            "لل" to "li",
            "وال" to "wa",
            "ال" to "",
        )

    for ((pref, prepRom) in prefixes) {
        if (cleanWord.startsWith(pref) && cleanWord.length > pref.length) {
            var remainder = cleanWord.substring(pref.length)
            val idx = remainder.indexOfFirst { it in '\u0621'..'\u064a' }
            if (idx == -1) continue
            val firstChar = remainder[idx]
            remainder = remainder.substring(idx)
            val isSun = firstChar in sunLetters

            val artRom: String
            if (isSun) {
                val sunRom = sunLetterRoman[firstChar] ?: "l"
                artRom = if (prepRom.isNotEmpty()) "${prepRom}$sunRom-" else "a$sunRom-"
                var k = 1
                while (k < remainder.length && remainder[k] in diacriticSet) k++
                val diacRun = remainder.substring(1, k).filter { it != '\u0651' }
                remainder = remainder[0] + diacRun + remainder.substring(k)
            } else {
                artRom =
                    if (prepRom.isNotEmpty()) {
                        if (pref == "لل") "lil-" else "${prepRom}l-"
                    } else {
                        "al-"
                    }
            }
            return artRom to remainder
        }
    }
    return null
}

private fun checkSinglePrepositionPrefix(
    cleanWord: String,
    hasNextWord: Boolean,
    depth: Int,
): String? {
    val lettersOnly = cleanWord.filter { it !in diacriticSet }
    if (lettersOnly.length <= 3) return null
    if (nonPrefixedExceptions.any { lettersOnly.startsWith(it) }) return null

    val first = cleanWord.first()
    val prefRom =
        when (first) {
            'و' -> "wa-"
            'ف' -> "fa-"
            'ب' -> "bi-"
            'ك' -> "ka-"
            else -> return null
        }
    var remainder = cleanWord.substring(1)
    remainder = remainder.dropWhile { it in diacriticSet }
    val unvoweledRemainder = remainder.filter { it !in '\u064b'..'\u0652' && it != '\u0670' }

    commonWords[unvoweledRemainder]?.let { dictHit ->
        return prefRom.removeSuffix("-") + dictHit
    }

    return prefRom + romanizeArabicWord(remainder, hasNextWord, depth + 1)
}

private fun checkPronounSuffix(
    unvoweled: String,
    cleanWord: String,
): String? {
    if (unvoweled != cleanWord) return null
    if (unvoweled.length < 4) return null

    for ((suffix, suffixRom) in nounPronounSuffixes) {
        if (unvoweled.length <= suffix.length + 1) continue
        if (!unvoweled.endsWith(suffix)) continue
        val base = unvoweled.removeSuffix(suffix)

        commonWords[base]?.let { baseRom ->
            return baseRom + suffixRom
        }
        if (base.length >= 2) {
            val baseRom = romanizeArabicWordBase(base, true)
            return baseRom + suffixRom
        }
    }
    return null
}

private fun checkFixedEnding(unvoweled: String): String? {
    for ((ending, endingRom) in fixedEndings) {
        if (unvoweled.length <= ending.length + 1) continue
        if (!unvoweled.endsWith(ending)) continue
        val base = unvoweled.removeSuffix(ending)
        val baseRom = commonWords[base] ?: romanizeArabicWordBase(base, true)
        return baseRom + endingRom
    }
    return null
}

private fun romanizeArabicWordBase(
    word: String,
    hasNextWord: Boolean,
): String {
    val isVoweled = word.any { it in '\u064b'..'\u0652' || it == '\u0670' }
    if (!isVoweled) {
        commonWords[word]?.let { return it }
        return romanizeUnvoweledWord(word, hasNextWord)
    }
    return romanizeVoweledWord(word, hasNextWord)
}

private fun romanizeVoweledWord(
    word: String,
    hasNextWord: Boolean,
): String {
    val sb = StringBuilder()
    var i = 0
    val n = word.length
    var prevLetter: Char? = null
    var prevVowelMark: Char? = null

    while (i < n) {
        val ch = word[i]
        if (ch in diacriticSet) {
            i++
            continue
        }

        var j = i + 1
        var hasShadda = false
        var vowelMark: Char? = null
        while (j < n && word[j] in diacriticSet) {
            if (word[j] == '\u0651') {
                hasShadda = true
            } else if (word[j] in vowelMarkSet) {
                vowelMark = word[j]
            }
            j++
        }
        val nextLetter = word.getOrNull(j)
        val prev = prevLetter

        val charRom =
            when (ch) {
                'ا', 'أ', 'إ', 'آ', 'ٱ' -> {
                    when {
                        ch == 'آ' -> {
                            "aa"
                        }

                        i == 0 -> {
                            when {
                                vowelMark != null -> ""
                                ch == 'إ' -> "i"
                                else -> "a"
                            }
                        }

                        else -> {
                            when (prevVowelMark) {
                                '\u064b' -> ""
                                '\u064e' -> "a"
                                else -> "aa"
                            }
                        }
                    }
                }

                'ع' -> {
                    if (i == 0 && vowelMark != null) {
                        ""
                    } else if (i == 0) {
                        "a"
                    } else {
                        "'"
                    }
                }

                'و' -> {
                    when {
                        i == 0 || prev.isArabicVowelOrAlif() -> "w"
                        prevVowelMark == '\u064f' -> "u"
                        prevVowelMark == '\u064e' -> "w"
                        nextLetter == null || nextLetter.isArabicVowelOrAlif() -> "u"
                        else -> "w"
                    }
                }

                'ي' -> {
                    when {
                        i == 0 || prev == null -> "y"
                        vowelMark != null -> "y"
                        else -> "i"
                    }
                }

                'ى' -> {
                    "a"
                }

                'ة' -> {
                    if (hasNextWord) "t" else "h"
                }

                'ء', 'ؤ', 'ئ' -> {
                    "'"
                }

                else -> {
                    consonantRoman[ch] ?: ch.toString()
                }
            }

        sb.append(charRom)
        if (hasShadda && charRom.isNotEmpty() && charRom != "'") {
            sb.append(charRom)
        }
        sb.append(vowelSound(vowelMark))

        prevLetter = ch
        prevVowelMark = vowelMark
        i = j
    }

    return sb.toString()
}

private fun romanizeUnvoweledWord(
    word: String,
    hasNextWord: Boolean,
): String {
    val sb = StringBuilder()
    val n = word.length
    var i = 0

    while (i < n) {
        val ch = word[i]
        val next = word.getOrNull(i + 1)

        if ((ch == 'ا' || ch == 'أ') && next == 'و' && i + 2 <= n) {
            val afterDiphthong = word.getOrNull(i + 2)
            if (afterDiphthong == null || afterDiphthong.isConsonant() || afterDiphthong.isArabicVowelOrAlif().not()) {
                sb.append("aw")
                i += 2
                continue
            }
        }
        if ((ch == 'ا' || ch == 'أ') && next == 'ي' && i + 2 <= n) {
            val afterDiphthong = word.getOrNull(i + 2)
            if (afterDiphthong == null || afterDiphthong.isConsonant()) {
                sb.append("ay")
                i += 2
                continue
            }
        }

        val prevChar = word.getOrNull(i - 1)
        val nextChar = word.getOrNull(i + 1)
        val thirdChar = word.getOrNull(i + 2)

        val charRom =
            when (ch) {
                'ا', 'أ', 'إ', 'آ', 'ٱ' -> {
                    when {
                        ch == 'آ' -> "aa"
                        ch == 'إ' -> "i"
                        else -> "a"
                    }
                }

                'ع' -> {
                    if (i == 0) "a" else "'"
                }

                'و' -> {
                    when {
                        i == 0 || prevChar.isArabicVowelOrAlif() -> "w"
                        nextChar == null || nextChar.isArabicVowelOrAlif() -> "u"
                        else -> "w"
                    }
                }

                'ي' -> {
                    when {
                        i == 0 || prevChar.isArabicVowelOrAlif() -> "y"
                        nextChar == null || nextChar.isArabicVowelOrAlif() -> "i"
                        else -> "y"
                    }
                }

                'ى' -> {
                    "a"
                }

                'ة' -> {
                    if (hasNextWord) "t" else "h"
                }

                'ء', 'ؤ', 'ئ' -> {
                    "'"
                }

                else -> {
                    consonantRoman[ch] ?: ch.toString()
                }
            }

        sb.append(charRom)

        val isCurrentConsonant = ch.isConsonant()
        val isNextConsonant = nextChar.isConsonant()
        val isThirdConsonantOrEnd = thirdChar == null || thirdChar.isConsonant()

        if (isCurrentConsonant && isNextConsonant && isThirdConsonantOrEnd && i < n - 1) {
            sb.append("a")
        }
        i++
    }

    return sb.toString()
}
