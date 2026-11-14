package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.MlxDocument
import com.metrolist.shared.model.WordTimestamp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToLong

private val editorJson = Json { prettyPrint = true }

class LyricsEditor(
    raw: String,
) {
    private val undo = mutableListOf<LyricsEditorSnapshot>()
    private val redo = mutableListOf<LyricsEditorSnapshot>()
    var current = snapshot(editorDocument(raw))
        private set
    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    fun edit(
        text: String,
        selectionStart: Int,
        selectionEnd: Int = selectionStart,
    ) {
        if (text == current.text) {
            current = current.copy(selectionStart = selectionStart, selectionEnd = selectionEnd)
            return
        }
        val before = current
        val oldRows =
            before.text
                .split('\n')
                .takeIf { before.text.isNotEmpty() }
                .orEmpty()
        val newRows = text.split('\n').takeIf { text.isNotEmpty() }.orEmpty()
        val oldLines = before.document.getValue("lines").jsonArray
        val used = mutableSetOf<Int>()
        val matches = arrayOfNulls<Int>(newRows.size)
        var prefix = 0
        while (prefix < minOf(oldRows.size, newRows.size) && oldRows[prefix] == newRows[prefix]) {
            matches[prefix] = prefix
            used += prefix++
        }
        var tail = 0
        while (tail < minOf(oldRows.size, newRows.size) - prefix &&
            oldRows[oldRows.lastIndex - tail] == newRows[newRows.lastIndex - tail]
        ) {
            matches[newRows.lastIndex - tail] = oldRows.lastIndex - tail
            used += oldRows.lastIndex - tail++
        }
        val remaining = oldRows.indices.filter { it !in used }.toMutableList()
        for (index in newRows.indices.filter { matches[it] == null }) {
            val exact = remaining.firstOrNull { oldRows[it] == newRows[index] }
            if (exact != null) {
                matches[index] = exact
                remaining.remove(exact)
            }
        }
        for (index in newRows.indices.filter { matches[it] == null }) {
            if (remaining.isNotEmpty()) matches[index] = remaining.removeAt(0)
        }
        val lines =
            newRows.mapIndexed { index, row ->
                val endMs =
                    newRows.getOrNull(index + 1)?.let(::parseRow)?.time
                        ?: before.document["song"]
                            ?.jsonObject
                            ?.get("durationMs")
                            ?.jsonPrimitive
                            ?.longOrNull
                updateLine(matches[index]?.let { oldLines[it].jsonObject }, row, endMs)
            }
        val updatedRows =
            newRows.mapIndexed { index, row ->
                val original = matches[index]?.let { parseRow(renderLine(oldLines[it].jsonObject)) }
                val parsed = parseRow(row)
                if (parsed != null && original != null && parsed.time != original.time &&
                    parsed.words.isNotEmpty() && parsed.words == original.words && lines[index] != oldLines[matches[index]!!]
                ) {
                    renderLine(lines[index])
                } else {
                    row
                }
            }
        val updatedText = updatedRows.joinToString("\n")
        push(
            before.copy(
                text = updatedText,
                document = JsonObject(before.document + ("lines" to JsonArray(lines))),
                selectionStart = selectionStart.coerceIn(0, updatedText.length),
                selectionEnd = selectionEnd.coerceIn(0, updatedText.length),
            ),
        )
    }

    fun shift(
        deltaMs: Long,
        all: Boolean = false,
    ) {
        if (!current.isValid) return
        val rows = current.text.split('\n').toMutableList()
        if (current.text.isEmpty()) return
        val selected = if (all) rows.indices else selectedLines()
        val lines =
            current.document
                .getValue("lines")
                .jsonArray
                .toMutableList()
        val times = selected.flatMap { timingValues(lines[it]) + parseRow(rows[it])!!.time }
        val delta = deltaMs.coerceIn(-times.min(), MAX_TIME_MS - times.max())
        if (delta == 0L) return
        for (index in selected) {
            val line = lines[index].jsonObject
            val time = parseRow(rows[index])!!.time
            val shifted = JsonObject(shiftTimes(line, delta).jsonObject + ("startMs" to JsonPrimitive(time + delta)))
            lines[index] = shifted
            rows[index] = renderLine(shifted)
        }
        val text = rows.joinToString("\n")
        push(
            current.copy(
                text = text,
                document = JsonObject(current.document + ("lines" to JsonArray(lines))),
                selectionStart = current.selectionStart.coerceAtMost(text.length),
                selectionEnd = current.selectionEnd.coerceAtMost(text.length),
            ),
        )
    }

    fun timestamp(positionMs: Long) {
        if (current.text.isEmpty()) {
            edit("[${editorTimestamp(positionMs.coerceIn(0, MAX_TIME_MS))}] ", 0)
            return
        }
        val rows = current.text.split('\n').toMutableList()
        val index = selectedLines().first
        val text = editorRow.matchEntire(rows[index])?.groupValues?.get(4) ?: escapeText(rows[index])
        rows[index] = "[${editorTimestamp(positionMs.coerceIn(0, MAX_TIME_MS))}] $text"
        edit(rows.joinToString("\n"), current.selectionStart, current.selectionEnd)
    }

    fun deleteLines() {
        if (current.text.isEmpty()) return
        val rows = current.text.split('\n')
        val selected = selectedLines()
        val text = rows.filterIndexed { index, _ -> index !in selected }.joinToString("\n")
        edit(text, current.selectionStart.coerceAtMost(text.length))
    }

    fun import(raw: String): Boolean =
        runCatching {
            val document = editorDocument(raw, importing = true)
            push(snapshot(document))
            true
        }.getOrDefault(false)

    fun undo() {
        if (undo.isEmpty()) return
        redo += current
        current = undo.removeAt(undo.lastIndex)
    }

    fun redo() {
        if (redo.isEmpty()) return
        undo += current
        current = redo.removeAt(redo.lastIndex)
    }

    private fun push(next: LyricsEditorSnapshot) {
        if (next == current) return
        undo += current
        redo.clear()
        current = if (next.isValid) next.copy(recoveryDocument = next.document) else next
    }

    private fun selectedLines(): IntRange {
        fun rowAt(offset: Int) = current.text.take(offset.coerceIn(0, current.text.length)).count { it == '\n' }
        val start = minOf(current.selectionStart, current.selectionEnd)
        val end = maxOf(current.selectionStart, current.selectionEnd)
        return rowAt(start)..rowAt(if (end > start && current.text.getOrNull(end - 1) == '\n') end - 1 else end)
    }
}

