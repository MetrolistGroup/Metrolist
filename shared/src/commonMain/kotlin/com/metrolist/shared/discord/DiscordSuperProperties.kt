package com.metrolist.shared.discord

import com.metrolist.shared.utils.Logger
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid

expect fun getSystemLocale(): String

expect val platformOs: String
expect val platformBrowser: String
expect val platformDevice: String
expect val platformOsVersion: String
expect val platformOsSdkVersion: String
expect val platformUserAgent: String

object DiscordSuperProperties {
    private const val TAG = "DiscordSvc"
    private const val CLIENT_VERSION = "314.13 - Stable"
    private const val CLIENT_BUILD_NUMBER = 314013
    private const val RELEASE_CHANNEL = "googleRelease"

    val USER_AGENT: String
        get() = platformUserAgent

    @Volatile
    private var _superProperties: JsonObject? = null

    private fun buildSuperProperties(): JsonObject {
        val vendorId = DiscordTokenStore.getDeviceVendorId() ?: Uuid.random().toString()
        val clientUuid = DiscordTokenStore.getClientUuid() ?: Uuid.random().toString()

        Logger.d(TAG, "superProperties: building (vendorId=${vendorId.take(8)})")

        return buildJsonObject {
            put("os", platformOs)
            put("browser", platformBrowser)
            put("device", platformDevice)
            put("system_locale", getSystemLocale())
            put("client_version", CLIENT_VERSION)
            put("release_channel", RELEASE_CHANNEL)
            put("device_vendor_id", vendorId)
            put("client_uuid", clientUuid)
            put("client_launch_id", Uuid.random().toString())
            put("os_version", platformOsVersion)
            put("os_sdk_version", platformOsSdkVersion)
            put("client_build_number", CLIENT_BUILD_NUMBER)
            put("client_event_source", JsonNull)
            put("design_id", 0)
        }
    }

    val superProperties: JsonObject
        get() {
            if (_superProperties == null) {
                _superProperties = buildSuperProperties()
            }
            return _superProperties!!
        }

    @Volatile
    private var _base64: String? = null

    val base64: String
        @OptIn(ExperimentalEncodingApi::class)
        get() {
            if (_base64 == null) {
                _base64 = Base64.encode(superProperties.toString().encodeToByteArray())
                Logger.d(TAG, "superProperties: base64 encoded (length=${_base64!!.length})")
            }
            return _base64!!
        }

    fun reset() {
        _superProperties = null
        _base64 = null
    }
}
