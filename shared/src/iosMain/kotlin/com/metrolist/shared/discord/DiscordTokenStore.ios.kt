package com.metrolist.shared.discord

import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.Logger
import com.metrolist.shared.utils.MetrolistClock
import platform.Foundation.NSUUID

actual object DiscordTokenStore {
    private const val TAG = "DiscordSvc"

    private val s: Settings get() = Settings.shared

    actual fun storeFull(
        accessToken: String,
        refreshToken: String,
        expiresInSec: Long,
    ) {
        s.discordAccessToken = accessToken
        s.discordRefreshToken = refreshToken
        val expiresAt = (MetrolistClock.nowMs() / 1000L) + expiresInSec
        s.discordExpiresAt = expiresAt
        Logger.d(TAG, "tokenStore: stored (accessToken length=${accessToken.length})")
    }

    actual fun storeAccessToken(accessToken: String) {
        s.discordAccessToken = accessToken
        Logger.d(TAG, "tokenStore: access token updated")
    }

    actual fun retrieve(): String? = s.discordAccessToken.takeIf { it.isNotEmpty() }

    actual fun getRefreshToken(): String? = s.discordRefreshToken.takeIf { it.isNotEmpty() }

    actual fun getExpiresAt(): Long = s.discordExpiresAt

    actual fun getDeviceVendorId(): String? {
        var id = s.discordDeviceVendorId
        if (id.isEmpty()) {
            id = NSUUID().UUIDString
            s.discordDeviceVendorId = id
        }
        return id
    }

    actual fun getClientUuid(): String? {
        var id = s.discordClientUuid
        if (id.isEmpty()) {
            id = NSUUID().UUIDString
            s.discordClientUuid = id
        }
        return id
    }

    actual fun clear() {
        s.discordAccessToken = ""
        s.discordRefreshToken = ""
        s.discordExpiresAt = 0L
        s.discordDeviceVendorId = ""
        s.discordClientUuid = ""
        Logger.d(TAG, "tokenStore: cleared")
    }
}
