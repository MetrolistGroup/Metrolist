/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * KizzyRPC.kt is part of Kizzy
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */

package com.my.kizzy.rpc

import com.my.kizzy.gateway.DiscordWebSocket
import com.my.kizzy.gateway.entities.presence.Activity
import com.my.kizzy.gateway.entities.presence.Assets
import com.my.kizzy.gateway.entities.presence.Metadata
import com.my.kizzy.gateway.entities.presence.Presence
import com.my.kizzy.gateway.entities.presence.Timestamps
import com.my.kizzy.repository.KizzyRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import org.json.JSONObject

/**
 * Modified by Zion Huang
 */
open class KizzyRPC(
    token: String,
    os: String = "Android",
    browser: String = "Discord Android",
    device: String = "Generic Android Device",
    userAgent: String = "Discord-Android/314013;RNA",
    superPropertiesBase64: String? = null
) {
    private val kizzyRepository = KizzyRepository(userAgent, superPropertiesBase64)
    private val discordWebSocket = DiscordWebSocket(token, os, browser, device)

    fun closeRPC() {
        discordWebSocket.close()
    }

    fun isRpcRunning(): Boolean {
        return discordWebSocket.isWebSocketConnected()
    }

    open suspend fun close() {
        if (!isRpcRunning()) {
            discordWebSocket.connect()
        }
        val presence = Presence(
            activities = emptyList()
        )
        discordWebSocket.sendActivity(presence)
    }

    suspend fun setActivity(
        name: String,
        state: String?,
        stateUrl: String? = null,
        details: String?,
        detailsUrl: String? = null,
        largeImage: RpcImage?,
        smallImage: RpcImage?,
        largeText: String? = null,
        smallText: String? = null,
        buttons: List<Pair<String, String>>? = null,
        startTime: Long? = null,
        endTime: Long? = null,
        type: Type = Type.LISTENING,
        statusDisplayType: StatusDisplayType = StatusDisplayType.NAME,
        streamUrl: String? = null,
        applicationId: String? = null,
        status: String? = "online",
        since: Long? = null,
    ) {
        if (!isRpcRunning()) {
            discordWebSocket.connect()
        }
        
        val images = listOfNotNull(largeImage, smallImage)
        val externalImages = images.filterIsInstance<RpcImage.ExternalImage>()
        val imageUrls = externalImages.map { it.image }
        val resolvedImages = kizzyRepository.getImages(imageUrls)?.results?.associate { it.originalUrl to it.id } ?: emptyMap()

        val presence = Presence(
            activities = listOf(
                Activity(
                    name = name,
                    state = state,
                    stateUrl = stateUrl,
                    details = details,
                    detailsUrl = detailsUrl,
                    type = type.value,
                    statusDisplayType = statusDisplayType.value,
                    timestamps = Timestamps(startTime, endTime),
                    assets = Assets(
                        largeImage = largeImage?.let { 
                            when (it) {
                                is RpcImage.DiscordImage -> "mp:${it.image}"
                                is RpcImage.ExternalImage -> resolvedImages[it.image]
                            }
                        },
                        smallImage = smallImage?.let { 
                            when (it) {
                                is RpcImage.DiscordImage -> "mp:${it.image}"
                                is RpcImage.ExternalImage -> resolvedImages[it.image]
                            }
                        },
                        largeText = largeText,
                        smallText = smallText
                    ),
                    buttons = buttons?.map { it.first },
                    metadata = Metadata(buttonUrls = buttons?.map { it.second }),
                    applicationId = applicationId.takeIf { !buttons.isNullOrEmpty() },
                    url = streamUrl
                )
            ),
            afk = true,
            since = since,
            status = status ?: "online"
        )
        discordWebSocket.sendActivity(presence)
    }

    enum class Type(val value: Int) {
        PLAYING(0),
        STREAMING(1),
        LISTENING(2),
        WATCHING(3),
        COMPETING(5)
    }

    enum class StatusDisplayType(val value: Int) {
        NAME(0),
        STATE(1),
        DETAILS(2)
    }

    companion object {
        suspend fun getUserInfo(
            token: String,
            userAgent: String = "Discord-Android/314013;RNA",
            superPropertiesBase64: String? = null
        ): Result<UserInfo> = runCatching {
            val client = HttpClient()
            val response = client.get("https://discord.com/api/v9/users/@me") {
                header("Authorization", token)
                header("User-Agent", userAgent)
                if (superPropertiesBase64 != null) {
                    header("X-Super-Properties", superPropertiesBase64)
                }
            }.bodyAsText()
            val json = JSONObject(response)
            val username = json.getString("username")
            val name = json.optString("global_name", username)
            client.close()

            UserInfo(username, name)
        }
    }
}
