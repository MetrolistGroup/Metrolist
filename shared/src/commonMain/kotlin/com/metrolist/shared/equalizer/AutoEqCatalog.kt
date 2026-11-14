/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.equalizer

import com.metrolist.shared.network.createHttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AutoEqEntry(
    val name: String,
    val source: String,
    val rig: String?,
    val form: String,
    val path: String,
)

data class AutoEqModel(
    val name: String,
    val entries: List<AutoEqEntry>,
)

object AutoEqCatalog {
    private const val RESULTS_URL = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results"

    private val client by lazy { createHttpClient() }
    private val mutex = Mutex()
    private var models: List<AutoEqModel>? = null

    suspend fun models(): List<AutoEqModel> =
        mutex.withLock {
            models ?: groupAutoEqModels(parseAutoEqIndex(fetch("$RESULTS_URL/INDEX.md"))).also { models = it }
        }

    suspend fun load(entry: AutoEqEntry): EqualizerProfile {
        val fileName = entry.path.substringAfterLast('/') + "%20ParametricEQ.txt"
        val (preamp, bands) = parseParametricEq(fetch("$RESULTS_URL/${entry.path}/$fileName")) ?: throw InvalidEqualizerProfileException()
        return EqualizerProfile(
            id = "autoeq:${entry.path}",
            name = entry.name,
            bands = bands,
            preamp = preamp,
            source = entry.source,
            rig = entry.rig ?: "unknown",
        )
    }

    private suspend fun fetch(url: String): String {
        val response = client.get(url)
        check(response.status.isSuccess()) { "AutoEq request failed: ${response.status}" }
        return response.bodyAsText()
    }
}

private val indexLine = Regex("""^- \[(.+)]\(\./(.+)\) by (.+?)(?: on (.+))?$""")

fun parseAutoEqIndex(markdown: String): List<AutoEqEntry> =
    markdown
        .lineSequence()
        .mapNotNull { line ->
            val (name, path, source, rig) = indexLine.matchEntire(line.trim())?.destructured ?: return@mapNotNull null
            val formDirectory = path.split('/').getOrNull(1) ?: return@mapNotNull null
            AutoEqEntry(name, source, rig.ifEmpty { null }, formDirectory.substringAfterLast("%20"), path)
        }.toList()

fun groupAutoEqModels(entries: List<AutoEqEntry>): List<AutoEqModel> =
    entries
        .groupBy { it.name.replace(Regex("""\s*\([^)]*\)"""), "").trim() }
        .map { (name, entries) -> AutoEqModel(name, entries) }
        .sortedBy { it.name.lowercase() }

fun searchAutoEqModels(
    models: List<AutoEqModel>,
    query: String,
): List<AutoEqModel> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return models
    val words = needle.split(Regex("\\s+"))
    return models
        .filter { model -> model.name.lowercase().let { name -> words.all(name::contains) } }
        .sortedBy { model ->
            val name = model.name.lowercase()
            when {
                name == needle -> 0
                name.startsWith(needle) -> 1
                name.split(' ', '-').any { it.startsWith(words.first()) } -> 2
                else -> 3
            }
        }
}
