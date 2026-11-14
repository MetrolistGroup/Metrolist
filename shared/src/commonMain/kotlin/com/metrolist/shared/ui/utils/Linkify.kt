package com.metrolist.shared.ui.utils

import com.metrolist.shared.utils.getUrlHost

data class LinkSegment(
    val text: String,
    val url: String? = null,
)

private val URL_REGEX = Regex("""(?:https?:)?//[^\s<>"]+""")

fun String.toLinkSegments(): List<LinkSegment> {
    if (isBlank()) return emptyList()
    val segments = mutableListOf<LinkSegment>()
    var lastIndex = 0
    URL_REGEX.findAll(this).forEach { match ->
        val matchStart = match.range.first
        val matchEnd = match.range.last + 1
        val rawUrl = substring(matchStart, matchEnd)
        val closingQuote = if (matchStart > 0 && this[matchStart - 1] == '\'' && rawUrl.endsWith("'")) "'" else ""
        val (url, trailingUrlPunctuation) = extractCleanUrlAndTrailing(rawUrl.removeSuffix(closingQuote))
        val trailingPunctuation = trailingUrlPunctuation + closingQuote
        val label = linkLabel(url)
        val plainBeforeEnd =
            if (matchStart > 0 && this[matchStart - 1] == '(' && trailingPunctuation.startsWith(")")) {
                matchStart - 1
            } else {
                matchStart
            }
        val plainText = substring(lastIndex, plainBeforeEnd)

        if (isCreativeCommonsUrl(url)) {
            val occ = findLicensePhraseStart(plainText)
            if (occ >= 0) {
                val prefix = plainText.substring(0, occ).trimEnd(' ', '(')
                if (prefix.isNotBlank()) segments.add(LinkSegment(prefix + if (prefix.endsWith(" ")) "" else " "))
                segments.add(LinkSegment(plainText.substring(occ).trim(' ', '(', ')'), url))
            } else {
                val cleanedPlain = plainText.trimEnd(' ', '(')
                if (cleanedPlain.isNotBlank()) {
                    segments.add(LinkSegment(cleanedPlain + if (cleanedPlain.endsWith(" ")) "" else " "))
                }
                segments.add(LinkSegment(label, url))
            }
        } else {
            val occ = findLastWord(plainText, label)
            if (occ >= 0) {
                val wordEnd = occ + label.length
                val prefix = plainText.substring(0, occ).trimEnd(' ', '(')
                if (prefix.isNotBlank()) segments.add(LinkSegment(prefix + if (prefix.endsWith(" ")) "" else " "))
                segments.add(LinkSegment(plainText.substring(occ, wordEnd), url))
                val suffix = plainText.substring(wordEnd).trim(' ', '(', ')')
                if (suffix.isNotBlank()) segments.add(LinkSegment(" $suffix"))
            } else {
                val cleanedPlain = plainText.trimEnd(' ', '(')
                if (cleanedPlain.isNotBlank()) {
                    segments.add(LinkSegment(cleanedPlain + if (cleanedPlain.endsWith(" ")) "" else " "))
                }
                segments.add(LinkSegment(label, url))
            }
        }
        val consumedUrlLength = matchEnd - matchStart - trailingPunctuation.length
        val afterUrlIndex = matchStart + consumedUrlLength
        lastIndex =
            if (plainBeforeEnd < matchStart && trailingPunctuation.startsWith(")")) {
                afterUrlIndex + 1
            } else {
                afterUrlIndex
            }
    }
    if (lastIndex < length) {
        val remaining = substring(lastIndex).trimStart(' ', ')')
        if (remaining.isNotBlank()) {
            segments.add(LinkSegment(remaining))
        }
    }
    return segments.filterNot { it.text.isBlank() }
}

private fun extractCleanUrlAndTrailing(rawMatchedText: String): Pair<String, String> {
    var url = rawMatchedText
    var trailing = ""

    while (url.isNotEmpty()) {
        val lastChar = url.last()
        when {
            lastChar in ".,;:!?" -> {
                trailing = lastChar + trailing
                url = url.dropLast(1)
            }

            lastChar == ')' -> {
                val openCount = url.count { it == '(' }
                val closeCount = url.count { it == ')' }
                if (closeCount > openCount) {
                    trailing = lastChar + trailing
                    url = url.dropLast(1)
                } else {
                    break
                }
            }

            lastChar == ']' -> {
                val openCount = url.count { it == '[' }
                val closeCount = url.count { it == ']' }
                if (closeCount > openCount) {
                    trailing = lastChar + trailing
                    url = url.dropLast(1)
                } else {
                    break
                }
            }

            else -> {
                break
            }
        }
    }
    return Pair(normalizeUrl(url), trailing)
}

private fun findLastWord(
    text: String,
    word: String,
): Int {
    var idx = text.lastIndexOf(word, ignoreCase = true)
    while (idx >= 0) {
        val before = if (idx == 0) ' ' else text[idx - 1]
        val after = if (idx + word.length >= text.length) ' ' else text[idx + word.length]
        if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) return idx
        idx = text.lastIndexOf(word, idx - 1, ignoreCase = true)
    }
    return -1
}

private fun linkLabel(url: String): String =
    runCatching {
        val host = getUrlHost(url)?.lowercase() ?: return url
        when {
            host.matchesDomain("wikipedia.org") -> "Wikipedia"
            host.matchesDomain("creativecommons.org") -> creativeCommonsLicenseLabel(url) ?: "Creative Commons"
            host.matchesDomain("last.fm") -> "Last.fm"
            host.matchesDomain("musicbrainz.org") -> "MusicBrainz"
            host.matchesDomain("discogs.com") -> "Discogs"
            host.matchesDomain("facebook.com") -> "Facebook"
            host.matchesDomain("instagram.com") -> "Instagram"
            host.matchesDomain("twitter.com") || host.matchesDomain("x.com") -> "X"
            host.matchesDomain("youtube.com") || host.matchesDomain("youtu.be") -> "YouTube"
            host.matchesDomain("tiktok.com") -> "TikTok"
            host.matchesDomain("spotify.com") -> "Spotify"
            else -> host.removePrefix("www.")
        }
    }.getOrDefault(url)

private fun normalizeUrl(url: String): String = if (url.startsWith("//")) "https:$url" else url

private fun String.matchesDomain(domain: String): Boolean = this == domain || endsWith(".$domain")

private fun isCreativeCommonsUrl(url: String): Boolean = getUrlHost(url)?.lowercase()?.matchesDomain("creativecommons.org") == true

private fun findLicensePhraseStart(text: String): Int {
    val match = Regex("(?i)(creative commons|\\bcc\\b)").find(text) ?: return -1
    return match.range.first
}

private fun creativeCommonsLicenseLabel(url: String): String? {
    val match =
        Regex("""creativecommons\.org/(?:licenses|publicdomain)/([^/\s]+)(?:/([^/\s]+))?""").find(url)
            ?: return null
    val license = match.groupValues[1]
    val version = match.groupValues[2].takeIf { it.isNotBlank() } ?: return null
    return when (license) {
        "zero" -> {
            "CC0 $version"
        }

        else -> {
            val codes = license.split("-")
            if (codes.isEmpty()) return null
            "CC " + codes.joinToString("-") { it.uppercase() } + " $version"
        }
    }
}
