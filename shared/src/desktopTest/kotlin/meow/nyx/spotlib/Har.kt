package meow.nyx.spotlib

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI

data class HarCredentials(
    val cookies: Map<String, String>,
    val accessToken: String?,
    val clientToken: String?,
)

object Har {
    private val json = Json { ignoreUnknownKeys = true }

    fun credentials(harFile: File): HarCredentials {
        val root = json.parseToJsonElement(harFile.readText()).jsonObject
        val entries = root["log"]?.jsonObject?.get("entries")?.jsonArray ?: JsonArray(emptyList())
        var cookies = emptyMap<String, String>()
        var accessToken: String? = null
        var clientToken: String? = null

        for (entry in entries) {
            val request = entry.jsonObject["request"]?.jsonObject ?: continue
            val url = request["url"]?.jsonPrimitive?.content ?: continue
            val host = runCatching { URI(url).host }.getOrNull()

            if (host == "open.spotify.com" && cookies.isEmpty()) {
                cookies = request.cookieHeader()?.let(::parseCookieHeader).orEmpty()
                if (!cookies.containsKey("sp_dc")) cookies = emptyMap()
            }

            if (host == "api-partner.spotify.com" && URI(url).path.startsWith("/pathfinder")) {
                for (header in request["headers"]?.jsonArray ?: JsonArray(emptyList())) {
                    val name = header.jsonObject["name"]?.jsonPrimitive?.content ?: continue
                    val value = header.jsonObject["value"]?.jsonPrimitive?.content ?: continue
                    when {
                        name.equals("authorization", ignoreCase = true) && value.startsWith("Bearer ") -> {
                            accessToken = value.removePrefix("Bearer ")
                        }

                        name.equals("client-token", ignoreCase = true) -> {
                            clientToken = value
                        }
                    }
                }
            }
        }

        if (cookies.isEmpty()) {
            for (entry in entries) {
                val request = entry.jsonObject["request"]?.jsonObject ?: continue
                val url = request["url"]?.jsonPrimitive?.content ?: continue
                if (runCatching { URI(url).host }.getOrNull() != "open.spotify.com") continue
                cookies = request.cookieHeader()?.let(::parseCookieHeader).orEmpty()
                if (cookies.isNotEmpty()) break
            }
        }

        return HarCredentials(cookies, accessToken, clientToken)
    }

    private fun kotlinx.serialization.json.JsonObject.cookieHeader(): String? =
        (get("headers")?.jsonArray ?: JsonArray(emptyList()))
            .firstOrNull { header ->
                header.jsonObject["name"]
                    ?.jsonPrimitive
                    ?.content
                    ?.equals("cookie", ignoreCase = true) == true
            }?.jsonObject
            ?.get("value")
            ?.jsonPrimitive
            ?.content

    private fun parseCookieHeader(value: String): Map<String, String> =
        value
            .split(';')
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) null else part.substring(0, separator).trim() to part.substring(separator + 1).trim()
            }.toMap()
}
