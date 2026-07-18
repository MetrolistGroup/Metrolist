package com.metrolist.music.discord

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DiscordPresenceTest {

    @Test
    fun buildActivity_includesNameAndType() {
        val activity = DiscordPresence.buildActivity(
            name = "Metrolist",
            type = ActivityType.Listening,
            details = "Song Title",
            state = "Artist",
        )
        assertEquals("Metrolist", activity.name)
        assertEquals(ActivityType.Listening.code, activity.type)
        assertEquals("Song Title", activity.details)
        assertEquals("Artist", activity.state)
    }

    @Test
    fun buildPresenceUpdate_serializesAsOp3() {
        val activity = DiscordPresence.buildActivity(
            name = "Metrolist",
            type = ActivityType.Listening,
            details = "Song",
            state = "Artist",
        )
        val json = DiscordPresence.buildPresenceUpdate(
            status = PresenceStatus.Online,
            activities = listOf(activity),
        )
        val root = JSONObject(json)
        assertEquals(3, root.getInt("op"))
        val d = root.getJSONObject("d")
        assertEquals("online", d.getString("status"))
        assertFalse(d.getBoolean("afk"))
        val activities = d.getJSONArray("activities")
        assertEquals(1, activities.length())
        val first = activities.getJSONObject(0)
        assertEquals("Metrolist", first.getString("name"))
        assertEquals(ActivityType.Listening.code, first.getInt("type"))
    }

    @Test
    fun buildPresenceUpdate_emitsTimestampsWhenProvided() {
        val activity = DiscordPresence.buildActivity(
            name = "Metrolist",
            type = ActivityType.Listening,
            startMs = 1000L,
            endMs = 2000L,
        )
        val json = DiscordPresence.buildPresenceUpdate(
            status = PresenceStatus.Online,
            activities = listOf(activity),
        )
        val first = JSONObject(json).getJSONObject("d").getJSONArray("activities").getJSONObject(0)
        val timestamps = first.getJSONObject("timestamps")
        assertEquals(1000L, timestamps.getLong("start"))
        assertEquals(2000L, timestamps.getLong("end"))
    }

    @Test
    fun buildPresenceUpdate_emitsAssetsWhenImagesPresent() {
        val activity = DiscordPresence.buildActivity(
            name = "Metrolist",
            type = ActivityType.Listening,
            largeImage = "mp:external/abc/large",
            largeText = "Big",
            smallImage = "mp:external/abc/small",
            smallText = "Small",
        )
        val json = DiscordPresence.buildPresenceUpdate(
            status = PresenceStatus.Online,
            activities = listOf(activity),
        )
        val first = JSONObject(json).getJSONObject("d").getJSONArray("activities").getJSONObject(0)
        val assets = first.getJSONObject("assets")
        assertEquals("mp:external/abc/large", assets.getString("large_image"))
        assertEquals("Big", assets.getString("large_text"))
        assertEquals("mp:external/abc/small", assets.getString("small_image"))
        assertEquals("Small", assets.getString("small_text"))
    }

    @Test
    fun buildPresenceUpdate_omitsAssetsWhenImagesNull() {
        val activity = DiscordPresence.buildActivity(
            name = "Metrolist",
            type = ActivityType.Listening,
        )
        val json = DiscordPresence.buildPresenceUpdate(
            status = PresenceStatus.Online,
            activities = listOf(activity),
        )
        val first = JSONObject(json).getJSONObject("d").getJSONArray("activities").getJSONObject(0)
        assertFalse("assets should be absent", first.has("assets"))
    }

    @Test
    fun buildPresenceUpdate_emitsButtonsAsStringArrayAndUrlsInMetadata() {
        val activity = DiscordPresence.buildActivity(
            name = "Metrolist",
            type = ActivityType.Listening,
            buttons = listOf(
                "Listen" to "https://example.com/listen",
                "Open" to "https://example.com/open",
            ),
        )
        val json = DiscordPresence.buildPresenceUpdate(
            status = PresenceStatus.Online,
            activities = listOf(activity),
        )
        val first = JSONObject(json).getJSONObject("d").getJSONArray("activities").getJSONObject(0)
        val buttons = first.getJSONArray("buttons")
        assertEquals(2, buttons.length())
        assertEquals("Listen", buttons.getString(0))
        assertEquals("Open", buttons.getString(1))
        val urls = first.getJSONObject("metadata").getJSONArray("button_urls")
        assertEquals(2, urls.length())
        assertEquals("https://example.com/listen", urls.getString(0))
        assertEquals("https://example.com/open", urls.getString(1))
    }

    @Test
    fun buildPresenceUpdate_supportsEmptyActivities() {
        val json = DiscordPresence.buildPresenceUpdate(
            status = PresenceStatus.Invisible,
            activities = emptyList(),
        )
        val d = JSONObject(json).getJSONObject("d")
        assertEquals("invisible", d.getString("status"))
        assertEquals(0, d.getJSONArray("activities").length())
    }

    @Test
    fun buildPresenceUpdate_emitsUrlWhenProvided() {
        val activity = DiscordPresence.buildActivity(
            name = "Metrolist",
            type = ActivityType.Streaming,
            url = "https://example.com/stream",
        )
        val json = DiscordPresence.buildPresenceUpdate(
            status = PresenceStatus.Online,
            activities = listOf(activity),
        )
        val first = JSONObject(json).getJSONObject("d").getJSONArray("activities").getJSONObject(0)
        assertEquals("https://example.com/stream", first.getString("url"))
    }
}
