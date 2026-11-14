package com.metrolist.shared.youtube.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

internal actual fun playbackLogPlatformDetails(): Map<String, String> =
    buildMap {
        put("platform", "android")
        put("sdk", Build.VERSION.SDK_INT.toString())
        put("release", Build.VERSION.RELEASE.orEmpty())
        put("securityPatch", Build.VERSION.SECURITY_PATCH.orEmpty())
        put("manufacturer", Build.MANUFACTURER.orEmpty())
        put("model", Build.MODEL.orEmpty())
        Build.SUPPORTED_ABIS?.takeIf { it.isNotEmpty() }?.let { put("abis", it.joinToString(",")) }
        runCatching {
            val context = AndroidContextProvider.applicationContext
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            put(
                "network",
                when {
                    capabilities == null -> {
                        "none"
                    }

                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                        "wifi"
                    }

                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                        "cellular"
                    }

                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                        "ethernet"
                    }

                    else -> {
                        "other"
                    }
                },
            )
            capabilities?.let { caps ->
                put("networkValidated", caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED).toString())
            }
        }
    }
