package com.metrolist.shared.discord

expect object DiscordTokenStore {
    fun storeFull(
        accessToken: String,
        refreshToken: String,
        expiresInSec: Long,
    )

    fun storeAccessToken(accessToken: String)

    fun retrieve(): String?

    fun getRefreshToken(): String?

    fun getExpiresAt(): Long

    fun getDeviceVendorId(): String?

    fun getClientUuid(): String?

    fun clear()
}
