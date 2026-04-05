/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

object HifiApi {
    private const val TAG = "HifiApi"

    private val httpClient = OkHttpClient()

    fun resolveStreamUrlOrNull(
        apiBaseUrl: String,
        videoId: String,
        quality: String,
    ): String? {
        val endpoint = apiBaseUrl.trim().trimEnd('/')
        if (endpoint.isBlank()) return null

        return runCatching {
            val requestUrl = "$endpoint/stream?videoId=$videoId&quality=$quality"
            val request = Request.Builder().url(requestUrl).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                JSONObject(body).optString("url").takeIf { it.isNotBlank() }
            }
        }.onFailure {
            Timber.tag(TAG).w(it, "Failed to resolve HiFi stream URL")
        }.getOrNull()
    }
}
