package com.metrolist.music.utils

import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.util.UUID
import java.util.Locale

object SuperProperties {
    // Constants from research for Discord Android 314.13
    private const val CLIENT_VERSION = "314.13 - Stable"
    private const val CLIENT_BUILD_NUMBER = 314013
    private const val RELEASE_CHANNEL = "googleRelease"
    
    // Lazy loaded properties to avoid re-generating UUIDs
    val superProperties: JSONObject by lazy {
        JSONObject().apply {
            put("os", "Android")
            put("browser", "Discord Android")
            put("device", Build.DEVICE)
            put("system_locale", Locale.getDefault().toString())
            put("client_version", CLIENT_VERSION)
            put("release_channel", RELEASE_CHANNEL)
            put("device_vendor_id", UUID.randomUUID().toString())
            put("client_uuid", UUID.randomUUID().toString())
            put("client_launch_id", UUID.randomUUID().toString())
            put("os_version", Build.VERSION.RELEASE)
            put("os_sdk_version", Build.VERSION.SDK_INT.toString())
            put("client_build_number", CLIENT_BUILD_NUMBER)
            put("client_event_source", JSONObject.NULL)
            put("design_id", 0)
        }
    }

    val superPropertiesBase64: String by lazy {
        val jsonString = superProperties.toString()
        Base64.encodeToString(jsonString.toByteArray(), Base64.NO_WRAP)
    }

    val userAgent: String by lazy {
        "Discord-Android/$CLIENT_BUILD_NUMBER;RNA"
    }
}
