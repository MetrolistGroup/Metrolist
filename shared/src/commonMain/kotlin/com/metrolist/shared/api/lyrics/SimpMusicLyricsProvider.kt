/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

class SimpMusicLyricsProvider(
    private val httpClient: HttpClient,
) {
    private val baseUrl = "https://api-lyrics.simpmusic.org/v1"
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    suspend fun getLyrics(
        videoId: String,
        duration: Long? = null,
    ): String? {
        val responseText =
            httpClient
                .prepareGet("$baseUrl/$videoId") {
                    expectMissingLyrics()
                    header("User-Agent", "SimpMusicLyrics/1.0")
                    header("Accept", "application/json")
                }.readLyricsText() ?: return null

        check(responseText.isNotBlank()) { "Empty lyrics response" }
        val root = json.parseToJsonElement(responseText).jsonObject
        check(root["type"]?.jsonPrimitive?.content == "success") { "Lyrics lookup failed" }

        val data = checkNotNull(root["data"]?.jsonArray) { "Missing lyrics data" }
        if (data.isEmpty()) return null

        val tracks =
            if (duration != null && duration > 0) {
                val dSec = (duration / 1000).toInt()
                val filtered = data.filter { abs((it.jsonObject["durationSeconds"]?.jsonPrimitive?.intOrNull ?: 0) - dSec) <= 10 }
                filtered.ifEmpty { data.toList() }
            } else {
                data.toList()
            }

        val best =
            if (duration != null) {
                val dSec = (duration / 1000).toInt()
                tracks.minByOrNull { abs((it.jsonObject["durationSeconds"]?.jsonPrimitive?.intOrNull ?: 0) - dSec) }
            } else {
                tracks.firstOrNull() ?: return null
            }

        val obj = best!!.jsonObject
        val rawLyrics =
            obj["richSyncLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: obj["syncedLyrics"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return null

        return lrcToMlx(rawLyrics.removeNoobSyncCredit(), null, null)
    }

    private fun String.removeNoobSyncCredit(): String =
        lines()
            .filterNot { line ->
                line
                    .trim()
                    .replace(Regex("""^(?:\[\d{1,2}:\d{2}\.\d{2,3}])+"""), "")
                    .trim()
                    .equals("Synced by Noob.exe", ignoreCase = true)
            }.joinToString("\n")
            .trim()
}
