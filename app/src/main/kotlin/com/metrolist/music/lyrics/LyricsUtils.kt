/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.text.format.DateUtils
import com.atilika.kuromoji.ipadic.Tokenizer
import com.github.promeg.pinyinhelper.Pinyin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Suppress("RegExpRedundantEscape")
object LyricsUtils {
    val LINE_REGEX = "((\\[\\d\\d:\\d\\d\\.\\d{2,3}\\] ?)+)(.+)".toRegex()
    val TIME_REGEX = "\\[(\\d\\d):(\\d\\d)\\.(\\d{2,3})\\]".toRegex()
    
    // Regex for rich sync format: [MM:SS.mm]<MM:SS.mm> word <MM:SS.mm> word ...
    private val RICH_SYNC_LINE_REGEX = "\\[(\\d{1,2}):(\\d{2})\\.(\\d{2,3})\\](.+)".toRegex()
    private val RICH_SYNC_WORD_REGEX = "<(\\d{1,2}):(\\d{2})\\.(\\d{2,3})>\\s*([^<]+)".toRegex()
    
    // Regex for agent and background markers
    private val AGENT_REGEX = "\\{agent:([^}]+)\\}".toRegex()
    private val BACKGROUND_REGEX = "^\\{bg\\}".toRegex()

    private val KANA_ROMAJI_MAP: Map<String, String> = mapOf(
        // Digraphs (Yōon - combinations like kya, sho)
        "キャ" to "kya", "キュ" to "kyu", "キョ" to "kyo",
        "シャ" to "sha", "シュ" to "shu", "ショ" to "sho",
        "チャ" to "cha", "チュ" to "chu", "チョ" to "cho",
        "ニャ" to "nya", "ニュ" to "nyu", "ニョ" to "nyo",
        "ヒャ" to "hya", "ヒュ" to "hyu", "ヒョ" to "hyo",
        "ミャ" to "mya", "ミュ" to "myu", "ミョ" to "myo",
        "リャ" to "rya", "リュ" to "ryu", "リョ" to "ryo",
        "ギャ" to "gya", "ギュ" to "gyu", "ギョ" to "gyo",
        "ジャ" to "ja", "ジュ" to "ju", "ジョ" to "jo",
        "ヂャ" to "ja", "ヂュ" to "ju", "ヂョ" to "jo",
        "ビャ" to "bya", "ビュ" to "byu", "ビョ" to "byo",
        "ピャ" to "pya", "ピュ" to "pyu", "ピョ" to "pyo",
        // Basic Katakana Characters
        "ア" to "a", "イ" to "i", "ウ" to "u", "エ" to "e", "オ" to "o",
        "カ" to "ka", "キ" to "ki", "ク" to "ku", "ケ" to "ke", "コ" to "ko",
        "サ" to "sa", "シ" to "shi", "ス" to "su", "セ" to "se", "ソ" to "so",
        "タ" to "ta", "チ" to "chi", "ツ" to "tsu", "テ" to "te", "ト" to "to",
        "ナ" to "na", "ニ" to "ni", "ヌ" to "nu", "ネ" to "ne", "ノ" to "no",
        "ハ" to "ha", "ヒ" to "hi", "フ" to "fu", "ヘ" to "he", "ホ" to "ho",
        "マ" to "ma", "ミ" to "mi", "ム" to "mu", "メ" to "me", "モ" to "mo",
        "ヤ" to "ya", "ユ" to "yu", "ヨ" to "yo",
        "ラ" to "ra", "リ" to "ri", "ル" to "ru", "レ" to "re", "ロ" to "ro",
        "ワ" to "wa", "ヲ" to "o", "ン" to "n",
        // Dakuten (voiced consonants)
        "ガ" to "ga", "ギ" to "gi", "グ" to "gu", "ゲ" to "ge", "ゴ" to "go",
        "ザ" to "za", "ジ" to "ji", "ズ" to "zu", "ゼ" to "ze", "ゾ" to "zo",
        "ダ" to "da", "ヂ" to "ji", "ヅ" to "zu", "デ" to "de", "ド" to "do",
        // Handakuten (p-sounds for 'h' group)
        "バ" to "ba", "ビ" to "bi", "ブ" to "bu", "ベ" to "be", "ボ" to "bo",
        "パ" to "pa", "ピ" to "pi", "プ" to "pu", "ペ" to "pe", "ポ" to "po",
        // Chōonpu (long vowel mark)
        "ー" to ""
    )

