/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.listentogether

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ListenTogetherServer(
    val name: String,
    val url: String,
    val location: String,
    val operator: String
)

object ListenTogetherServers {
    private const val ServersJson = """
        [
          {
            "name": "The Meowery",
            "url": "wss://metroserverx.meowery.eu/ws",
            "location": "Poland",
            "operator": "Nyx"
          }
        ]
    """

    private val json = Json { ignoreUnknownKeys = true }

    val servers: List<ListenTogetherServer> by lazy {
        json.decodeFromString(ServersJson)
    }

    val defaultServerUrl: String
        get() = servers.first().url

    fun findByUrl(url: String): ListenTogetherServer? = servers.firstOrNull { it.url == url }
}