data class LyricsEditorSnapshot(
    val text: String,
    val document: JsonObject,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val recoveryDocument: JsonObject = document,
) {
    val exceedsLimits: Boolean get() {
        val lines = document.getValue("lines").jsonArray
        if (text.length > 8 * 1024 * 1024 || lines.size > MlxParser.MAX_LINES ||
            text.split('\n').any { it.length > MlxParser.MAX_LINE_CHARS }
        ) {
            return true
        }
        var wordCount = 0
        for (entry in lines) {
            val line = entry.jsonObject
            var chars =
                listOf("text", "backgroundText", "romanizedText", "translatedText").sumOf {
                    line[it]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.length
                        ?.toLong() ?: 0L
                }
            for (key in listOf("words", "backgroundWords", "romanizedWords")) {
                val words = line[key]?.jsonArray.orEmpty()
                wordCount += words.size
                chars +=
                    words.sumOf {
                        it.jsonObject
                            .getValue("text")
                            .jsonPrimitive.content.length
                            .toLong()
                    }
            }
            if (line["backgroundWords"]?.jsonArray.isNullOrEmpty() &&
                !line["backgroundText"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()
            ) {
                wordCount++
            }
            if (chars > MlxParser.MAX_LINE_CHARS || wordCount > MlxParser.MAX_WORD_MARKERS) return true
        }
        return false
    }
    val errors: List<Int> get() =
        if (text.isEmpty()) {
            emptyList()
        } else {
            text.split('\n').mapIndexedNotNull { index, row ->
                val parsed = parseRow(row)
                val stored =
                    document
                        .getValue("lines")
                        .jsonArray[index]
                        .jsonObject["startMs"]
                        ?.jsonPrimitive
                        ?.longOrNull ?: 0L
                val words = parseRow(renderLine(document.getValue("lines").jsonArray[index].jsonObject))?.words
                index.takeIf { parsed == null || parsed.time != stored || parsed.words != words }
            }
        }
    val isValid: Boolean get() = errors.isEmpty() && !exceedsLimits
    val json: String get() = editorJson.encodeToString(document)
    val recoveryJson: String get() = editorJson.encodeToString(recoveryDocument)
}

private const val MAX_TIME_MS = Long.MAX_VALUE / 2
private val editorRow = Regex("""^\[(\d+):([0-5]\d)\.(\d{2,3})] ?(.*)$""")
private val editorWord = Regex("""<(\d+):([0-5]\d)\.(\d{2,3})>""")

private data class EditorWord(
    val time: Long,
    val text: String,
)

private data class EditorRow(
    val time: Long,
    val text: String,
    val words: List<EditorWord>,
)

private fun parseTime(match: MatchResult): Long? {
    val minutes = match.groupValues[1].toLongOrNull()?.takeIf { it <= (MAX_TIME_MS - 60_000) / 60_000 } ?: return null
    val fraction = match.groupValues[3].padEnd(3, '0').toLong()
    return minutes * 60_000 + match.groupValues[2].toLong() * 1000 + fraction
}

private fun parseRow(row: String): EditorRow? {
    val match = editorRow.matchEntire(row) ?: return null
    val time = parseTime(match) ?: return null
    val content = match.groupValues[4]
    val words = mutableListOf<EditorWord>()
    val text = StringBuilder()
    var wordTime: Long? = null
    var index = 0
    while (index < content.length) {
        when {
            content[index] == '\\' && index + 1 < content.length && content[index + 1] in "nr\\<>" -> {
                text.append(
                    when (val escaped = content[++index]) {
                        'n' -> '\n'
                        'r' -> '\r'
                        else -> escaped
                    },
                )
                index++
            }

            content[index] == '<' -> {
                val marker = editorWord.matchAt(content, index) ?: return null
                val start = parseTime(marker) ?: return null
                if (wordTime != null || text.isNotEmpty()) words += EditorWord(wordTime ?: time, text.toString())
                text.clear()
                wordTime = start
                index = marker.range.last + 1
            }

            content[index] == '>' -> {
                return null
            }

            else -> {
                text.append(content[index++])
            }
        }
    }
    if (wordTime != null) words += EditorWord(wordTime, text.toString())
    return EditorRow(time, if (words.isEmpty()) text.toString() else words.joinToString("") { it.text }, words)
}

internal fun editorTimestamp(time: Long): String {
    val fraction = (time % 1000).toString().padStart(3, '0').let { if (time % 10 == 0L) it.dropLast(1) else it }
    return "${(time / 60_000).toString().padStart(2, '0')}:${(time / 1000 % 60).toString().padStart(2, '0')}.$fraction"
}

private fun escapeText(text: String): String =
    text
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("<", "\\<")
        .replace(">", "\\>")

private fun renderLine(line: JsonObject): String {
    val words = line["words"]?.jsonArray.orEmpty()
    if (words.any { it.jsonObject["isLineTimed"]?.jsonPrimitive?.booleanOrNull != true }) {
        val text =
            words.joinToString("") {
                val word = it.jsonObject
                "<${editorTimestamp(
                    word.getValue("startMs").jsonPrimitive.longOrNull!!,
                )}>${escapeText(word.getValue("text").jsonPrimitive.content)}"
            }
        return "[${editorTimestamp(line["startMs"]?.jsonPrimitive?.longOrNull ?: 0)}] $text"
    }
    val text =
        line["text"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: MlxJsonParser
                .parse(
                    JsonObject(mapOf("version" to JsonPrimitive(2), "lines" to JsonArray(listOf(line)))).toString(),
                )?.lines
                ?.firstOrNull()
                ?.text
                .orEmpty()
    return "[${editorTimestamp(line["startMs"]?.jsonPrimitive?.longOrNull ?: 0)}] ${escapeText(text)}"
}

