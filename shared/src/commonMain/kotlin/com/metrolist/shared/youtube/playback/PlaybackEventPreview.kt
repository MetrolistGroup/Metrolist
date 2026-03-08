package com.metrolist.shared.youtube.playback

internal const val PLAYBACK_EVENT_PREVIEW_CHARS = 4_096

internal fun PlaybackEvent.previewText(formatTimestamp: (Long) -> String): String =
    buildString {
        var truncated = false

        fun appendPreview(value: String) {
            val count = minOf(value.length, PLAYBACK_EVENT_PREVIEW_CHARS - length)
            append(value, 0, count)
            if (count < value.length) truncated = true
        }

        appendPreview(formatTimestamp(timestampMs))
        appendPreview(" ${level.name} ${kind.name}")
        clientName?.takeIf(String::isNotBlank)?.let {
            appendPreview(" client=")
            appendPreview(it)
        }
        profileId?.takeIf(String::isNotBlank)?.let {
            appendPreview(" profile=")
            appendPreview(it)
        }
        videoId?.takeIf(String::isNotBlank)?.let {
            appendPreview(" videoId=")
            appendPreview(it)
        }
        appendPreview("\n  ")
        appendPreview(message)
        if (length == PLAYBACK_EVENT_PREVIEW_CHARS) {
            if (details.isNotEmpty()) truncated = true
        } else {
            for ((key, value) in details.entries.sortedBy { it.key }) {
                if (length == PLAYBACK_EVENT_PREVIEW_CHARS) {
                    truncated = true
                    break
                }
                appendPreview("\n  ")
                appendPreview(key)
                appendPreview("=")
                appendPreview(value)
            }
        }
        if (truncated) {
            setLength(length - if (this[length - 2].isHighSurrogate()) 2 else 1)
            append('…')
        }
    }