    private val HANGUL_ROMAJA_MAP: Map<String, Map<String, String>> = mapOf(
        "cho" to mapOf(
            "ᄀ" to "g", "ᄁ" to "kk", "ᄂ" to "n", "ᄃ" to "d",
            "ᄄ" to "tt", "ᄅ" to "r", "ᄆ" to "m", "ᄇ" to "b",
            "ᄈ" to "pp", "ᄉ" to "s", "ᄊ" to "ss", "ᄋ" to "",
            "ᄌ" to "j", "ᄍ" to "jj", "ᄎ" to "ch", "ᄏ" to "k",
            "ᄐ" to "t", "ᄑ" to "p", "ᄒ" to "h"
        ),
        "jung" to mapOf(
            "ᅡ" to "a", "ᅢ" to "ae", "ᅣ" to "ya", "ᅤ" to "yae",
            "ᅥ" to "eo", "ᅦ" to "e", "ᅧ" to "yeo", "ᅨ" to "ye",
            "ᅩ" to "o", "ᅪ" to "wa", "ᅫ" to "wae", "ᅬ" to "oe",
            "ᅭ" to "yo", "ᅮ" to "u", "ᅯ" to "wo", "ᅰ" to "we",
            "ᅱ" to "wi", "ᅲ" to "yu", "ᅳ" to "eu", "ᅴ" to "eui",
            "ᅵ" to "i"
        ),
        "jong" to mapOf(
            "ᆨ" to "k", "ᆨᄋ" to "g", "ᆨᄂ" to "ngn", "ᆨᄅ" to "ngn", "ᆨᄆ" to "ngm", "ᆨᄒ" to "kh",
            "ᆩ" to "kk", "ᆩᄋ" to "kg", "ᆩᄂ" to "ngn", "ᆩᄅ" to "ngn", "ᆩᄆ" to "ngm", "ᆩᄒ" to "kh",
            "ᆪ" to "k", "ᆪᄋ" to "ks", "ᆪᄂ" to "ngn", "ᆪᄅ" to "ngn", "ᆪᄆ" to "ngm", "ᆪᄒ" to "kch",
            "ᆫ" to "n", "ᆫᄅ" to "ll", "ᆬ" to "n", "ᆬᄋ" to "nj", "ᆬᄂ" to "nn", "ᆬᄅ" to "nn",
            "ᆬᄆ" to "nm", "ᆬㅎ" to "nch", "ᆭ" to "n", "ᆭᄋ" to "nh", "ᆭᄅ" to "nn", "ᆮ" to "t",
            "ᆮᄋ" to "d", "ᆮᄂ" to "nn", "ᆮᄅ" to "nn", "ᆮᄆ" to "nm", "ᆮᄒ" to "th", "ᆯ" to "l",
            "ᆯᄋ" to "r", "ᆯᄂ" to "ll", "ᆯᄅ" to "ll", "ᆰ" to "k", "ᆰᄋ" to "lg", "ᆰᄂ" to "ngn",
            "ᆰᄅ" to "ngn", "ᆰᄆ" to "ngm", "ᆰᄒ" to "lkh", "ᆱ" to "m", "ᆱᄋ" to "lm", "ᆱᄂ" to "mn",
            "ᆱᄅ" to "mn", "ᆱᄆ" to "mm", "ᆱᄒ" to "lmh", "ᆲ" to "p", "ᆲᄋ" to "lb", "ᆲᄂ" to "mn",
            "ᆲᄅ" to "mn", "ᆲᄆ" to "mm", "ᆲᄒ" to "lph", "ᆳ" to "t", "ᆳᄋ" to "ls", "ᆳᄂ" to "nn",
            "ᆳᄅ" to "nn", "ᆳᄆ" to "nm", "ᆳᄒ" to "lsh", "ᆴ" to "t", "ᆴᄋ" to "lt", "ᆴᄂ" to "nn",
            "ᆴᄅ" to "nn", "ᆴᄆ" to "nm", "ᆴᄒ" to "lth", "ᆵ" to "p", "ᆵᄋ" to "lp", "ᆵᄂ" to "mn",
            "ᆵᄅ" to "mn", "ᆵᄆ" to "mm", "ᆵᄒ" to "lph", "ᆶ" to "l", "ᆶᄋ" to "lh", "ᆶᄂ" to "ll",
            "ᆶᄅ" to "ll", "ᆶᄆ" to "lm", "ᆶᄒ" to "lh", "ᆷ" to "m", "ᆷᄅ" to "mn", "ᆸ" to "p",
            "ᆸᄋ" to "b", "ᆸᄂ" to "mn", "ᆸᄅ" to "mn", "ᆸᄆ" to "mm", "ᆸᄒ" to "ph", "ᆹ" to "p",
            "ᆹᄋ" to "ps", "ᆹᄂ" to "mn", "ᆹᄅ" to "mn", "ᆹᄆ" to "mm", "ᆹᄒ" to "psh", "ᆺ" to "t",
            "ᆺᄋ" to "s", "ᆺᄂ" to "nn", "ᆺᄅ" to "nn", "ᆺᄆ" to "nm", "ᆺᄒ" to "sh", "ᆻ" to "t",
            "ᆻᄋ" to "ss", "ᆻᄂ" to "tn", "ᆻᄅ" to "tn", "ᆻᄆ" to "nm", "ᆻᄒ" to "th", "ᆼ" to "ng",
            "ᆽ" to "t", "ᆽᄋ" to "j", "ᆽᄂ" to "nn", "ᆽᄅ" to "nn", "ᆽᄆ" to "nm", "ᆽᄒ" to "ch",
            "ᆾ" to "t", "ᆾᄋ" to "ch", "ᆾᄂ" to "nn", "ᆾᄅ" to "nn", "ᆾᄆ" to "nm", "ᆾᄒ" to "ch",
            "ᆿ" to "k", "ᆿᄋ" to "k", "ᆿᄂ" to "ngn", "ᆿᄅ" to "ngn", "ᆿᄆ" to "ngm", "ᆿᄒ" to "kh",
            "ᇀ" to "t", "ᇀᄋ" to "t", "ᇀᄂ" to "nn", "ᇀᄅ" to "nn", "ᇀᄆ" to "nm", "ᇀᄒ" to "th",
            "ᇁ" to "p", "ᇁᄋ" to "p", "ᇁᄂ" to "mn", "ᇁᄅ" to "mn", "ᇁᄆ" to "mm", "ᇁᄒ" to "ph",
            "ᇂ" to "t", "ᇂᄋ" to "h", "ᇂᄂ" to "nn", "ᇂᄅ" to "nn", "ᇂᄆ" to "mm", "ᇂᄒ" to "t",
            "ᇂᄀ" to "k"
        )
    )

    private val GENERAL_CYRILLIC_ROMAJI_MAP: Map<String, String> = mapOf(
        "А" to "A", "Б" to "B", "В" to "V", "Г" to "G", "Ґ" to "G", "Д" to "D",
        "Ѓ" to "Ǵ", "Ђ" to "Đ", "Е" to "E", "Ё" to "Yo", "Є" to "Ye", "Ж" to "Zh",
        "З" to "Z", "Ѕ" to "Dz", "И" to "I", "І" to "I", "Ї" to "Yi", "Й" to "Y",
        "Ј" to "Y", "К" to "K", "Л" to "L", "Љ" to "Ly", "М" to "M", "Н" to "N",
        "Њ" to "Ny", "О" to "O", "П" to "P", "Р" to "R", "С" to "S", "Т" to "T",
        "Ћ" to "Ć", "У" to "U", "Ў" to "Ŭ", "Ф" to "F", "Х" to "Kh", "Ц" to "Ts",
        "Ч" to "Ch", "Џ" to "Dž", "Ш" to "Sh", "Щ" to "Shch", "Ъ" to "ʺ", "Ы" to "Y",
        "Ь" to "ʹ", "Э" to "E", "Ю" to "Yu", "Я" to "Ya",
        "Ѡ" to "O", "Ѣ" to "Ya", "Ѥ" to "Ye", "Ѧ" to "Ya", "Ѩ" to "Ya",
        "Ѫ" to "U", "Ѭ" to "Yu", "Ѯ" to "Ks", "Ѱ" to "Ps", "Ѳ" to "F",
        "Ѵ" to "I", "Ѷ" to "I", "Ғ" to "Gh", "Ҕ" to "G", "Җ" to "Zh",
        "Ҙ" to "Dz", "Қ" to "Q", "Ҝ" to "K", "Ҟ" to "K", "Ҡ" to "K",
        "Ң" to "Ng", "Ҥ" to "Ng", "Ҧ" to "P", "Ҩ" to "O", "Ҫ" to "S",
        "Ҭ" to "T", "Ү" to "U", "Ұ" to "U", "Ҳ" to "Kh", "Ҵ" to "Ts",
        "Ҷ" to "Ch", "Ҹ" to "Ch", "Һ" to "H", "Ҽ" to "Ch", "Ҿ" to "Ch",
        "Ќ" to "Ḱ", "Ө" to "Ö",

        "а" to "a", "б" to "b", "в" to "v", "г" to "g", "ґ" to "g", "д" to "d",
        "ѓ" to "ǵ", "ђ" to "đ", "е" to "e", "ё" to "yo", "є" to "ye", "ж" to "zh",
        "з" to "z", "ѕ" to "dz", "и" to "i", "і" to "i", "ї" to "yi", "й" to "y",
        "ј" to "y", "к" to "k", "л" to "l", "љ" to "ly", "м" to "m", "н" to "n",
        "њ" to "ny", "о" to "o", "п" to "p", "р" to "r", "с" to "s", "т" to "t",
        "ћ" to "ć", "у" to "u", "ў" to "ŭ", "ф" to "f", "х" to "kh", "ц" to "ts",
        "ч" to "ch", "џ" to "dž", "ш" to "sh", "щ" to "shch", "ъ" to "ʺ", "ы" to "y",
        "ь" to "ʹ", "э" to "e", "ю" to "yu", "я" to "ya",
        "ѡ" to "o", "ѣ" to "ya", "ѥ" to "ye", "ѧ" to "ya", "ѩ" to "ya",
        "ѫ" to "u", "ѭ" to "yu", "ѯ" to "ks", "ѱ" to "ps", "ѳ" to "f",
        "ѵ" to "i", "ѷ" to "i", "ғ" to "gh", "ҕ" to "g", "җ" to "zh",
        "ҙ" to "dz", "қ" to "q", "ҝ" to "k", "ҟ" to "k", "ҡ" to "k",
        "ң" to "ng", "ҥ" to "ng", "ҧ" to "p", "ҩ" to "o", "ҫ" to "s",
        "ҭ" to "t", "ү" to "u", "ұ" to "u", "ҳ" to "kh", "ҵ" to "ts",
        "ҷ" to "ch", "ҹ" to "ch", "һ" to "h", "ҽ" to "ch", "ҿ" to "ch",
        "ќ" to "ḱ", "ө" to "ö"
    )

