/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal fun removeLyprioIntroHeader(
    mlx: String,
    title: String,
    artist: String,
): String {
    val whitespace = Regex("\\s+")

    fun normalize(text: String) =
        text
            .trim()
            .split(whitespace)
            .joinToString(" ")
            .lowercase()

    val normalizedTitle = normalize(title)
    val normalizedArtist = normalize(artist)
    if (normalizedTitle.isEmpty() || normalizedArtist.isEmpty()) return mlx
    val document = MlxParser.parse(mlx)
    val index = document.lines.indexOfFirst { it.text.isNotBlank() }
    val header = document.lines.getOrNull(index) ?: return mlx
    if (!header.words.isNullOrEmpty() || header.isBackground || !header.backgroundText.isNullOrBlank()) return mlx
    val text = normalize(header.text)
    val matches =
        listOf(" - ", " – ", " — ").any { separator ->
            text == "$normalizedTitle$separator$normalizedArtist" || text == "$normalizedArtist$separator$normalizedTitle"
        }
    if (!matches || document.lines.drop(index + 1).none { it.text.isNotBlank() }) return mlx

    val remaining = document.lines.filterIndexed { i, _ -> i != index }
    if (mlx.trimStart().startsWith('{')) {
        val root = Json.parseToJsonElement(mlx).jsonObject
        val lines = root.getValue("lines").jsonArray
        return JsonObject(root + ("lines" to JsonArray(lines.filterIndexed { i, _ -> i != index }))).toString()
    }

    val lines = mlx.lines()
    var section = "lyrics"
    var mainIndex: Int? = null
    val auxiliaryIndices = mutableListOf<Int>()
    val visitedSections = mutableSetOf<String>()
    val sectionHeader = Regex("^\\[([a-zA-Z_]+)]$")
    val lyricSections = setOf("lyrics", "romanlyrics", "roman_lyrics", "translatedlyrics", "translated_lyrics")
    for ((lineIndex, line) in lines.withIndex()) {
        val trimmed = line.trim()
        val sectionName =
            sectionHeader
                .matchEntire(trimmed)
                ?.groupValues
                ?.get(1)
                ?.lowercase()
        if (sectionName != null) {
            section = sectionName
            continue
        }
        if (section !in lyricSections) continue
        if (section in visitedSections) continue
        val entry = MlxParser.parse(line).lines.firstOrNull { it.text.isNotBlank() } ?: continue
        visitedSections += section
        if (section == "lyrics") {
            if (normalize(entry.text) != text) return mlx
            mainIndex = lineIndex
        } else {
            auxiliaryIndices += lineIndex
        }
    }
    val removedIndex = mainIndex ?: return mlx

    fun without(indices: Collection<Int>) = lines.filterIndexed { i, _ -> i !in indices }.joinToString("\n")

    val cleaned = without(listOf(removedIndex))
    if (MlxParser.parse(cleaned).lines == remaining) return cleaned
    val aligned = without(auxiliaryIndices + removedIndex)
    return if (MlxParser.parse(aligned).lines == remaining) aligned else mlx
}
