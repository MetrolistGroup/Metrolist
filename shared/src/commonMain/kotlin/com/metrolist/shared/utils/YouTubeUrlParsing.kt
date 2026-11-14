package com.metrolist.shared.utils

import com.metrolist.innertubex.utils.YouTubeUrlParser
import io.ktor.http.Url

private val youtubeVideoId = Regex("^[A-Za-z0-9_-]+$")
private val listenTogetherRoomCode = Regex("^[A-Za-z0-9]{8}$")
private val partyCodePattern = Regex("^[A-Za-z0-9_-]{1,64}$")

fun normalizeMetrolistShareUrl(text: String): String {
    val trimmed = text.trim()
    if (trimmed.startsWith("metrolist://", ignoreCase = true)) {
        val afterScheme = trimmed.substring("metrolist://".length).trimStart('/')
        if (afterScheme.startsWith("open?", ignoreCase = true)) {
            val decoded =
                runCatching {
                    Url("https://metrolist.internal/?$afterScheme").parameters["url"]
                }.getOrNull() ?: afterScheme.substringAfter("url=")
            return normalizeMetrolistShareUrl(decoded)
        }
        if (afterScheme.startsWith("http://", ignoreCase = true) || afterScheme.startsWith("https://", ignoreCase = true)) {
            return normalizeMetrolistShareUrl(afterScheme)
        }
        if (afterScheme.startsWith("artist/", ignoreCase = true)) {
            return "https://music.youtube.com/channel/${afterScheme.substring("artist/".length)}"
        }
        if (afterScheme.startsWith("album/", ignoreCase = true)) {
            return "https://music.youtube.com/browse/${afterScheme.substring("album/".length)}"
        }
        return "https://music.youtube.com/$afterScheme"
    }

    val url =
        runCatching {
            Url(if (trimmed.contains("://")) trimmed else "https://$trimmed")
        }.getOrNull() ?: return trimmed

    val host = url.host.lowercase().removeSuffix(".")
    val isMetrolistHost =
        host == "metrolist.cc" || host.endsWith(".metrolist.cc")

    if (isMetrolistHost) {
        val path = url.encodedPath
        when {
            path == "/open" -> {
                val target = url.parameters["url"]
                if (target != null) return normalizeMetrolistShareUrl(target)
            }

            path == "/watch" -> {
                val query = if (url.encodedQuery.isNotEmpty()) "?${url.encodedQuery}" else ""
                return "https://music.youtube.com/watch$query"
            }

            path == "/playlist" -> {
                val query = if (url.encodedQuery.isNotEmpty()) "?${url.encodedQuery}" else ""
                return "https://music.youtube.com/playlist$query"
            }

            path.startsWith("/channel/") || path.startsWith("/browse/") -> {
                return "https://music.youtube.com$path"
            }

            path.startsWith("/artist/") -> {
                val artistId = path.removePrefix("/artist/").substringBefore('/')
                return "https://music.youtube.com/channel/$artistId"
            }

            path.startsWith("/album/") -> {
                val albumId = path.removePrefix("/album/").substringBefore('/')
                return "https://music.youtube.com/browse/$albumId"
            }
        }
    }

    return trimmed
}

fun extractListenTogetherRoomCode(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val trimmed =
        value
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()
    if (trimmed.isEmpty()) return null

    val target =
        if (trimmed.startsWith("metrolist://open?", ignoreCase = true)) {
            val query = trimmed.substringAfter('?')
            val encoded = query.substringAfter("url=", "")
            runCatching { Url("https://internal/?$query").parameters["url"] }.getOrNull()
                ?: encoded.takeIf(String::isNotEmpty) ?: trimmed
        } else {
            trimmed
        }

    val url =
        runCatching {
            Url(
                if (target.startsWith("metrolist://", ignoreCase = true)) {
                    target.replaceFirst(Regex("^metrolist://", RegexOption.IGNORE_CASE), "https://metrolist.cc/")
                } else if (!target.contains("://")) {
                    "https://$target"
                } else {
                    target
                },
            )
        }.getOrNull()

    if (url != null) {
        val host = url.host.lowercase().removeSuffix(".")
        val isMetrolistHost =
            host == "metrolist.cc" || host.endsWith(".metrolist.cc")

        if (isMetrolistHost) {
            val path = url.encodedPath.trim('/')
            val rParam = url.parameters["r"] ?: url.parameters["code"]
            if (path == "party" || path.startsWith("party/")) {
                val candidate = rParam ?: path.removePrefix("party/").substringBefore('/')
                if (candidate.matches(partyCodePattern)) return candidate.uppercase()
            }
            if (path == "listen" || path.startsWith("listen/") || path.startsWith("listen-together/")) {
                val candidate = rParam ?: path.removePrefix("listen/").removePrefix("listen-together/").substringBefore('/')
                if (candidate.matches(listenTogetherRoomCode)) return candidate.uppercase()
            }
        }
    }

    if (target.startsWith("metrolist://", ignoreCase = true)) {
        val afterScheme = target.substring("metrolist://".length).trimStart('/')
        val path = afterScheme.substringBefore('?')
        val query = afterScheme.substringAfter('?', "")
        val rParam =
            query.split('&').firstOrNull { it.startsWith("r=") || it.startsWith("code=") }?.substringAfter('=')
        if (path.startsWith("party")) {
            val candidate = rParam ?: path.removePrefix("party/").substringBefore('/')
            if (candidate.matches(partyCodePattern)) return candidate.uppercase()
        }
        if (path.startsWith("listen-together") || path.startsWith("listen")) {
            val candidate = rParam ?: path.removePrefix("listen-together/").removePrefix("listen/").substringBefore('/')
            if (candidate.matches(listenTogetherRoomCode)) return candidate.uppercase()
        }
    }

    return null
}