    private val RUSSIAN_ROMAJI_MAP: Map<String, String> = mapOf(
        "ого" to "ovo", "Ого" to "Ovo", "его" to "evo", "Его" to "Evo"
    )

    private val UKRAINIAN_ROMAJI_MAP: Map<String, String> = mapOf(
        "Г" to "H", "г" to "h",
        "Ґ" to "G", "ґ" to "g",
        "Є" to "Ye", "є" to "ye",
        "І" to "I", "і" to "i",
        "Ї" to "Yi", "ї" to "yi"
    )

    private val SERBIAN_ROMAJI_MAP: Map<String, String> = mapOf(
        "Ж" to "Ž", "Љ" to "Lj", "Њ" to "Nj", "Ц" to "C", "Ч" to "Č",
        "Џ" to "Dž", "Ш" to "Š", "Х" to "H",

        "ж" to "ž", "љ" to "lj", "њ" to "nj", "ц" to "c", "ч" to "č",
        "џ" to "dž", "ш" to "š", "х" to "h"
    )

    private val BULGARIAN_ROMAJI_MAP: Map<String, String> = mapOf(
        "Ж" to "Zh", "Ц" to "Ts", "Ч" to "Ch", "Ш" to "Sh", "Щ" to "Sht",
        "Ъ" to "A", "Ь" to "Y", "Ю" to "Yu", "Я" to "Ya",

        "ж" to "zh", "ц" to "ts", "ч" to "ch", "ш" to "sh", "щ" to "sht",
        "ъ" to "a", "ь" to "y", "ю" to "yu", "я" to "ya"
    )

    private val BELARUSIAN_ROMAJI_MAP: Map<String, String> = mapOf(
        "Г" to "H", "г" to "h", "Ў" to "W", "ў" to "w"
    )

    private val KYRGYZ_ROMAJI_MAP: Map<String, String> = mapOf(
        "Ү" to "Ü", "ү" to "ü", "Ы" to "Y", "ы" to "y"
    )

    private val MACEDONIAN_ROMAJI_MAP: Map<String, String> = mapOf(
        "Ѓ" to "Gj", "Ѕ" to "Dz", "И" to "I", "Ј" to "J", "Љ" to "Lj",
        "Њ" to "Nj", "Ќ" to "Kj", "Џ" to "Dž", "Ч" to "Č", "Ш" to "Sh",
        "Ж" to "Zh", "Ц" to "C", "Х" to "H",

        "ѓ" to "gj", "ѕ" to "dz", "и" to "i", "ј" to "j", "љ" to "lj",
        "њ" to "nj", "ќ" to "kj", "џ" to "dž", "ч" to "č", "ш" to "sh",
        "ж" to "zh", "ц" to "c", "х" to "h"
    )

    private val RUSSIAN_CYRILLIC_LETTERS = setOf(
        "А", "Б", "В", "Г", "Д", "Е", "Ё", "Ж", "З", "И", "Й", "К", "Л", "М", "Н",
        "О", "П", "Р", "С", "Т", "У", "Ф", "Х", "Ц", "Ч", "Ш", "Щ", "Ъ", "Ы", "Ь",
        "Э", "Ю", "Я",

        "а", "б", "в", "г", "д", "е", "ё", "ж", "з", "и", "й", "к", "л", "м", "н",
        "о", "п", "р", "с", "т", "у", "ф", "х", "ц", "ч", "ш", "щ", "ъ", "ы", "ь",
        "э", "ю", "я"
    )

    private val UKRAINIAN_CYRILLIC_LETTERS = setOf(
       "А", "Б", "В", "Г", "Ґ", "Д", "Е", "Є", "Ж", "З", "И", "І", "Ї", "Й",
        "К", "Л", "М", "Н", "О", "П", "Р", "С", "Т", "У", "Ф", "Х", "Ц", "Ч",
        "Ш", "Щ", "Ь", "Ю", "Я",

        "а", "б", "в", "г", "ґ", "д", "е", "є", "ж", "з", "и", "і", "ї", "й",
        "к", "л", "м", "н", "о", "п", "р", "с", "т", "у", "ф", "х", "ц", "ч",
        "ш", "щ", "ь", "ю", "я"
    )

    private val SERBIAN_CYRILLIC_LETTERS = setOf(
        "А", "Б", "В", "Г", "Д", "Ђ", "Е", "Ж", "З", "И", "Ј", "К", "Л", "Љ", "М",
        "Н", "Њ", "О", "П", "Р", "С", "Т", "Ћ", "У", "Ф", "Х", "Ц", "Ч", "Џ", "Ш",

        "а", "б", "в", "г", "д", "ђ", "е", "ж", "з", "и", "ј", "к", "л", "љ", "м",
        "н", "њ", "о", "п", "р", "с", "т", "ћ", "у", "ф", "х", "ц", "ч", "џ", "ш"
    )

    private val BULGARIAN_CYRILLIC_LETTERS = setOf(
        "А", "Б", "В", "Г", "Д", "Е", "Ж", "З", "И", "Й", "К", "Л", "М",
        "Н", "О", "П", "Р", "С", "Т", "У", "Ф", "Х", "Ц", "Ч", "Ш", "Щ",
        "Ъ", "Ь", "Ю", "Я",

        "а", "б", "в", "г", "д", "е", "ж", "з", "и", "й", "к", "л", "м",
        "н", "о", "п", "р", "с", "т", "у", "ф", "х", "ц", "ч", "ш", "щ",
        "ъ", "ь", "ю", "я"
    )

    private val BELARUSIAN_CYRILLIC_LETTERS = setOf(
        "А", "Б", "В", "Г", "Д", "Е", "Ё", "Ж", "З", "І", "Й", "К", "Л", "М", "Н",
        "О", "П", "Р", "С", "Т", "У", "Ў", "Ф", "Х", "Ц", "Ч", "Ш", "Ь", "Ю", "Я",
        "Ы", "Э",

        "а", "б", "в", "г", "д", "е", "ё", "ж", "з", "і", "й", "к", "л", "м", "н",
        "о", "п", "р", "с", "т", "у", "ў", "ф", "х", "ц", "ч", "ш", "ь", "ю", "я",
        "ы", "э"
    )

