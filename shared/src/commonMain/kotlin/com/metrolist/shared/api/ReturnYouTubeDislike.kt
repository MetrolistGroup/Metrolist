/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api

import com.metrolist.shared.state.GlobalYouTube
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
data class VideoVotes(
    val likes: Long = 0,
    val dislikes: Long = 0,
    val viewCount: Long = 0,
    val deleted: Boolean = false,
)

/** Third-party vote counts from returnyoutubedislikeapi.com; dislikes are the service's estimates. */
object ReturnYouTubeDislike {
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, VideoVotes>()

    suspend fun votes(videoId: String): VideoVotes? {
        mutex.withLock { cache[videoId] }?.let { return it }
        val votes =
            try {
                val response =
                    GlobalYouTube.client.get("https://returnyoutubedislikeapi.com/Votes") {
                        parameter("videoId", videoId)
                        timeout { requestTimeoutMillis = 5_000 }
                    }
                if (response.status != HttpStatusCode.OK) return null
                repositoryJson.decodeFromString<VideoVotes>(response.bodyAsText())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            }
        // The service answers unknown IDs with all-zero counts.
        if (votes.deleted || votes.viewCount <= 0) return null
        mutex.withLock {
            if (cache.size >= 64) cache.remove(cache.keys.first())
            cache[videoId] = votes
        }
        return votes
    }
}
