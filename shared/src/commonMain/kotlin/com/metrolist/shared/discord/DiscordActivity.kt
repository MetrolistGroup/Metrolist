package com.metrolist.shared.discord

import kotlinx.serialization.Serializable

@Serializable
data class DiscordActivity(
    val activityType: Int = 2,
    val name: String?,
    val state: String,
    val details: String?,
    val startTimestamp: Long,
    val endTimestamp: Long?,
    val largeImage: String?,
    val largeText: String?,
    val smallImage: String?,
    val smallText: String?,
    val button1Label: String?,
    val button1Url: String?,
    val button2Label: String?,
    val button2Url: String?,
) {
    companion object {
        const val TYPE_LISTENING = 2
    }
}