private fun snapshot(document: JsonObject): LyricsEditorSnapshot =
    LyricsEditorSnapshot(
        document.getValue("lines").jsonArray.joinToString("\n") {
            renderLine(it.jsonObject)
        },
        document,
    )

private fun updateLine(
    source: JsonObject?,
    row: String,
    endMs: Long?,
): JsonObject {
    val parsed =
        parseRow(row) ?: return source ?: buildJsonObject {
            put("startMs", JsonPrimitive(0))
            put("text", JsonPrimitive(""))
        }
    if (source != null && row == renderLine(source)) return source
    val (time, text) = parsed
    val originalTime = source?.get("startMs")?.jsonPrimitive?.longOrNull ?: 0
    val times = source?.let(::timingValues).orEmpty() + originalTime
    val delta = time - originalTime
    if (delta !in -times.min()..(MAX_TIME_MS - times.max())) return source ?: JsonObject(emptyMap())
    val shifted = source?.let { shiftTimes(it, time - originalTime).jsonObject } ?: JsonObject(emptyMap())
    val original = source?.let { parseRow(renderLine(it)) }
    val result =
        if (text == original?.text) {
            shifted
        } else {
            JsonObject(shifted - setOf("romanizedWords", "romanizedText", "translatedText"))
        }
    val updated = result.toMutableMap()
    updated["startMs"] = JsonPrimitive(time)
    if (text != original?.text) updated["text"] = JsonPrimitive(text)
    if (parsed.words.isEmpty()) {
        if (!original?.words.isNullOrEmpty() || text != original?.text) updated.remove("words")
        if (!original?.words.isNullOrEmpty()) updated.remove("romanizedWords")
    } else if (parsed.words != original?.words) {
        val originals =
            shifted["words"]
                ?.jsonArray
                .orEmpty()
                .map { it.jsonObject }
                .toMutableList()
        val sameWordCount = originals.size == parsed.words.size
        updated["words"] =
            JsonArray(
                parsed.words.mapIndexed { index, word ->
                    val previous =
                        if (sameWordCount) {
                            originals[index]
                        } else {
                            originals.firstOrNull { it["text"] == JsonPrimitive(word.text) }?.also { originals.remove(it) }
                        }
                    if (previous != null && previous["text"] ==
                        JsonPrimitive(
                            word.text,
                        ) && previous["startMs"] == JsonPrimitive(word.time) &&
                        previous["isLineTimed"]?.jsonPrimitive?.booleanOrNull != true
                    ) {
                        return@mapIndexed previous
                    }
                    val end =
                        previous
                            ?.get("endMs")
                            ?.jsonPrimitive
                            ?.longOrNull
                            ?.takeIf { it > word.time }
                            ?: parsed.words
                                .getOrNull(index + 1)
                                ?.time
                                ?.takeIf { it > word.time }
                            ?: shifted["endMs"]?.jsonPrimitive?.longOrNull?.takeIf { it > word.time }
                            ?: endMs?.takeIf { it > word.time }
                            // Match the enhanced-LRC parser when the final word has no end.
                            ?: (word.time + 500).coerceAtMost(MAX_TIME_MS)
                    var retained = previous.orEmpty().let { if (it["text"] == JsonPrimitive(word.text)) it else it - "syllables" }
                    if (retained["isLineTimed"] == JsonPrimitive(true)) retained = retained - "isLineTimed"
                    JsonObject(
                        retained +
                            mapOf(
                                "text" to JsonPrimitive(word.text),
                                "startMs" to JsonPrimitive(word.time),
                                "endMs" to JsonPrimitive(end),
                            ),
                    )
                },
            )
    }
    return JsonObject(updated)
}

