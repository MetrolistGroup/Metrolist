package com.metrolist.shared.utils

internal const val LOG_TEXT_CHUNK_CHARS = 2_048

internal fun logTextChunks(text: String): List<String> =
    buildList {
        var start = 0
        while (start < text.length) {
            var end = minOf(start + LOG_TEXT_CHUNK_CHARS, text.length)
            if (end < text.length) {
                var newline = end - 1
                while (newline >= start && text[newline] != '\n') newline--
                if (newline >= start) {
                    end = newline + 1
                } else if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) {
                    end--
                }
            }
            add(text.substring(start, end))
            start = end
        }
    }
