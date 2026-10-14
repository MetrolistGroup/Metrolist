/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.export

/** Builds "Artist — Title.ext" names that are valid on Android, FAT/exFAT and Windows. */
object ExportFileNaming {
    private const val SEPARATOR = " — "

    /** Android limits a file name to 255 bytes; keep a margin for " (12)" suffixes. */
    private const val MAX_NAME_BYTES = 240

    private val illegalChars = Regex("""[\\/:*?"<>|\u0000-\u001F\u007F]""")
    private val whitespace = Regex("""\s+""")
    private val reservedNames =
        setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        )

    fun sanitize(part: String): String {
        var result = illegalChars.replace(part, "_")
        result = whitespace.replace(result, " ").trim()
        result = result.trim('.', ' ')
        return result
    }

    fun build(
        artists: List<String>,
        title: String,
        extension: String,
    ): String {
        val artistPart = sanitize(artists.filter { it.isNotBlank() }.joinToString(", "))
        val titlePart = sanitize(title).ifEmpty { "Unknown" }
        var base = if (artistPart.isEmpty()) titlePart else "$artistPart$SEPARATOR$titlePart"
        if (base.uppercase() in reservedNames) base = "_$base"

        val suffix = ".$extension"
        val budget = MAX_NAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size
        base = truncateUtf8(base, budget).trimEnd('.', ' ').ifEmpty { "Unknown" }
        return base + suffix
    }

    /** "name.ext" -> "name (n).ext". */
    fun withCounter(
        fileName: String,
        counter: Int,
    ): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot <= 0) {
            "$fileName ($counter)"
        } else {
            "${fileName.substring(0, dot)} ($counter)${fileName.substring(dot)}"
        }
    }

    /** First "name", "name (1)", "name (2)"... for which [exists] is false. */
    inline fun uniqueName(
        fileName: String,
        exists: (String) -> Boolean,
    ): String {
        if (!exists(fileName)) return fileName
        var counter = 1
        while (true) {
            val candidate = withCounter(fileName, counter)
            if (!exists(candidate)) return candidate
            counter++
        }
    }

    private fun truncateUtf8(
        text: String,
        maxBytes: Int,
    ): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        val out = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val chars = Character.charCount(codePoint)
            val size = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            out.appendCodePoint(codePoint)
            bytes += size
            index += chars
        }
        return out.toString()
    }
}