    private val KYRGYZ_CYRILLIC_LETTERS = setOf(
        "А", "Б", "В", "Г", "Д", "Е", "Ё", "Ж", "З", "И", "Й", "К", "Л", "М", "Н",
        "Ң", "О", "Ө", "П", "Р", "С", "Т", "У", "Ү", "Ф", "Х", "Ц", "Ч", "Ш", "Щ",
        "Ъ", "Ы", "Ь", "Э", "Ю", "Я",

        "а", "б", "в", "г", "д", "е", "ё", "ж", "з", "и", "й", "к", "л", "м", "н",
        "ң", "о", "ө", "п", "р", "с", "т", "у", "ү", "ф", "х", "ц", "ч", "ш", "щ",
        "ъ", "ы", "ь", "э", "ю", "я"
    )

    private val MACEDONIAN_CYRILLIC_LETTERS = setOf(
        "А", "Б", "В", "Г", "Д", "Ѓ", "Е", "Ж", "З", "Ѕ", "И", "Ј", "К", "Л",
        "Љ", "М", "Н", "Њ", "О", "П", "Р", "С", "Т", "Ќ", "У", "Ф", "Х",
        "Ц", "Ч", "Џ", "Ш",

        "а", "б", "в", "г", "д", "ѓ", "е", "ж", "з", "ѕ", "и", "ј", "к", "л",
        "љ", "м", "н", "њ", "о", "п", "р", "с", "т", "ќ", "у", "ф", "х",
        "ц", "ч", "џ", "ш"
    )

    private val UKRAINIAN_SPECIFIC_CYRILLIC_LETTERS = setOf(
        "Ґ", "ґ", "Є", "є", "І", "і", "Ї", "ї"
    )

    private val SERBIAN_SPECIFIC_CYRILLIC_LETTERS = setOf(
        "Ђ", "ђ", "Ј", "ј", "Љ", "љ", "Њ", "њ", "Ћ", "ћ", "Џ", "џ"
    )

    private val BELARUSIAN_SPECIFIC_CYRILLIC_LETTERS = setOf(
        "Ў", "ў", "І", "і"
    )

    private val KYRGYZ_SPECIFIC_CYRILLIC_LETTERS = setOf(
        "Ң", "ң", "Ө", "ө", "Ү", "ү"
    )

    private val MACEDONIAN_SPECIFIC_CYRILLIC_LETTERS = setOf(
        "Ѓ", "ѓ", "Ѕ", "ѕ", "Ќ", "ќ"
    )

    // Lazy initialized Tokenizer
    private val kuromojiTokenizer: Tokenizer by lazy {
        Tokenizer()
    }

    private val HEX_ENTITY_REGEX = "&#x([0-9a-fA-F]+);".toRegex()
    private val DEC_ENTITY_REGEX = "&#(\\d+);".toRegex()

    private fun decodeHtmlEntities(text: String): String =
        text
            .replace(HEX_ENTITY_REGEX) { match ->
                match.groupValues[1].toIntOrNull(16)
                    ?.takeIf { it in 0..0x10FFFF }
                    ?.let { String(Character.toChars(it)) }
                    ?: match.value
            }
            .replace(DEC_ENTITY_REGEX) { match ->
                match.groupValues[1].toIntOrNull()
                    ?.takeIf { it in 0..0x10FFFF }
                    ?.let { String(Character.toChars(it)) }
                    ?: match.value
            }
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")

    fun parseLyrics(lyrics: String): List<LyricsEntry> {
        // Unescape JSON string if needed
        val unescapedLyrics = lyrics
            .trim()
            .removePrefix("\"")
            .removeSuffix("\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")

        // Decode HTML entities (e.g. &#x27; -> ', &amp; -> &)
        val decodedLyrics = decodeHtmlEntities(unescapedLyrics)
        
        val lines = decodedLyrics.lines()
            .filter { it.isNotBlank() && !it.trim().startsWith("[offset:") }
        
        // Check if this is rich sync format (contains <MM:SS.mm> patterns)
        val isRichSync = lines.any { line ->
            RICH_SYNC_LINE_REGEX.matches(line.trim()) && 
            RICH_SYNC_WORD_REGEX.containsMatchIn(line)
        }
        
        return if (isRichSync) {
            parseRichSyncLyrics(lines)
        } else {
            parseStandardLyrics(lines)
        }
    }
    
    /**
     * Parse rich sync lyrics format: [MM:SS.mm]<MM:SS.mm> word <MM:SS.mm> word ...
     * This format provides word-by-word timing for karaoke-style highlighting
     */
    private fun parseRichSyncLyrics(lines: List<String>): List<LyricsEntry> {
        val result = mutableListOf<LyricsEntry>()
        
        lines.forEachIndexed { index, line ->
            val matchResult = RICH_SYNC_LINE_REGEX.matchEntire(line.trim())
            if (matchResult != null) {
                val minutes = matchResult.groupValues[1].toLongOrNull() ?: 0L
                val seconds = matchResult.groupValues[2].toLongOrNull() ?: 0L
                val centiseconds = matchResult.groupValues[3].toLongOrNull() ?: 0L
                
                // Convert to milliseconds
                val millisPart = if (matchResult.groupValues[3].length == 3) centiseconds else centiseconds * 10
                val lineTimeMs = minutes * DateUtils.MINUTE_IN_MILLIS + seconds * DateUtils.SECOND_IN_MILLIS + millisPart
                
                var content = matchResult.groupValues[4].trimStart()
                
                // Parse agent marker {agent:v1}
                val agentMatch = AGENT_REGEX.find(content)
                val agent = agentMatch?.groupValues?.get(1)
                if (agentMatch != null) {
                    content = content.replaceFirst(AGENT_REGEX, "")
                }
                
                // Parse background marker {bg}
                val isBackground = BACKGROUND_REGEX.containsMatchIn(content)
                if (isBackground) {
                    content = content.replaceFirst(BACKGROUND_REGEX, "")
                }
                
                // Parse word-level timestamps from content
                val wordTimings = parseRichSyncWords(content, index, lines)
                
                // Extract plain text (remove all <MM:SS.mm> tags)
                val plainText = content.replace(Regex("<\\d{1,2}:\\d{2}\\.\\d{2,3}>\\s*"), "").trim()
                
                if (plainText.isNotBlank()) {
                    result.add(LyricsEntry(lineTimeMs, plainText, wordTimings, agent = agent, isBackground = isBackground))
                }
            }
        }
        
        return result.sorted()
    }
    
