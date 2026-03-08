package com.metrolist.shared.discord

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscordPresenceTest {
    @Test
    fun richPresenceIncludesApplicationAndButtons() {
        val wireActivity =
            wireActivity(
                DiscordPresence.buildActivity(
                    name = "Metrolist",
                    type = ActivityType.Listening,
                    applicationId = "app-id",
                    buttons = listOf("Listen" to "https://example.com/song"),
                ),
            )

        assertEquals("app-id", wireActivity.text("application_id"))
        assertEquals(
            "Listen",
            wireActivity
                .getValue("buttons")
                .jsonArray
                .single()
                .jsonPrimitive.content,
        )
        assertEquals(
            "https://example.com/song",
            wireActivity
                .getValue("metadata")
                .jsonObject
                .getValue("button_urls")
                .jsonArray
                .single()
                .jsonPrimitive.content,
        )
    }

    @Test
    fun presenceDedupKeepsPlaybackArtworkSeekAndReconnectTransitions() {
        val playing =
            DiscordPresence.buildActivity(
                name = "Metrolist",
                type = ActivityType.Listening,
                details = "Song",
                startMs = 10_000L,
                endMs = 190_000L,
            )

        assertFalse(shouldSendDiscordPresence(playing, null, playing.copy(startMs = 10_100L, endMs = 190_100L), false))
        assertFalse(shouldSendDiscordPresence(null, playing, playing.copy(startMs = 10_100L, endMs = 190_100L), false))
        assertTrue(shouldSendDiscordPresence(playing, null, playing.copy(state = "Paused"), false))
        assertTrue(shouldSendDiscordPresence(playing, null, playing.copy(largeImage = "artwork"), false))
        assertTrue(shouldSendDiscordPresence(playing, null, playing.copy(startMs = 11_000L, endMs = 191_000L), false))
        assertTrue(shouldSendDiscordPresence(playing.copy(endMs = null), null, playing, false))
        assertTrue(shouldSendDiscordPresence(playing, null, playing.copy(endMs = null), false))
        assertFalse(shouldSendDiscordPresence(playing.copy(endMs = null), null, playing.copy(endMs = null), false))
        assertTrue(shouldSendDiscordPresence(playing, null, playing, true))
        assertTrue(shouldSendDiscordPresence(playing, playing.copy(startMs = 50_000L), playing, false))
    }

    @Test
    fun richPresencePreservesIndivisibleFieldsAtLimit() {
        val textAtLimit = "t".repeat(128)
        val activityUrl = "https://example.com/${"a".repeat(492)}"
        val assetUrl = "https://example.com/${"a".repeat(236)}"
        val externalAsset = "mp:external/${"a".repeat(301)}"
        val buttonLabel = "b".repeat(32)
        val wireActivity =
            wireActivity(
                DiscordPresence.buildActivity(
                    name = textAtLimit,
                    type = ActivityType.Listening,
                    details = textAtLimit,
                    state = textAtLimit,
                    url = activityUrl,
                    largeImage = externalAsset,
                    largeText = textAtLimit,
                    largeUrl = assetUrl,
                    smallImage = externalAsset,
                    smallText = textAtLimit,
                    smallUrl = assetUrl,
                    inviteCoverImage = externalAsset,
                    buttons = listOf(buttonLabel to activityUrl, buttonLabel to activityUrl),
                ),
            )

        assertEquals(textAtLimit, wireActivity.text("name"))
        assertEquals(textAtLimit, wireActivity.text("details"))
        assertEquals(textAtLimit, wireActivity.text("state"))
        assertEquals(activityUrl, wireActivity.text("url"))
        val assets = wireActivity.getValue("assets").jsonObject
        assertEquals(externalAsset, assets.text("large_image"))
        assertEquals(textAtLimit, assets.text("large_text"))
        assertEquals(assetUrl, assets.text("large_url"))
        assertEquals(externalAsset, assets.text("small_image"))
        assertEquals(textAtLimit, assets.text("small_text"))
        assertEquals(assetUrl, assets.text("small_url"))
        assertEquals(externalAsset, assets.text("invite_cover_image"))

        val buttons = wireActivity.getValue("buttons").jsonArray
        val buttonUrls =
            wireActivity
                .getValue("metadata")
                .jsonObject
                .getValue("button_urls")
                .jsonArray
        assertEquals(listOf(buttonLabel, buttonLabel), buttons.map { it.jsonPrimitive.content })
        assertEquals(listOf(activityUrl, activityUrl), buttonUrls.map { it.jsonPrimitive.content })
    }

    @Test
    fun richPresenceOmitsOversizedUrlsAndAssetTokens() {
        val activityUrl = "https://example.com/${"a".repeat(492)}"
        val assetUrl = "https://example.com/${"a".repeat(236)}"
        val externalAsset = "mp:external/${"a".repeat(301)}"
        val oversizedActivityUrl = "$activityUrl!"
        val oversizedAssetUrl = "$assetUrl!"
        val oversizedExternalAsset = "$externalAsset!"
        val wireActivity =
            wireActivity(
                DiscordPresence.buildActivity(
                    name = "Track",
                    type = ActivityType.Listening,
                    url = oversizedActivityUrl,
                    largeImage = externalAsset,
                    largeUrl = oversizedAssetUrl,
                    smallImage = oversizedExternalAsset,
                    smallUrl = oversizedAssetUrl,
                    inviteCoverImage = oversizedExternalAsset,
                    buttons = listOf("b".repeat(33) to activityUrl, "Omit" to oversizedActivityUrl),
                ),
            )

        assertNull(wireActivity["url"])
        val assets = wireActivity.getValue("assets").jsonObject
        assertEquals(externalAsset, assets.text("large_image"))
        assertNull(assets["large_url"])
        assertNull(assets["small_image"])
        assertNull(assets["small_url"])
        assertNull(assets["invite_cover_image"])

        val buttons = wireActivity.getValue("buttons").jsonArray
        val buttonUrls =
            wireActivity
                .getValue("metadata")
                .jsonObject
                .getValue("button_urls")
                .jsonArray
        assertEquals(listOf("b".repeat(32)), buttons.map { it.jsonPrimitive.content })
        assertEquals(listOf(activityUrl), buttonUrls.map { it.jsonPrimitive.content })

        val oversizedLargeImage =
            wireActivity(
                DiscordPresence.buildActivity(
                    name = "Track",
                    type = ActivityType.Listening,
                    largeImage = oversizedExternalAsset,
                    smallImage = externalAsset,
                ),
            ).getValue("assets").jsonObject
        assertNull(oversizedLargeImage["large_image"])
        assertEquals(externalAsset, oversizedLargeImage.text("small_image"))
    }

    @Test
    fun richPresenceClampsUnicodeWithoutSplittingSurrogatePairs() {
        val emojiOverLimit = "a" + "😀".repeat(64)
        val wireActivity =
            wireActivity(
                DiscordPresence.buildActivity(
                    name = emojiOverLimit,
                    type = ActivityType.Listening,
                    details = emojiOverLimit,
                    state = emojiOverLimit,
                    largeImage = "asset",
                    largeText = emojiOverLimit,
                    smallImage = "asset",
                    smallText = emojiOverLimit,
                ),
            )
        val expected = "a" + "😀".repeat(63)

        assertEquals(expected, wireActivity.text("name"))
        assertEquals(expected, wireActivity.text("details"))
        assertEquals(expected, wireActivity.text("state"))
        assertEquals(expected, wireActivity.getValue("assets").jsonObject.text("large_text"))
        assertEquals(expected, wireActivity.getValue("assets").jsonObject.text("small_text"))
    }

    private fun wireActivity(activity: ActivityPayload): JsonObject =
        Json
            .parseToJsonElement(
                DiscordPresence.buildPresenceUpdate(PresenceStatus.Online, activities = listOf(activity)),
            ).jsonObject
            .getValue("d")
            .jsonObject
            .getValue("activities")
            .jsonArray
            .single()
            .jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
}
