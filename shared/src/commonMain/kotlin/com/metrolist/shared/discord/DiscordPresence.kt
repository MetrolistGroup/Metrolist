package com.metrolist.shared.discord

import com.metrolist.shared.utils.MetrolistClock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@Serializable
enum class ActivityType(
    val code: Int,
) {
    @SerialName("0")
    Playing(0),

    @SerialName("1")
    Streaming(1),

    @SerialName("2")
    Listening(2),

    @SerialName("3")
    Watching(3),

    @SerialName("4")
    Custom(4),

    @SerialName("5")
    Competing(5),
}

@Serializable
enum class PresenceStatus(
    val wire: String,
) {
    @SerialName("online")
    Online("online"),

    @SerialName("idle")
    Idle("idle"),

    @SerialName("dnd")
    Dnd("dnd"),

    @SerialName("invisible")
    Invisible("invisible"),
}

@Serializable
data class ActivityPayload(
    val name: String = "",
    val type: Int,
    @SerialName("application_id") val applicationId: String? = null,
    val details: String? = null,
    val state: String? = null,
    val url: String? = null,
    @SerialName("large_image") val largeImage: String? = null,
    @SerialName("large_text") val largeText: String? = null,
    @SerialName("large_url") val largeUrl: String? = null,
    @SerialName("small_image") val smallImage: String? = null,
    @SerialName("small_text") val smallText: String? = null,
    @SerialName("small_url") val smallUrl: String? = null,
    @SerialName("invite_cover_image") val inviteCoverImage: String? = null,
    @SerialName("start_ms") val startMs: Long? = null,
    @SerialName("end_ms") val endMs: Long? = null,
    val buttons: List<Pair<String, String>> = emptyList(),
)

object DiscordPresence {
    private const val MAX_ACTIVITY_TEXT_CODE_UNITS = 128
    private const val MAX_ACTIVITY_URL_CODE_UNITS = 512
    private const val MAX_ASSET_IMAGE_CODE_UNITS = 313
    private const val MAX_ASSET_URL_CODE_UNITS = 256
    private const val MAX_BUTTON_LABEL_CODE_UNITS = 32
    private const val MAX_ACTIVITY_BUTTONS = 2

    private val json = Json { ignoreUnknownKeys = true }

    fun buildActivity(
        name: String = "",
        type: ActivityType,
        applicationId: String? = null,
        details: String? = null,
        state: String? = null,
        url: String? = null,
        largeImage: String? = null,
        largeText: String? = null,
        largeUrl: String? = null,
        smallImage: String? = null,
        smallText: String? = null,
        smallUrl: String? = null,
        inviteCoverImage: String? = null,
        startMs: Long? = null,
        endMs: Long? = null,
        buttons: List<Pair<String, String>> = emptyList(),
    ): ActivityPayload =
        ActivityPayload(
            name = name,
            type = type.code,
            applicationId = applicationId,
            details = details,
            state = state,
            url = url,
            largeImage = largeImage,
            largeText = largeText,
            largeUrl = largeUrl,
            smallImage = smallImage,
            smallText = smallText,
            smallUrl = smallUrl,
            inviteCoverImage = inviteCoverImage,
            startMs = startMs,
            endMs = endMs,
            buttons = buttons,
        )

    fun buildPresenceUpdate(
        status: PresenceStatus,
        afk: Boolean = false,
        since: Long = 0,
        activities: List<ActivityPayload>,
    ): String {
        val payload =
            buildJsonObject {
                put("since", since)
                putJsonArray("activities") {
                    activities.forEach { add(activityToJson(it)) }
                }
                put("status", status.wire)
                put("afk", afk)
            }

        return buildJsonObject {
            put("op", 3)
            put("d", payload)
        }.toString()
    }

    fun validatedButtons(buttons: List<Pair<String, String>>): List<Pair<String, String>> =
        buttons
            .take(MAX_ACTIVITY_BUTTONS)
            .mapNotNull { (label, url) ->
                url.takeIfWithinDiscordFieldLimit(MAX_ACTIVITY_URL_CODE_UNITS)?.let {
                    label.truncateDiscordField(MAX_BUTTON_LABEL_CODE_UNITS) to it
                }
            }

    private fun activityToJson(activity: ActivityPayload): JsonObject {
        val buttons = validatedButtons(activity.buttons)
        val activityUrl = activity.url.takeIfWithinDiscordFieldLimit(MAX_ACTIVITY_URL_CODE_UNITS)
        val largeImage = activity.largeImage.takeIfWithinDiscordFieldLimit(MAX_ASSET_IMAGE_CODE_UNITS)
        val largeUrl = activity.largeUrl.takeIfWithinDiscordFieldLimit(MAX_ASSET_URL_CODE_UNITS)
        val smallImage = activity.smallImage.takeIfWithinDiscordFieldLimit(MAX_ASSET_IMAGE_CODE_UNITS)
        val smallUrl = activity.smallUrl.takeIfWithinDiscordFieldLimit(MAX_ASSET_URL_CODE_UNITS)
        val inviteCoverImage = activity.inviteCoverImage.takeIfWithinDiscordFieldLimit(MAX_ASSET_IMAGE_CODE_UNITS)

        return buildJsonObject {
            put("name", activity.name.truncateDiscordField(MAX_ACTIVITY_TEXT_CODE_UNITS))
            put("type", activity.type)
            activity.applicationId?.let { put("application_id", it) }
            put("created_at", MetrolistClock.nowMs())
            activity.details?.let { put("details", it.truncateDiscordField(MAX_ACTIVITY_TEXT_CODE_UNITS)) }
            activity.state?.let { put("state", it.truncateDiscordField(MAX_ACTIVITY_TEXT_CODE_UNITS)) }
            activityUrl?.let { put("url", it) }

            if (activity.startMs != null || activity.endMs != null) {
                put(
                    "timestamps",
                    buildJsonObject {
                        activity.startMs?.let { put("start", it) }
                        activity.endMs?.let { put("end", it) }
                    },
                )
            }

            if (largeImage != null || smallImage != null) {
                put(
                    "assets",
                    buildJsonObject {
                        largeImage?.let { put("large_image", it) }
                        activity.largeText?.let { put("large_text", it.truncateDiscordField(MAX_ACTIVITY_TEXT_CODE_UNITS)) }
                        largeUrl?.let { put("large_url", it) }
                        smallImage?.let { put("small_image", it) }
                        activity.smallText?.let { put("small_text", it.truncateDiscordField(MAX_ACTIVITY_TEXT_CODE_UNITS)) }
                        smallUrl?.let { put("small_url", it) }
                        inviteCoverImage?.let { put("invite_cover_image", it) }
                    },
                )
            }

            if (buttons.isNotEmpty()) {
                putJsonArray("buttons") {
                    buttons.forEach { add(it.first) }
                }
                put(
                    "metadata",
                    buildJsonObject {
                        putJsonArray("button_urls") {
                            buttons.forEach { add(it.second) }
                        }
                    },
                )
            }
        }
    }

    private fun String?.takeIfWithinDiscordFieldLimit(maxCodeUnits: Int): String? = this?.takeIf { it.length <= maxCodeUnits }

    private fun String.truncateDiscordField(maxCodeUnits: Int): String {
        if (length <= maxCodeUnits) return this
        // Discord applies its limits to UTF-16 code units; do not leave a lone surrogate.
        val end = if (this[maxCodeUnits - 1] in '\uD800'..'\uDBFF') maxCodeUnits - 1 else maxCodeUnits
        return substring(0, end)
    }
}
