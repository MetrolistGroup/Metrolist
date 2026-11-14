/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.AgentType
import kotlin.time.Clock

class MlxBuilder {
    private val metaLines = StringBuilder()
    private val songLines = StringBuilder()
    private val agentLines = StringBuilder()
    private val lyricLines = StringBuilder()
    private val romanLyricLines = StringBuilder()

    fun meta(
        source: String,
        version: String = "1.0",
        lastFetched: String = "",
        ignoreRetiming: Boolean = false,
        sourceId: String? = null,
        lyricsCurator: String? = null,
        lyricsCuratorId: String? = null,
    ): MlxBuilder {
        metaLines.clear()
        metaLines.appendLine("mlx_version=$version")
        metaLines.appendLine("lyric_source=$source")
        val effectiveLastFetched = lastFetched.ifBlank { Clock.System.now().toString() }
        metaLines.appendLine("last_fetched=$effectiveLastFetched")
        metaLines.appendLine("ignore_retiming=$ignoreRetiming")
        sourceId?.let { metaLines.appendLine("source_id=$it") }
        lyricsCurator?.let { metaLines.appendLine("lyrics_curator=$it") }
        lyricsCuratorId?.let { metaLines.appendLine("lyrics_curator_id=$it") }
        return this
    }

    fun song(
        title: String? = null,
        artist: String? = null,
        youtubeId: String? = null,
        songwriters: List<String>? = null,
    ): MlxBuilder {
        songLines.clear()
        if (title != null) songLines.appendLine("title=$title")
        if (artist != null) songLines.appendLine("artist=$artist")
        if (youtubeId != null) songLines.appendLine("youtube_id=$youtubeId")
        if (!songwriters.isNullOrEmpty()) {
            songLines.appendLine("songwriters=${songwriters.joinToString(", ")}")
        }
        return this
    }

    fun songPart(
        name: String,
        startMs: Long? = null,
        endMs: Long? = null,
    ): MlxBuilder {
        if (startMs != null && endMs != null) {
            lyricLines.append("[part:")
            lyricLines.append(formatMs(startMs))
            lyricLines.append('~')
            lyricLines.append(formatMs(endMs))
            lyricLines.append(']')
        } else if (startMs != null) {
            lyricLines.append("[part:")
            lyricLines.append(formatMs(startMs))
            lyricLines.append(']')
        } else {
            lyricLines.append("[part]")
        }
        lyricLines.appendLine(name)
        return this
    }

    fun romanSongPart(
        name: String,
        startMs: Long? = null,
        endMs: Long? = null,
    ): MlxBuilder {
        if (startMs != null && endMs != null) {
            romanLyricLines.append("[part:")
            romanLyricLines.append(formatMs(startMs))
            romanLyricLines.append('~')
            romanLyricLines.append(formatMs(endMs))
            romanLyricLines.append(']')
        } else if (startMs != null) {
            romanLyricLines.append("[part:")
            romanLyricLines.append(formatMs(startMs))
            romanLyricLines.append(']')
        } else {
            romanLyricLines.append("[part]")
        }
        romanLyricLines.appendLine(name)
        return this
    }

    fun agent(
        id: String,
        type: AgentType,
        name: String? = null,
    ): MlxBuilder {
        agentLines.append(id)
        agentLines.append('=')
        agentLines.append(type.name.lowercase())
        if (name != null) {
            agentLines.append(',')
            agentLines.append(name)
        }
        agentLines.append('\n')
        return this
    }

    fun unsyncedLine(text: String): MlxBuilder {
        lyricLines.appendLine(text)
        return this
    }

    fun line(
        startMs: Long,
        text: String,
    ): MlxBuilder {
        lyricLines.append(ts(startMs))
        lyricLines.appendLine(text)
        return this
    }

    fun line(
        startMs: Long,
        endMs: Long,
        text: String,
        agentId: String? = null,
        background: String? = null,
    ): MlxBuilder {
        lyricLines.append(header(startMs, endMs, agentId))
        lyricLines.append(text)
        if (background != null) lyricLines.append(" [bg:$background]")
        lyricLines.append('\n')
        return this
    }

    /** The last word end becomes the trailing timestamp; background words use inline [bg:...] syntax. */
    fun line(
        startMs: Long,
        endMs: Long,
        agentId: String? = null,
        words: List<MlxWord>,
        backgroundWords: List<MlxWord>? = null,
    ): MlxBuilder {
        if (words.isEmpty()) return this

        lyricLines.append(header(startMs, endMs, agentId))
        appendWords(lyricLines, words)

        if (!backgroundWords.isNullOrEmpty()) {
            lyricLines.append(" [bg:")
            appendWords(lyricLines, backgroundWords)
            lyricLines.append(']')
        }

        lyricLines.append('\n')
        return this
    }