fun isListenTogetherUrl(value: String?): Boolean = extractListenTogetherRoomCode(value) != null

fun parseYouTubePodcastId(value: String): String? {
    val normalized = normalizeMetrolistShareUrl(value)
    val url = runCatching { Url(if (normalized.contains("://")) normalized else "https://$normalized") }.getOrNull() ?: return null
    val host = url.host.lowercase().removeSuffix(".")
    if (url.protocol.name !in setOf("http", "https") || (host != "youtube.com" && !host.endsWith(".youtube.com"))) return null
    val path = url.encodedPath.removeSuffix("/")
    if (!path.startsWith("/browse/")) return null
    return path.removePrefix("/browse/").takeIf { it.startsWith("MPSP") && it.length > 4 && it.matches(youtubeVideoId) }
}

fun parseYouTubeUrl(value: String): YouTubeUrlParser.ParsedUrl? {
    val text = value.trim()
    val normalized = normalizeMetrolistShareUrl(text)
    YouTubeUrlParser.parse(normalized)?.let { return it }

    val url =
        runCatching {
            Url(if (normalized.contains("://")) normalized else "https://$normalized")
        }.getOrNull() ?: return null
    val host = url.host.lowercase().removeSuffix(".")
    val id =
        when {
            host == "youtu.be" -> {
                url.encodedPath.trim('/').substringBefore('/')
            }

            host == "youtube.com" || host.endsWith(".youtube.com") -> {
                when {
                    url.encodedPath == "/watch" -> url.parameters["v"]
                    url.encodedPath.startsWith("/shorts/") -> url.encodedPath.removePrefix("/shorts/").substringBefore('/')
                    url.encodedPath.startsWith("/live/") -> url.encodedPath.removePrefix("/live/").substringBefore('/')
                    url.encodedPath.startsWith("/embed/") -> url.encodedPath.removePrefix("/embed/").substringBefore('/')
                    else -> null
                }
            }

            else -> {
                null
            }
        }
    return id?.takeIf(youtubeVideoId::matches)?.let { YouTubeUrlParser.ParsedUrl.Video(it) }
}

fun parseYouTubeStartPositionMs(value: String): Long {
    if (parseYouTubeUrl(value) !is YouTubeUrlParser.ParsedUrl.Video) return 0L
    val text = value.trim()
    val url = runCatching { Url(if (text.contains("://")) text else "https://$text") }.getOrNull() ?: return 0L
    val timestamp =
        url.parameters["t"] ?: url.parameters["start"]
            ?: url.fragment.removePrefix("t=").takeIf { url.fragment.startsWith("t=") } ?: return 0L
    val seconds =
        timestamp.toLongOrNull() ?: run {
            val parts = Regex("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$").matchEntire(timestamp) ?: return 0L
            if (timestamp.isEmpty()) return 0L
            var total = 0L
            for ((part, factor) in parts.groupValues.drop(1).zip(listOf(3600L, 60L, 1L))) {
                val amount = if (part.isEmpty()) 0L else part.toLongOrNull() ?: return 0L
                if (amount > (Long.MAX_VALUE / 1000L - total) / factor) return 0L
                total += amount * factor
            }
            total
        }
    return if (seconds in 0..Long.MAX_VALUE / 1000L) seconds * 1000L else 0L
}