private fun timingValues(value: JsonElement): List<Long> =
    when (value) {
        is JsonObject -> {
            value.flatMap { (key, item) ->
                if (key == "startMs" ||
                    key == "endMs"
                ) {
                    listOfNotNull((item as? JsonPrimitive)?.longOrNull)
                } else {
                    timingValues(item)
                }
            }
        }

        is JsonArray -> {
            value.flatMap(::timingValues)
        }

        else -> {
            emptyList()
        }
    }

private fun shiftTimes(
    value: JsonElement,
    delta: Long,
): JsonElement =
    when (value) {
        is JsonObject -> {
            JsonObject(
                value.mapValues { (key, item) ->
                    if (key == "startMs" || key == "endMs") {
                        (item as? JsonPrimitive)?.longOrNull?.let { JsonPrimitive((it + delta).coerceIn(0, MAX_TIME_MS)) } ?: item
                    } else {
                        shiftTimes(item, delta)
                    }
                },
            )
        }

        is JsonArray -> {
            JsonArray(value.map { shiftTimes(it, delta) })
        }

        else -> {
            value
        }
    }

private fun editorDocument(
    raw: String,
    importing: Boolean = false,
): JsonObject {
    require(raw.length <= 8 * 1024 * 1024)
    if (raw.trimStart().startsWith('{')) {
        val root = Json.parseToJsonElement(raw).jsonObject
        require(root["version"] == JsonPrimitive(2))
        val lines = root["lines"]?.jsonArray ?: error("Missing MLX lines")
        require(lines.isEmpty() || MlxJsonParser.parse(raw) != null)
        require(lines.all { line -> timingValues(line).all { it in 0..MAX_TIME_MS } })
        return root
    }
    val doc = MlxParser.parse(raw)
    require(!importing || raw.isBlank() || doc.lines.isNotEmpty())
    return doc.toEditorJson()
}

