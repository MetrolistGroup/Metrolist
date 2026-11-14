package com.metrolist.shared.api.lyrics.romanizer

import com.metrolist.shared.model.WordTimestamp
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MandarinRomanizerTest {
    @Test
    fun romanizesChineseLyricsWithBundledDictionary() =
        runTest {
            val line = "音乐，重庆！Hello世界"
            assertEquals(
                "yīn yuè，chóng qìng！Hello shì jiè",
                LyricsRomanizer.romanize(line, line, setOf("Chinese"), false),
            )
            assertNull(LyricsRomanizer.romanize(line, line, emptySet(), false))
        }

    @Test
    fun romanizesChineseWordsWithoutChangingTimestamps() =
        runTest {
            val words = listOf(WordTimestamp("你好", 1.0, 2.0), WordTimestamp("世界", 2.0, 3.0))
            val result =
                assertNotNull(
                    LyricsRomanizer.romanizeWords("你好世界", "你好世界", words, setOf("Chinese"), false),
                )

            assertEquals(listOf(words[0].copy(text = "nǐ hǎo "), words[1].copy(text = "shì jiè")), result.second)
        }

    @Test
    fun preservesDesktopPinyin040Output() {
        // Regression oracle from the PinyinHelper wrapper.
        val corpus =
            listOf(
                "" to "",
                "   " to "",
                "你好世界" to "nǐ hǎo shì jiè",
                "我们一起唱歌" to "wǒ men yì qǐ chàng gē",
                "君" to "jūn",
                "女儿 绿水 略微" to "nǚ ér lǜ shuǐ lüè wēi",
                "音乐 快乐 乐队" to "yīn yuè kuài lè yuè duì",
                "重庆 重复 重要" to "chóng qìng chóng fù zhòng yào",
                "银行 行走 行长" to "yín háng xíng zǒu xíng zhǎng",
                "长大 长江 长发" to "zhǎng dà cháng jiāng cháng fà",
                "着急 看着 睡着" to "zháo jí kàn zhe shuì zháo",
                "还好 还钱" to "hái hǎo huán qián",
                "朝阳 朝代" to "cháo yáng cháo dài",
                "单独 单于 单田芳" to "dān dú chán yú dān tián fāng",
                "薄荷 薄弱" to "bò he bó ruò",
                "便宜 方便" to "pián yí fāng biàn",
                "一不小心 不对 不是" to "yí bù xiǎo xīn bú duì bú shì",
                "什么 我的 朋友" to "shén me wǒ de péng yǒu",
                "地上 土地 地方" to "dì shàng tǔ dì dì fāng",
                "中国人你好" to "zhōng guó rén nǐ hǎo",
                "中华人民共和国" to "zhōng huá rén mín gòng hé guó",
                "你好，世界！再见。" to "nǐ hǎo，shì jiè！zài jiàn。",
                "Hello世界123!你好,world." to "Hello shì jiè 123!nǐ hǎo,world.",
                "hello-world don't C++ foo_bar" to "hello - world don't C + + foo _ bar",
                "你好（世界）《音乐》；再见：好？" to "nǐ hǎo （ shì jiè ） 《 yīn yuè 》 ； zài jiàn ： hǎo？",
                "繁體中文 臺灣 重慶 銀行 音樂" to "fán tǐ zhōng wén tái wān zhòng qìng yín xíng yīn lè",
                "乾坤 乾燥 頭髮 發現" to "qián kūn qián zào tóu fà fā xiàn",
                "㐀〇鿿\ud840\udc00\ud83d\ude00" to "qiū líng 鿿 \ud840 \udc00 \ud83d \ude00",
                "かなカナ 한글 café Привет" to "か な カ ナ 한 글 c a f é П р и в е т",
                "中文\u0009歌词" to "zhōng wén gē cí",
                " A  B " to "A B",
                "你好 世界" to "nǐ hǎo   shì jiè",
                "嗯 呣 哟 唉 哪吒" to "ń ḿ yō āi né zhā",
                "曾经 曾国藩 解放 解缙" to "céng jīng céng guó fān jiě fàng jiě jìn",
                "不一样 一个人 看一看" to "bù yí yàng yí gè rén kàn yī kàn",
                "得到了 跑得快 必须得" to "dé dào le pǎo dé kuài bì xū dé",
                "大夫 丈夫 头发 似的" to "dài fū zhàng fū tóu fà shì de",
            )
        corpus.forEach { (input, expected) -> assertEquals(expected, romanizeMandarin(input), input) }
    }
}
