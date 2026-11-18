package com.metrolist.shared.api

import com.metrolist.shared.utils.monthlyAudienceText
import com.metrolist.shared.utils.parseAudienceCount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ArtistAudienceFixturesTest {
    @Test
    fun localizedHeadersDisplayCountsWithOneLabel() {
        val fixtures =
            listOf(
                Fixture("en-US", "59.3M", "240M"),
                Fixture("de-DE", "59,3\u00a0Mio.", "240\u00a0Mio."),
                Fixture("fr-FR", "59,3\u00a0M", "240\u00a0M"),
                Fixture("es-ES", "59,3\u00a0M", "240\u00a0M"),
                Fixture("pt-BR-BR", "59,3\u00a0mi", "240\u00a0mi"),
                Fixture("it-IT", "59,3\u00a0Mln", "240\u00a0Mln"),
                Fixture("nl-NL", "59,3\u00a0mln.", "240\u00a0mln."),
                Fixture("pl-PL", "59,3\u00a0mln", "240\u00a0mln"),
                Fixture("cs-CZ", "59,3\u00a0mil.", "240\u00a0mil."),
                Fixture("ru-RU", "59,3\u00a0млн", "240\u00a0млн"),
                Fixture("uk-UA", "59,3\u00a0млн", "240\u00a0млн"),
                Fixture("tr-TR", "59,3\u00a0Mn", "240\u00a0Mn"),
                Fixture("ar-EG", "59.3\u00a0مليون", "240\u00a0مليون"),
                Fixture("fa-US", "59.3\u00a0میلیون", "240\u00a0میلیون"),
                Fixture("iw-IL", "59.3M", "240M"),
                Fixture("he-IL", "59.3M", "240M"),
                Fixture("hi-IN", "5.93\u00a0क॰", "24\u00a0क॰"),
                Fixture("bn-BD", "5.93\u00a0কো", "24\u00a0কো"),
                Fixture("th-TH", "59.3 ล้าน", "240 ล้าน"),
                Fixture("vi-VN", "59,3\u00a0Tr", "240\u00a0Tr"),
                Fixture("id-ID", "59,3\u00a0jt", "240\u00a0jt"),
                Fixture("ja-JP", "5930万", "2.4億"),
                Fixture("ko-KR", "5930만", "2.4억"),
                Fixture("zh-CN-SG", "5930万", "2.4亿"),
                Fixture("zh-TW-TW", "5930萬", "2.4億"),
                Fixture("hu-HU", "59,3\u00a0M", "240\u00a0M"),
                Fixture("fi-FI", "59,3\u00a0milj.", "240\u00a0milj."),
            )
        for ((locale, subscribers, monthlyAudience) in fixtures) {
            val source = assertNotNull(javaClass.getResourceAsStream("/youtube/artist-counts/$locale.json"), locale)
            val header =
                source.bufferedReader().use { reader ->
                    Json
                        .parseToJsonElement(
                            reader.readText(),
                        ).jsonObject["header"]!!
                        .jsonObject["musicImmersiveHeaderRenderer"]!!
                        .jsonObject
                }
            assertSubscriberDisplay(header, subscribers, locale)
            val subscriptionButton = header["subscriptionButton"]!!.jsonObject["subscribeButtonRenderer"]!!.jsonObject
            val longOnlyHeader =
                JsonObject(
                    header +
                        (
                            "subscriptionButton" to
                                JsonObject(mapOf("subscribeButtonRenderer" to JsonObject(subscriptionButton - "subscriberCountText")))
                        ),
                )
            assertSubscriberDisplay(longOnlyHeader, subscribers, "$locale longSubscriberCountText")
            val parsedMonthlyAudience = YouTubeArtistParser.parseMonthlyListenerCount(header)
            assertEquals(monthlyAudience, parsedMonthlyAudience, locale)
            assertEquals("$monthlyAudience monthly audience", monthlyAudienceText(parsedMonthlyAudience, "monthly audience"), locale)
        }
    }

    @Test
    fun joinsAllRunsAndKeepsUncertainHeaderText() {
        val header =
            Json
                .parseToJsonElement(
                    """{
                    "subscriptionButton":{"subscribeButtonRenderer":{
                        "subscriberCountText":{"runs":[{"text":"جمهور "},{"text":"٢٫٤ "},{"text":"وحدة"}]}
                    }},
                    "monthlyListenerCount":{"runs":[{"text":"月間 "},{"text":"12 "},{"text":"未知"}]}
                }""",
                ).jsonObject
        val subscribers = YouTubeArtistParser.parseSubscriberCount(header)
        assertEquals("جمهور ٢٫٤ وحدة", subscribers)
        assertEquals("جمهور ٢٫٤ وحدة", parseAudienceCount(subscribers)?.withLabel { error("Do not add a label") })
        val monthlyAudience = YouTubeArtistParser.parseMonthlyListenerCount(header)
        assertEquals("月間 12 未知", monthlyAudience)
        assertEquals("月間 12 未知", monthlyAudienceText(monthlyAudience, "monthly audience"))
    }

    private fun assertSubscriberDisplay(
        header: JsonObject,
        expected: String,
        message: String,
    ) {
        val parsed = YouTubeArtistParser.parseSubscriberCount(header)
        assertEquals(expected, parsed, message)
        assertEquals("$expected subscribers", parseAudienceCount(parsed)?.withLabel { "$it subscribers" }, message)
    }

    private data class Fixture(
        val locale: String,
        val subscribers: String,
        val monthlyAudience: String,
    )
}