    /**
     * Parse word timestamps from rich sync content
     * Format: <MM:SS.mm> word <MM:SS.mm> word ...
     */
    private fun parseRichSyncWords(content: String, currentIndex: Int, allLines: List<String>): List<WordTimestamp>? {
        val wordMatches = RICH_SYNC_WORD_REGEX.findAll(content).toList()
        
        if (wordMatches.isEmpty()) return null
        
        val wordTimings = mutableListOf<WordTimestamp>()
        
        wordMatches.forEachIndexed { index, match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: 0L
            val seconds = match.groupValues[2].toLongOrNull() ?: 0L
            val fraction = match.groupValues[3].toLongOrNull() ?: 0L
            
            // Convert to seconds (Double)
            val fractionPart = if (match.groupValues[3].length == 3) fraction / 1000.0 else fraction / 100.0
            val startTimeSeconds = minutes * 60.0 + seconds + fractionPart
            
            val wordText = match.groupValues[4].trim()
            
            // Calculate end time: use next word's start time, or estimate from next line
            val endTimeSeconds = if (index < wordMatches.size - 1) {
                val nextMatch = wordMatches[index + 1]
                val nextMinutes = nextMatch.groupValues[1].toLongOrNull() ?: 0L
                val nextSeconds = nextMatch.groupValues[2].toLongOrNull() ?: 0L
                val nextFraction = nextMatch.groupValues[3].toLongOrNull() ?: 0L
                val nextFractionPart = if (nextMatch.groupValues[3].length == 3) nextFraction / 1000.0 else nextFraction / 100.0
                nextMinutes * 60.0 + nextSeconds + nextFractionPart
            } else {
                // For last word, try to get next line's start time or add a default duration
                val nextLineTime = getNextLineStartTime(currentIndex, allLines)
                nextLineTime ?: (startTimeSeconds + 0.5) // Default 500ms duration for last word
            }
            
            if (wordText.isNotBlank()) {
                wordTimings.add(WordTimestamp(wordText, startTimeSeconds, endTimeSeconds))
            }
        }
        
        return if (wordTimings.isNotEmpty()) wordTimings else null
    }
    
    /**
     * Get the start time of the next line for calculating the last word's end time
     */
    private fun getNextLineStartTime(currentIndex: Int, allLines: List<String>): Double? {
        if (currentIndex + 1 >= allLines.size) return null
        
        val nextLine = allLines[currentIndex + 1].trim()
        val matchResult = RICH_SYNC_LINE_REGEX.matchEntire(nextLine) ?: return null
        
        val minutes = matchResult.groupValues[1].toLongOrNull() ?: return null
        val seconds = matchResult.groupValues[2].toLongOrNull() ?: return null
        val fraction = matchResult.groupValues[3].toLongOrNull() ?: 0L
        
        val fractionPart = if (matchResult.groupValues[3].length == 3) fraction / 1000.0 else fraction / 100.0
        return minutes * 60.0 + seconds + fractionPart
    }
    
