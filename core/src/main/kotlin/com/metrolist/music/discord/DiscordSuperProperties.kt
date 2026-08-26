package com.metrolist.music.discord

import android.os.Build
import android.util.Base64
import org.json.JSONObject
import timber.log.Timber
import java.util.Locale
import java.util.UUID

object DiscordSuperProperties {
    private const val TAG = "DiscordSvc"
    private const val CLIENT_VERSION = "314.13 - Stable"
    private const val CLIENT_BUILD_NUMBER = 314013
    private const val RELEASE_CHANNEL = "googleRelease"

    const val USER_AGENT = "Discord-Android/$CLIENT_BUILD_NUMBER;RNA"

    @Volatile
    private var _superProperties: JSONObject? = null

    private fun buildSuperProperties(): JSONObject {
        val vendorId = DiscordTokenStore.getDeviceVendorId()
            ?: UUID.randomUUID().toString()
        val clientUuid = DiscordTokenStore.getClientUuid()
            ?: UUID.randomUUID().toString()

        Timber.tag(TAG).d(
            "superProperties: building (device=%s, os=%s, sdk=%d, vendorId=%s)",
            Build.DEVICE, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, vendorId.take(8),
        )

        return JSONObject().apply {
            put("os", "Android")
            put("browser", "Discord Android")
            put("device", Build.DEVICE)
            put("system_locale", Locale.getDefault().toString())
            put("client_version", CLIENT_VERSION)
            put("release_channel", RELEASE_CHANNEL)
            put("device_vendor_id", vendorId)
            put("client_uuid", clientUuid)
            put("client_launch_id", UUID.randomUUID().toString())
            put("os_version", Build.VERSION.RELEASE)
            put("os_sdk_version", Build.VERSION.SDK_INT.toString())
            put("client_build_number", CLIENT_BUILD_NUMBER)
            put("client_event_source", JSONObject.NULL)
            put("design_id", 0)
        }
    }

    val superProperties: JSONObject
        get() {
            if (_superProperties == null) {
                _superProperties = buildSuperProperties()
            }
            return _superProperties!!
        }

    @Volatile
    private var _base64: String? = null

    val base64: String
        get() {
            if (_base64 == null) {
                _base64 = Base64.encodeToString(
                    superProperties.toString().toByteArray(),
                    Base64.NO_WRAP,
                )
                Timber.tag(TAG).d("superProperties: base64 encoded (length=%d)", _base64!!.length)
            }
            return _base64!!
        }

    fun reset() {
        _superProperties = null
        _base64 = null
    }
}
