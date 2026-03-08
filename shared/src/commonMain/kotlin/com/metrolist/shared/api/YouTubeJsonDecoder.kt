package com.metrolist.shared.api

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal fun decodeYouTubeJson(text: String): JsonObject =
    try {
        YouTubeJsonReader(text).read().jsonObject
    } catch (_: UnsupportedJson) {
        repositoryJson.parseToJsonElement(text).jsonObject
    }

private class UnsupportedJson : Exception()

private class YouTubeJsonReader(
    private val text: String,
) {
    private var offset = 0

    fun read(): JsonElement {
        val result = value(retain = true, depth = 0)
        whitespace()
        if (offset != text.length) throw UnsupportedJson()
        return result
    }

    private fun value(
        retain: Boolean,
        depth: Int,
    ): JsonElement {
        if (depth > 128) throw UnsupportedJson()
        whitespace()
        return when (peek()) {
            '{' -> {
                offset++
                val fields = if (retain) linkedMapOf<String, JsonElement>() else null
                whitespace()
                if (!take('}')) {
                    do {
                        whitespace()
                        if (peek() != '"') throw UnsupportedJson()
                        val key = string(retain)
                        whitespace()
                        if (!take(':')) throw UnsupportedJson()
                        val keep = retain && !isTelemetry(key)
                        val child = value(keep, depth + 1)
                        if (keep) fields!![key] = child
                        whitespace()
                        if (take('}')) break
                        if (!take(',')) throw UnsupportedJson()
                    } while (true)
                }
                fields?.let(::JsonObject) ?: JsonNull
            }

            '[' -> {
                offset++
                val elements = if (retain) mutableListOf<JsonElement>() else null
                whitespace()
                if (!take(']')) {
                    do {
                        val child = value(retain, depth + 1)
                        elements?.add(child)
                        whitespace()
                        if (take(']')) break
                        if (!take(',')) throw UnsupportedJson()
                    } while (true)
                }
                elements?.let(::JsonArray) ?: JsonNull
            }

            '"' -> {
                val content = string(retain)
                if (retain) JsonPrimitive(content) else JsonNull
            }

            else -> {
                val start = offset
                while (offset < text.length && text[offset] !in ",]} \t\r\n") offset++
                if (start == offset) throw UnsupportedJson()
                val primitive =
                    when (val token = text.substring(start, offset)) {
                        "null" -> JsonNull
                        "true" -> if (retain) JsonPrimitive(true) else JsonNull
                        "false" -> if (retain) JsonPrimitive(false) else JsonNull
                        else -> repositoryJson.parseToJsonElement(token)
                    }
                if (primitive !is JsonPrimitive) throw UnsupportedJson()
                if (retain) primitive else JsonNull
            }
        }
    }

    private fun string(retain: Boolean): String {
        val start = offset
        var cursor = start + 1
        var escaped = false
        while (cursor < text.length) {
            when (val char = text[cursor++]) {
                '"' -> {
                    offset = cursor
                    if (!retain) return ""
                    return if (escaped) {
                        repositoryJson.decodeFromString<String>(text.substring(start, cursor))
                    } else {
                        text.substring(start + 1, cursor - 1)
                    }
                }

                '\\' -> {
                    escaped = true
                    if (cursor == text.length) throw UnsupportedJson()
                    when (text[cursor++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> {
                        }

                        'u' -> {
                            if (text.length - cursor < 4) throw UnsupportedJson()
                            repeat(4) {
                                if (text[cursor++].digitToIntOrNull(16) == null) throw UnsupportedJson()
                            }
                        }

                        else -> {
                            throw UnsupportedJson()
                        }
                    }
                }

                else -> {
                    if (char < ' ') throw UnsupportedJson()
                }
            }
        }
        throw UnsupportedJson()
    }

    private fun whitespace() {
        while (offset < text.length && text[offset] in " \t\r\n") offset++
    }

    private fun peek(): Char = text.getOrNull(offset) ?: throw UnsupportedJson()

    private fun take(char: Char): Boolean {
        if (offset >= text.length || text[offset] != char) return false
        offset++
        return true
    }

    private fun isTelemetry(key: String): Boolean =
        key == "trackingParams" || key == "clickTrackingParams" ||
            key == "loggingContext" || key == "loggingDirectives"
}
