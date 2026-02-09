package com.my.kizzy.gateway.entities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

import com.my.kizzy.gateway.entities.presence.Presence

@Serializable
data class Identify(
    @SerialName("capabilities")
    val capabilities: Int,
    @SerialName("compress")
    val compress: Boolean,
    @SerialName("largeThreshold")
    val largeThreshold: Int,
    @SerialName("properties")
    val properties: Properties,
    @SerialName("client_state")
    val clientState: ClientState = ClientState(),
    @SerialName("presence")
    val presence: Presence? = null,
    @SerialName("shard")
    val shard: List<Int> = listOf(0, 1),
    @SerialName("token")
    val token: String,
) {
    companion object {
        fun String.toIdentifyPayload(
            os: String = "Android",
            browser: String = "Discord Android",
            device: String = "Generic Android Device"
        ) = Identify(
            capabilities = 16381,
            compress = false,
            largeThreshold = 100,
            properties = Properties(
                os = os,
                browser = browser,
                device = device
            ),
            presence = Presence(
                status = "online",
                since = 0,
                activities = emptyList(),
                afk = false
            ),
            token = this
        )
    }
}

@Serializable
data class ClientState(
    @SerialName("guild_versions")
    val guildVersions: Map<String, String> = emptyMap(),
    @SerialName("highest_last_message_id")
    val highestLastMessageId: String = "0",
    @SerialName("read_state_version")
    val readStateVersion: Int = 0,
    @SerialName("user_guild_settings_version")
    val userGuildSettingsVersion: Int = -1,
    @SerialName("user_settings_version")
    val userSettingsVersion: Int = -1,
    @SerialName("private_channels_version")
    val privateChannelsVersion: String = "0",
    @SerialName("api_code_version")
    val apiCodeVersion: Int = 0,
)

@Serializable
data class Properties(
    @SerialName("browser")
    val browser: String,
    @SerialName("device")
    val device: String,
    @SerialName("os")
    val os: String,
)