    fun romanLine(
        startMs: Long,
        text: String,
    ): MlxBuilder {
        romanLyricLines.append(ts(startMs))
        romanLyricLines.appendLine(text)
        return this
    }

    fun romanLine(
        startMs: Long,
        endMs: Long,
        text: String,
        agentId: String? = null,
        background: String? = null,
    ): MlxBuilder {
        romanLyricLines.append(header(startMs, endMs, agentId))
        romanLyricLines.append(text)
        if (background != null) romanLyricLines.append(" [bg:$background]")
        romanLyricLines.append('\n')
        return this
    }

    fun romanLine(
        startMs: Long,
        endMs: Long,
        agentId: String? = null,
        words: List<MlxWord>,
        backgroundWords: List<MlxWord>? = null,
    ): MlxBuilder {
        if (words.isEmpty()) return this

        romanLyricLines.append(header(startMs, endMs, agentId))
        appendWords(romanLyricLines, words)

        if (!backgroundWords.isNullOrEmpty()) {
            romanLyricLines.append(" [bg:")
            appendWords(romanLyricLines, backgroundWords)
            romanLyricLines.append(']')
        }

        romanLyricLines.append('\n')
        return this
    }

    private fun appendWords(
        target: StringBuilder,
        words: List<MlxWord>,
    ) {
        var lastEmittedMs: Long? = null
        for (w in words) {
            if (lastEmittedMs != w.startMs) {
                target.append(wts(w.startMs))
            }
            target.append(w.text)
            if (w.hasTrailingSpace) target.append(' ')
            target.append(wts(w.endMs))
            lastEmittedMs = w.endMs
        }
    }

    fun build(): String =
        buildString {
            appendLine("[meta]")
            append(metaLines)
            appendLine()
            appendLine("[song]")
            append(songLines)
            appendLine()
            if (agentLines.isNotEmpty()) {
                appendLine("[agents]")
                append(agentLines)
                appendLine()
            }
            appendLine("[lyrics]")
            append(lyricLines)
            if (romanLyricLines.isNotEmpty()) {
                appendLine()
                appendLine("[romanLyrics]")
                append(romanLyricLines)
            }
        }

    private fun ts(ms: Long): String {
        val m = ms / 60_000L
        val s = (ms % 60_000L) / 1_000L
        val mil = ms % 1_000L
        return buildString {
            append('[')
            append(m.toString().padStart(2, '0'))
            append(':')
            append(s.toString().padStart(2, '0'))
            append('.')
            append(mil.toString().padStart(3, '0'))
            append(']')
        }
    }

    private fun wts(ms: Long): String {
        val m = ms / 60_000L
        val s = (ms % 60_000L) / 1_000L
        val mil = ms % 1_000L
        return buildString {
            append('<')
            append(m.toString().padStart(2, '0'))
            append(':')
            append(s.toString().padStart(2, '0'))
            append('.')
            append(mil.toString().padStart(3, '0'))
            append('>')
        }
    }

    private fun header(
        startMs: Long,
        endMs: Long,
        agentId: String?,
    ): String =
        buildString {
            val m1 = startMs / 60_000L
            val s1 = (startMs % 60_000L) / 1_000L
            val ms1 = startMs % 1_000L
            val m2 = endMs / 60_000L
            val s2 = (endMs % 60_000L) / 1_000L
            val ms2 = endMs % 1_000L
            append('[')
            append(m1.toString().padStart(2, '0'))
            append(':')
            append(s1.toString().padStart(2, '0'))
            append('.')
            append(ms1.toString().padStart(3, '0'))
            append('~')
            append(m2.toString().padStart(2, '0'))
            append(':')
            append(s2.toString().padStart(2, '0'))
            append('.')
            append(ms2.toString().padStart(3, '0'))
            if (agentId != null) {
                append('|')
                append(agentId)
            }
            append(']')
        }

    companion object {
        fun formatMs(ms: Long): String {
            val m = ms / 60_000L
            val s = (ms % 60_000L) / 1_000L
            val mil = ms % 1_000L
            return "${m.toString().padStart(2,'0')}:${s.toString().padStart(2,'0')}.${mil.toString().padStart(3,'0')}"
        }
    }
}

data class MlxWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val hasTrailingSpace: Boolean = true,
)
