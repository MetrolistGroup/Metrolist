/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics.romanizer

import com.metrolist.shared.model.WordTimestamp
import com.metrolist.shared.network.bodyAsBytesLimited
import com.metrolist.shared.network.createHttpClient
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import meow.nyx.glyphmind.DictionaryPacks
import meow.nyx.glyphmind.LyricsRomanizer as GlyphmindRomanizer
import meow.nyx.glyphmind.WordTimestamp as GlyphmindWord

internal expect fun glyphmindStorageDirectory(): String

internal expect fun removeLegacyJapaneseDictionary()

object LyricsRomanizer {
    private val rules = GlyphmindRomanizer()
    private val httpClient by lazy { createHttpClient() }
    private val stored by lazy { createDictionaryRomanizer(glyphmindStorageDirectory(), httpClient) }

    private fun engine(): GlyphmindRomanizer =
        try {
            stored
        } catch (_: Exception) {
            rules
        }

    internal suspend fun prepareDictionariesIfNeeded(
        fullText: String,
        enabledLanguages: Set<String>,
        prepare: suspend () -> Boolean = { prepareRomanizationDictionaries(fullText, enabledLanguages) },
    ): Boolean = if (rules.requiredDictionaries(fullText, enabledLanguages).isEmpty()) false else prepare()

    internal suspend fun prepare(
        fullText: String,
        enabledLanguages: Set<String>,
        romanizer: () -> GlyphmindRomanizer = { stored },
        removeLegacyDictionary: () -> Unit = ::removeLegacyJapaneseDictionary,
    ): Boolean =
        withContext(Dispatchers.Default) {
            try {
                romanizer().prepareDictionariesIfNeeded(fullText, enabledLanguages).also { ready ->
                    if (ready) removeLegacyDictionary()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                PlaybackEventLog.error(PlaybackEventKind.NETWORK, "Glyphmind", "Dictionary preparation failed", error)
                false
            }
        }

    suspend fun romanize(
        fullText: String,
        line: String,
        enabledLanguages: Set<String>,
        cyrillicByLine: Boolean,
    ): String? = engine().romanize(fullText, line, enabledLanguages, cyrillicByLine)

    suspend fun romanizeWords(
        fullText: String,
        line: String,
        words: List<WordTimestamp>,
        enabledLanguages: Set<String>,
        cyrillicByLine: Boolean,
    ): Pair<String, List<WordTimestamp>>? =
        engine()
            .romanizeWords(fullText, line, words.map(WordTimestamp::toGlyphmind), enabledLanguages, cyrillicByLine)
            ?.let { (text, romanized) ->
                text to words.mapIndexed { index, word -> word.copy(text = romanized[index].text) }
            }

    fun alignRomanizationAnchors(
        fullText: String,
        romanizedGuides: List<String>,
        words: List<WordTimestamp>,
    ): List<String> = rules.alignRomanizationAnchors(fullText, romanizedGuides, words.map(WordTimestamp::toGlyphmind))

    fun romanizeIsolatedWord(text: String): String = engine().romanizeIsolatedWord(text)

    fun stripAccents(text: String): String = rules.stripAccents(text)

    fun isPurelyLatinScript(text: String): Boolean = rules.isPurelyLatinScript(text)

    fun romanizeKana(text: String): String = rules.romanizeKana(text)
}

internal fun createDictionaryRomanizer(
    storageDirectory: String,
    httpClient: HttpClient,
): GlyphmindRomanizer =
    GlyphmindRomanizer(
        DictionaryPacks(storageDirectory) { pack ->
            httpClient
                .prepareGet(pack.url) {
                    timeout {
                        requestTimeoutMillis = 60_000
                        socketTimeoutMillis = 30_000
                    }
                }.execute { response ->
                    check(response.status.isSuccess()) { "Dictionary request failed: ${response.status}" }
                    checkNotNull(response.bodyAsBytesLimited(pack.size.toInt())) { "Dictionary exceeds pinned size" }
                }
        },
    )

internal suspend fun prepareRomanizationDictionaries(
    fullText: String,
    enabledLanguages: Set<String>,
): Boolean = LyricsRomanizer.prepare(fullText, enabledLanguages)

private fun WordTimestamp.toGlyphmind() = GlyphmindWord(text, startTime, endTime, hasTrailingSpace, isBackground, isLineTimed)