    /**
     * Parse standard synced lyrics format: [MM:SS.mm] text
     */
    private fun parseStandardLyrics(lines: List<String>): List<LyricsEntry> {
        val result = mutableListOf<LyricsEntry>()
        
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (!line.trim().startsWith("<") || !line.trim().endsWith(">")) {
                val entries = parseLine(line, null)
                if (entries != null) {
                    val wordTimestamps = if (i + 1 < lines.size) {
                        val nextLine = lines[i + 1]
                        if (nextLine.trim().startsWith("<") && nextLine.trim().endsWith(">")) {
                            parseWordTimestamps(nextLine.trim().removeSurrounding("<", ">"))
                        } else null
                    } else null
                    
                    if (wordTimestamps != null) {
                        result.addAll(entries.map { entry ->
                            LyricsEntry(entry.time, entry.text, wordTimestamps, agent = entry.agent, isBackground = entry.isBackground)
                        })
                    } else {
                        result.addAll(entries)
                    }
                }
            }
            i++
        }
        return result.sorted()
    }
    
    private fun parseWordTimestamps(data: String): List<WordTimestamp>? {
        if (data.isBlank()) return null
        return try {
            data.split("|").mapNotNull { wordData ->
                val parts = wordData.split(":")
                if (parts.size == 3) {
                    WordTimestamp(
                        text = parts[0],
                        startTime = parts[1].toDouble(),
                        endTime = parts[2].toDouble()
                    )
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseLine(line: String, words: List<WordTimestamp>? = null): List<LyricsEntry>? {
        if (line.isEmpty()) {
            return null
        }
        val matchResult = LINE_REGEX.matchEntire(line.trim()) ?: return null
        val times = matchResult.groupValues[1]
        var text = matchResult.groupValues[3]
        val timeMatchResults = TIME_REGEX.findAll(times)
        
        // Parse agent marker {agent:v1}
        val agentMatch = AGENT_REGEX.find(text)
        val agent = agentMatch?.groupValues?.get(1)
        if (agentMatch != null) {
            text = text.replaceFirst(AGENT_REGEX, "")
        }
        
        // Parse background marker {bg}
        val isBackground = BACKGROUND_REGEX.containsMatchIn(text)
        if (isBackground) {
            text = text.replaceFirst(BACKGROUND_REGEX, "")
        }

        return timeMatchResults
            .map { timeMatchResult ->
                val min = timeMatchResult.groupValues[1].toLong()
                val sec = timeMatchResult.groupValues[2].toLong()
                val milString = timeMatchResult.groupValues[3]
                var mil = milString.toLong()
                if (milString.length == 2) {
                    mil *= 10
                }
                val time = min * DateUtils.MINUTE_IN_MILLIS + sec * DateUtils.SECOND_IN_MILLIS + mil
                LyricsEntry(time, text, words, agent = agent, isBackground = isBackground)
            }.toList()
    }

    fun findCurrentLineIndex(
        lines: List<LyricsEntry>,
        position: Long,
    ): Int {
        for (index in lines.indices) {
            if (lines[index].time >= position + 300L) {
                return index - 1
            }
        }
        return lines.lastIndex
    }

    // TODO: Will be useful if we let the user pick the language, useless for now
    /* enum class CyrillicLanguage {
        RUSSIAN,
        UKRAINIAN,
        SERBIAN,
        BULGARIAN,
        BELARUSIAN,
        KYRGYZ,
        MACEDONIAN
    } */

    fun katakanaToRomaji(katakana: String?): String {
        if (katakana.isNullOrEmpty()) return ""

        val romajiBuilder = StringBuilder(katakana.length)
        var i = 0
        val n = katakana.length
        while (i < n) {
            var consumed = false
            if (i + 1 < n) {
                val twoCharCandidate = katakana.substring(i, i + 2)
                val mappedTwoChar = KANA_ROMAJI_MAP[twoCharCandidate]
                if (mappedTwoChar != null) {
                    romajiBuilder.append(mappedTwoChar)
                    i += 2
                    consumed = true
                }
            }

            if (!consumed) {
                val oneCharCandidate = katakana[i].toString()
                val mappedOneChar = KANA_ROMAJI_MAP[oneCharCandidate]
                if (mappedOneChar != null) {
                    romajiBuilder.append(mappedOneChar)
                } else {
                    romajiBuilder.append(oneCharCandidate)
                }
                i += 1
            }
        }
        return romajiBuilder.toString().lowercase()
    }

    suspend fun romanizeJapanese(text: String): String = withContext(Dispatchers.Default) {
        val tokens = kuromojiTokenizer.tokenize(text)
        val romanizedTokens = tokens.mapIndexed { index, token ->
            val currentReading = if (token.reading.isNullOrEmpty() || token.reading == "*") {
                token.surface
            } else {
                token.reading
            }
            val nextTokenReading = if (index + 1 < tokens.size) {
                tokens[index + 1].reading?.takeIf { it.isNotEmpty() && it != "*" } ?: tokens[index + 1].surface
            } else {
                null
            }
            katakanaToRomaji(currentReading, nextTokenReading)
        }
        romanizedTokens.joinToString(" ")
    }

    fun katakanaToRomaji(katakana: String?, nextKatakana: String? = null): String {
        if (katakana.isNullOrEmpty()) return ""

        val romajiBuilder = StringBuilder(katakana.length)
        var i = 0
        val n = katakana.length
        while (i < n) {
            var consumed = false
            if (i + 1 < n) {
                val twoCharCandidate = katakana.substring(i, i + 2)
                val mappedTwoChar = KANA_ROMAJI_MAP[twoCharCandidate]
                if (mappedTwoChar != null) {
                    romajiBuilder.append(mappedTwoChar)
                    i += 2
                    consumed = true
                }
            }

            if (!consumed && katakana[i] == 'ッ') {
                val nextCharToDouble = nextKatakana?.getOrNull(0)
                if (nextCharToDouble != null) {
                    val nextCharRomaji = KANA_ROMAJI_MAP[nextCharToDouble.toString()]?.getOrNull(0)?.toString()
                        ?: nextCharToDouble.toString()
                    romajiBuilder.append(nextCharRomaji.lowercase().trim())
                }
                i += 1
                consumed = true
            }

            if (!consumed) {
                val oneCharCandidate = katakana[i].toString()
                val mappedOneChar = KANA_ROMAJI_MAP[oneCharCandidate]
                if (mappedOneChar != null) {
                    romajiBuilder.append(mappedOneChar)
                } else {
                    romajiBuilder.append(oneCharCandidate)
                }
                i += 1
            }
        }
        return romajiBuilder.toString().lowercase()
    }

    suspend fun romanizeKorean(text: String): String = withContext(Dispatchers.Default) {
        val romajaBuilder = StringBuilder()
        var prevFinal: String? = null

        for (i in text.indices) {
            val char = text[i]
            if (char in '\uAC00'..'\uD7A3') {
                val syllableIndex = char.code - 0xAC00
                val choIndex = syllableIndex / (21 * 28)
                val jungIndex = (syllableIndex % (21 * 28)) / 28
                val jongIndex = syllableIndex % 28

                val choChar = (0x1100 + choIndex).toChar().toString()
                val jungChar = (0x1161 + jungIndex).toChar().toString()
                val jongChar = if (jongIndex == 0) null else (0x11A7 + jongIndex).toChar().toString()

                if (prevFinal != null) {
                    val contextKey = prevFinal + choChar
                    val jong = HANGUL_ROMAJA_MAP["jong"]?.get(contextKey)
                        ?: HANGUL_ROMAJA_MAP["jong"]?.get(prevFinal)
                        ?: prevFinal
                    romajaBuilder.append(jong)
                }

                val cho = HANGUL_ROMAJA_MAP["cho"]?.get(choChar) ?: choChar
                val jung = HANGUL_ROMAJA_MAP["jung"]?.get(jungChar) ?: jungChar
                romajaBuilder.append(cho).append(jung)
                prevFinal = jongChar
            } else {
                if (prevFinal != null) {
                    val jong = HANGUL_ROMAJA_MAP["jong"]?.get(prevFinal) ?: prevFinal
                    romajaBuilder.append(jong)
                    prevFinal = null
                }
                romajaBuilder.append(char)
            }
        }

        if (prevFinal != null) {
            val jong = HANGUL_ROMAJA_MAP["jong"]?.get(prevFinal) ?: prevFinal
            romajaBuilder.append(jong)
        }

        romajaBuilder.toString()
    }

    suspend fun romanizeChinese(text: String): String = withContext(Dispatchers.Default) {
        if (text.isEmpty()) return@withContext ""
        val builder = StringBuilder(text.length * 2)
        for (ch in text) {
            if (ch in '\u4E00'..'\u9FFF') {
                val py = Pinyin.toPinyin(ch).lowercase(Locale.getDefault())
                builder.append(py).append(' ')
            } else {
                builder.append(ch)
            }
        }
        // Remove whitespaces before ASCII and CJK punctuations
        builder.toString()
            .replace(Regex("\\s+([,.!?;:])"), "$1")
            .replace(Regex("\\s+([，。！？；：、（）《》〈〉【】『』「」])"), "$1")
            .trim()
    }

    suspend fun romanizeCyrillic(text: String): String? = withContext(Dispatchers.Default) {
        if (text.isEmpty()) return@withContext null

        val cyrillicChars = text.filter { it in '\u0400'..'\u04FF' }

        if (cyrillicChars.isEmpty() ||
            (cyrillicChars.length == 1 && (cyrillicChars[0] == 'е' || cyrillicChars[0] == 'Е'))) {
            return@withContext null
        }

        when {
            isRussian(text) -> romanizeRussianInternal(text)
            isUkrainian(text) -> romanizeUkrainianInternal(text)
            isSerbian(text) -> romanizeSerbianInternal(text)
            isBulgarian(text) -> romanizeBulgarianInternal(text)
            isBelarusian(text) -> romanizeBelarusianInternal(text)
            isKyrgyz(text) -> romanizeKyrgyzInternal(text)
            isMacedonian(text) -> romanizeMacedonianInternal(text)
            else -> null
        }
    }

    private fun romanizeRussianInternal(text: String): String {
        val romajiBuilder = StringBuilder(text.length)
        val words = text.split("((?<=\\s|[.,!?;])|(?=\\s|[.,!?;]))".toRegex())
            .filter { it.isNotEmpty() }

        words.forEachIndexed { _, word ->
            if (word.matches("[.,!?;]".toRegex()) || word.isBlank()) {
                romajiBuilder.append(word)
            } else {
                var charIndex = 0
                while (charIndex < word.length) {
                    var consumed = false
                    // Check for 3-character sequences
                    if (charIndex + 2 < word.length) {
                        val threeCharCandidate = word.substring(charIndex, charIndex + 3)
                        if (RUSSIAN_ROMAJI_MAP.containsKey(threeCharCandidate)) {
                            romajiBuilder.append(RUSSIAN_ROMAJI_MAP[threeCharCandidate])
                            charIndex += 3
                            consumed = true
                        }
                    }

                    if (!consumed) {
                        val charStr = word[charIndex].toString()
                        // Special case for 'е' or 'Е' at the start of a word
                        if ((charStr == "е" || charStr == "Е") && (charIndex == 0 || word[charIndex - 1].isWhitespace())) {
                            romajiBuilder.append(if (charStr == "е") "ye" else "Ye")
                        } else {
                            // Apply general Cyrillic mapping (Russian is no different so there's no need to apply a russian map)
                            val romanizedChar = GENERAL_CYRILLIC_ROMAJI_MAP[charStr] ?: charStr
                            romajiBuilder.append(romanizedChar)
                        }
                        charIndex += 1
                    }
                }
            }
        }
        return romajiBuilder.toString()
    }

    private fun romanizeUkrainianInternal(text: String): String {
        val romajiBuilder = StringBuilder(text.length)
        val words = text.split("((?<=\\s|[.,!?;])|(?=\\s|[.,!?;]))".toRegex())
            .filter { it.isNotEmpty() }

        words.forEachIndexed { _, word ->
            if (word.matches("[.,!?;]".toRegex()) || word.isBlank()) {
                romajiBuilder.append(word)
            } else {
                var charIndex = 0
                while (charIndex < word.length) {
                    val charStr = word[charIndex].toString()
                    var processed = false

                    if (charIndex > 0 && word[charIndex - 1].isLetter() && !isCyrillicVowel(word[charIndex - 1])) {
                        // Check if the current character is Ю or Я and is preceded by a consonant
                        if (charStr == "Ю") {
                            romajiBuilder.append("Iu")
                            processed = true
                        } else if (charStr == "ю") {
                            romajiBuilder.append("iu")
                            processed = true
                        } else if (charStr == "Я") {
                            romajiBuilder.append("Ia")
                            processed = true
                        } else if (charStr == "я") {
                            romajiBuilder.append("ia")
                            processed = true
                        }
                    }

                    if (!processed) {
                        romajiBuilder.append(UKRAINIAN_ROMAJI_MAP[charStr] ?: GENERAL_CYRILLIC_ROMAJI_MAP[charStr] ?: charStr)
                    }
                    charIndex++
                }
            }
        }
        return romajiBuilder.toString()
    }

    private fun romanizeSerbianInternal(text: String): String {
        val romajiBuilder = StringBuilder(text.length)
        val words = text.split("((?<=\\s|[.,!?;])|(?=\\s|[.,!?;]))".toRegex())
            .filter { it.isNotEmpty() }

        words.forEachIndexed { _, word ->
            if (word.matches("[.,!?;]".toRegex()) || word.isBlank()) {
                romajiBuilder.append(word)
            } else {
                var charIndex = 0
                while (charIndex < word.length) {
                    val charStr = word[charIndex].toString()
                    val romanizedChar = SERBIAN_ROMAJI_MAP[charStr] ?: GENERAL_CYRILLIC_ROMAJI_MAP[charStr] ?: charStr
                    romajiBuilder.append(romanizedChar)
                    charIndex++
                }
            }
        }
        return romajiBuilder.toString()
    }

    private fun romanizeBulgarianInternal(text: String): String {
        val romajiBuilder = StringBuilder(text.length)
        val words = text.split("((?<=\\s|[.,!?;])|(?=\\s|[.,!?;]))".toRegex())
            .filter { it.isNotEmpty() }

        words.forEachIndexed { _, word ->
            if (word.matches("[.,!?;]".toRegex()) || word.isBlank()) {
                romajiBuilder.append(word)
            } else {
                var charIndex = 0
                while (charIndex < word.length) {
                    val charStr = word[charIndex].toString()
                    val romanizedChar = BULGARIAN_ROMAJI_MAP[charStr] ?: GENERAL_CYRILLIC_ROMAJI_MAP[charStr] ?: charStr
                    romajiBuilder.append(romanizedChar)
                    charIndex++
                }
            }
        }
        return romajiBuilder.toString()
    }

    private fun romanizeBelarusianInternal(text: String): String {
        val romajiBuilder = StringBuilder(text.length)
        val words = text.split("((?<=\\s|[.,!?;])|(?=\\s|[.,!?;]))".toRegex())
            .filter { it.isNotEmpty() }

        words.forEach { word ->
            if (word.matches("[.,!?;]".toRegex()) || word.isBlank()) {
                romajiBuilder.append(word)
            } else {
                var charIndex = 0
                while (charIndex < word.length) {
                    val charStr = word[charIndex].toString()
                    // Special case for 'е' or 'Е' at the start of a word
                    if ((charStr == "е" || charStr == "Е") && (charIndex == 0 || word[charIndex - 1].isWhitespace())) {
                        romajiBuilder.append(if (charStr == "е") "ye" else "Ye")
                    } else {
                        // General mapping
                        val romanizedChar = BELARUSIAN_ROMAJI_MAP[charStr] ?: GENERAL_CYRILLIC_ROMAJI_MAP[charStr] ?: charStr
                        romajiBuilder.append(romanizedChar)
                    }
                    charIndex += 1
                }
            }
        }

        return romajiBuilder.toString()
    }

    private fun romanizeKyrgyzInternal(text: String): String {
        val romajiBuilder = StringBuilder(text.length)
        val words = text.split("((?<=\\s|[.,!?;])|(?=\\s|[.,!?;]))".toRegex())
            .filter { it.isNotEmpty() }

        words.forEachIndexed { _, word ->
            if (word.matches("[.,!?;]".toRegex()) || word.isBlank()) {
                romajiBuilder.append(word)
            } else {
                var charIndex = 0
                while (charIndex < word.length) {
                    val charStr = word[charIndex].toString()
                    val romanizedChar = KYRGYZ_ROMAJI_MAP[charStr] ?: GENERAL_CYRILLIC_ROMAJI_MAP[charStr] ?: charStr
                    romajiBuilder.append(romanizedChar)
                    charIndex++
                }
            }
        }
        return romajiBuilder.toString()
    }

    private fun romanizeMacedonianInternal(text: String): String {
        val romajiBuilder = StringBuilder(text.length)
        val words = text.split("((?<=\\s|[.,!?;])|(?=\\s|[.,!?;]))".toRegex())
            .filter { it.isNotEmpty() }

        words.forEachIndexed { _, word ->
            if (word.matches("[.,!?;]".toRegex()) || word.isBlank()) {
                romajiBuilder.append(word)
            } else {
                var charIndex = 0
                while (charIndex < word.length) {
                    val charStr = word[charIndex].toString()
                    val romanizedChar = MACEDONIAN_ROMAJI_MAP[charStr] ?: GENERAL_CYRILLIC_ROMAJI_MAP[charStr] ?: charStr
                    romajiBuilder.append(romanizedChar)
                    charIndex++
                }
            }
        }
        return romajiBuilder.toString()
    }

    // TODO: This function might be used later if we let the user choose the language manually
    /** private suspend fun romanizeCyrillicWithLanguage(text: String, language: CyrillicLanguage): String = withContext(Dispatchers.Default) {
        if (text.isEmpty()) return@withContext ""

        val detectedLanguage = language ?: when {
            isRussian(text) -> CyrillicLanguage.RUSSIAN
            isUkrainian(text) -> CyrillicLanguage.UKRAINIAN
            isSerbian(text) -> CyrillicLanguage.SERBIAN
            isBelarusian(text) -> CyrillicLanguage.BELARUSIAN
            isKyrgyz(text) -> CyrillicLanguage.KYRGYZ
            isMacedonian(text) -> CyrillicLanguage.MACEDONIAN
            else -> return@withContext text
        }

        val languageMap: Map<String, String> = when (detectedLanguage) {
            CyrillicLanguage.RUSSIAN -> RUSSIAN_ROMAJI_MAP
            CyrillicLanguage.UKRAINIAN -> UKRAINIAN_ROMAJI_MAP
            CyrillicLanguage.SERBIAN -> SERBIAN_ROMAJI_MAP
            CyrillicLanguage.BELARUSIAN -> BELARUSIAN_ROMAJI_MAP
            CyrillicLanguage.KYRGYZ -> KYRGYZ_ROMAJI_MAP
            CyrillicLanguage.MACEDONIAN -> MACEDONIAN_ROMAJI_MAP
            // else -> emptyMap()
        }
        val languageLetters = when (language) {
            CyrillicLanguage.RUSSIAN -> RUSSIAN_CYRILLIC_LETTERS
            CyrillicLanguage.UKRAINIAN -> UKRAINIAN_CYRILLIC_LETTERS
            CyrillicLanguage.SERBIAN -> SERBIAN_CYRILLIC_LETTERS
            CyrillicLanguage.BELARUSIAN -> BELARUSIAN_CYRILLIC_LETTERS
            CyrillicLanguage.KYRGYZ -> KYRGYZ_CYRILLIC_LETTERS
            CyrillicLanguage.MACEDONIAN -> MACEDONIAN_CYRILLIC_LETTERS
            else -> GENERAL_CYRILLIC_ROMAJI_MAP.keys
        }

        val romajiBuilder = StringBuilder(text.length)
        val words = text.split("((?<=\\s|[.,!?;])|(?=\\s|[.,!?;]))".toRegex())
            .filter { it.isNotEmpty() }

        words.forEachIndexed { _, word ->
            if (word.matches("[.,!?;]".toRegex()) || word.isBlank()) {
                // Preserve punctuation or spaces as is
                romajiBuilder.append(word)
            } else {
                // Process word
                var charIndex = 0
                while (charIndex < word.length) {
                    var consumed = false
                    // Check for 3-character sequences (language-specific, e.g., Russian)
                    if (detectedLanguage == CyrillicLanguage.RUSSIAN && charIndex + 2 < word.length) {
                        val threeCharCandidate = word.substring(charIndex, charIndex + 3)
                        if (languageLetters is Set<*> && languageLetters.containsAll(threeCharCandidate.toList().map { it.toString() })) {
                            val mappedThreeChar = languageMap[threeCharCandidate]
                            if (mappedThreeChar != null) {
                                romajiBuilder.append(mappedThreeChar)
                                charIndex += 3
                                consumed = true
                            }
                        }
                    }
                    if (!consumed) {
                        val charStr = word[charIndex].toString()
                        val isSpecificLanguageChar = languageLetters is Set<*> && languageLetters.contains(charStr)
                        val isGeneralCyrillicChar = GENERAL_CYRILLIC_ROMAJI_MAP.containsKey(charStr)

                        if (isSpecificLanguageChar || isGeneralCyrillicChar) {
                            if (detectedLanguage == CyrillicLanguage.RUSSIAN && (charStr == "е" || charStr == "Е") && charIndex == 0 && (charIndex == 0 || word[charIndex-1].isWhitespace())) {
                                romajiBuilder.append(if (charStr == "е") "ye" else "Ye")
                            } else {
                                val romanizedChar = languageMap[charStr] ?: GENERAL_CYRILLIC_ROMAJI_MAP[charStr]
                                if (romanizedChar != null) {
                                    romajiBuilder.append(romanizedChar)
                                } else {
                                    romajiBuilder.append(charStr)
                                }
                            }
                        } else {
                            romajiBuilder.append(charStr)
                        }
                        charIndex += 1
                    }
                }
            }
        }
        romajiBuilder.toString()
    } */

    fun isRussian(text: String): Boolean {
        return text.any { char ->
            RUSSIAN_CYRILLIC_LETTERS.contains(char.toString())
        } && text.all { char ->
            val charStr = char.toString()
            RUSSIAN_CYRILLIC_LETTERS.contains(charStr) || !charStr.matches("[\\u0400-\\u04FF]".toRegex())
        }
    }

    fun isUkrainian(text: String): Boolean {
        return text.any { char ->
            UKRAINIAN_CYRILLIC_LETTERS.contains(char.toString()) || UKRAINIAN_SPECIFIC_CYRILLIC_LETTERS.contains(char.toString())
        } && text.all { char ->
            UKRAINIAN_CYRILLIC_LETTERS.contains(char.toString()) || UKRAINIAN_SPECIFIC_CYRILLIC_LETTERS.contains(char.toString()) || !char.toString().matches("[\\u0400-\\u04FF]".toRegex())
        }
    }

    fun isSerbian(text: String): Boolean {
        return text.any { char ->
            SERBIAN_CYRILLIC_LETTERS.contains(char.toString()) || SERBIAN_SPECIFIC_CYRILLIC_LETTERS.contains(char.toString())
        } && text.all { char ->
            SERBIAN_CYRILLIC_LETTERS.contains(char.toString()) || SERBIAN_SPECIFIC_CYRILLIC_LETTERS.contains(char.toString()) || !char.toString().matches("[\\u0400-\\u04FF]".toRegex())
        }
    }

    fun isBulgarian(text: String): Boolean {
        return text.any { char ->
            BULGARIAN_CYRILLIC_LETTERS.contains(char.toString()) // Bulgarian doesn't have any language specific letters
        } && text.all { char ->
            BULGARIAN_CYRILLIC_LETTERS.contains(char.toString()) || !char.toString().matches("[\\u0400-\\u04FF]".toRegex())
        }
    }

    fun isBelarusian(text: String): Boolean {
        return text.any { char ->
            BELARUSIAN_CYRILLIC_LETTERS.contains(char.toString()) || BELARUSIAN_SPECIFIC_CYRILLIC_LETTERS.contains(char.toString())
        } && text.all { char ->
            BELARUSIAN_CYRILLIC_LETTERS.contains(char.toString()) || BELARUSIAN_SPECIFIC_CYRILLIC_LETTERS.contains(char.toString()) || !char.toString().matches("[\\u0400-\\u04FF]".toRegex())
        }
    }

    fun isKyrgyz(text: String): Boolean {
        return text.any { char ->
            KYRGYZ_CYRILLIC_LETTERS.contains(char.toString()) || KYRGYZ_SPECIFIC_CYRILLIC_LETTERS.contains(char.toString())
        } && text.all { char ->
            KYRGYZ_CYRILLIC_LETTERS.contains(char.toString()) || KYRGYZ_SPECIFIC_CYRILLIC_LETTERS.contains(char.toString()) || !char.toString().matches("[\\u0400-\\u04FF]".toRegex())
        }
    }

    fun isMacedonian(text: String): Boolean {
        return text.any { char ->
            MACEDONIAN_CYRILLIC_LETTERS.contains(char.toString()) || MACEDONIAN_SPECIFIC_CYRILLIC_LETTERS.contains(char.toString())
        } && text.all { char ->
            MACEDONIAN_CYRILLIC_LETTERS.contains(char.toString()) || MACEDONIAN_SPECIFIC_CYRILLIC_LETTERS.contains(char.toString()) || !char.toString().matches("[\\u0400-\\u04FF]".toRegex())
        }
    }

    fun isJapanese(text: String): Boolean {
        return text.any { char ->
            (char in '\u3040'..'\u309F') || // Hiragana
                    (char in '\u30A0'..'\u30FF') || // Katakana
                    (char in '\u4E00'..'\u9FFF') // CJK Unified Ideographs
        }
    }

    fun isKorean(text: String): Boolean {
        return text.any { char ->
            (char in '\uAC00'..'\uD7A3') // Hangul Syllables
        }
    }

    fun isChinese(text: String): Boolean {
        if (text.isEmpty()) return false
        val cjkCharCount = text.count { char -> char in '\u4E00'..'\u9FFF' }
        val hiraganaKatakanaCount = text.count { char -> (char in '\u3040'..'\u309F') || (char in '\u30A0'..'\u30FF') }
        return cjkCharCount > 0 && (hiraganaKatakanaCount.toDouble() / text.length.toDouble()) < 0.1
    }

    private fun isCyrillicVowel(char: Char): Boolean {
        return "АаЕеЄєИиІіЇїОоУуЮюЯяЫыЭэ".contains(char)
    }
}