private fun MlxDocument.toEditorJson(): JsonObject =
    buildJsonObject {
        put("version", JsonPrimitive(2))
        put("song", Json.encodeToJsonElement(song))
        put("metadata", Json.encodeToJsonElement(meta))
        put(
            "source",
            buildJsonObject {
                meta.lyricSource?.let { put("name", JsonPrimitive(it)) }
                meta.lastFetched?.let { put("lastFetched", JsonPrimitive(it)) }
                meta.sourceId?.let { put("id", JsonPrimitive(it)) }
                meta.curator?.let { put("curator", JsonPrimitive(it)) }
                meta.lyricsCurator?.let { put("lyricsCurator", JsonPrimitive(it)) }
                meta.lyricsCuratorId?.let { put("lyricsCuratorId", JsonPrimitive(it)) }
                meta.copyright?.let { put("copyright", JsonPrimitive(it)) }
            },
        )
        meta.provider?.let { put("provider", JsonPrimitive(it)) }
        meta.subprovider?.let { put("subprovider", JsonPrimitive(it)) }
        meta.sourceFormat?.let { put("sourceFormat", JsonPrimitive(it)) }
        meta.syncType?.let { put("syncType", JsonPrimitive(it)) }
        put("ignoreRetiming", JsonPrimitive(meta.ignoreRetiming))
        colors?.let { put("colors", Json.encodeToJsonElement(it)) }
        put(
            "agents",
            JsonObject(
                agents.mapValues { (_, agent) ->
                    buildJsonObject {
                        put("type", JsonPrimitive(agent.type.name.lowercase()))
                        agent.name?.let { put("name", JsonPrimitive(it)) }
                        agent.color?.let { put("color", JsonPrimitive(it)) }
                    }
                },
            ),
        )
        put("parts", Json.encodeToJsonElement(songParts))
        put(
            "lines",
            JsonArray(
                lines.map { line ->
                    buildJsonObject {
                        put("startMs", JsonPrimitive(line.time))
                        line.endTime?.let { put("endMs", JsonPrimitive(it)) }
                        put("text", JsonPrimitive(line.text))
                        put("background", JsonPrimitive(line.isBackground))
                        line.agent?.let { put("agent", JsonPrimitive(it)) }
                        line.songPart?.let { part ->
                            songParts.indexOfFirst { it.name == part }.takeIf { it >= 0 }?.let { put("part", JsonPrimitive(it)) }
                        }
                        line.backgroundText?.let { put("backgroundText", JsonPrimitive(it)) }
                        line.backgroundTranslatedText?.let { put("backgroundTranslatedText", JsonPrimitive(it)) }
                        line.translatedText?.let { put("translatedText", JsonPrimitive(it)) }
                        line.romanizedText?.let { put("romanizedText", JsonPrimitive(it)) }
                        line.words?.let { words ->
                            put("words", jsonWords(words.filterNot { it.isBackground }))
                            put("backgroundWords", jsonWords(words.filter { it.isBackground }))
                        }
                        line.romanizedWords?.let { put("romanizedWords", jsonWords(it)) }
                    }
                },
            ),
        )
    }

private fun jsonWords(words: List<WordTimestamp>): JsonArray =
    JsonArray(
        words.map { word ->
            buildJsonObject {
                put("text", JsonPrimitive(word.text + if (word.hasTrailingSpace) " " else ""))
                put("startMs", JsonPrimitive((word.startTime * 1000).roundToLong()))
                put("endMs", JsonPrimitive((word.endTime * 1000).roundToLong()))
                put("isLineTimed", JsonPrimitive(word.isLineTimed))
            }
        },
    )
