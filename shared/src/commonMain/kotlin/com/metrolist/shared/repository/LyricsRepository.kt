package com.metrolist.shared.repository

import com.metrolist.shared.api.lyrics.AiModelCatalog
import com.metrolist.shared.api.lyrics.AiProviderPresets
import com.metrolist.shared.api.lyrics.LyprioLyricsProvider
import com.metrolist.shared.api.lyrics.LyricsAiResult
import com.metrolist.shared.api.lyrics.LyricsFetcher
import com.metrolist.shared.api.lyrics.LyricsTranslationClient
import com.metrolist.shared.api.lyrics.LyricsTranslationFailure
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.api.lyrics.MusicVideoLyricsRetimer
import com.metrolist.shared.api.lyrics.YouTubeLyricsApi
import com.metrolist.shared.api.lyrics.YouTubeLyricsProvider
import com.metrolist.shared.api.lyrics.YouTubeSubtitleLyricsProvider
import com.metrolist.shared.api.lyrics.YouTubeTranscriptCue
import com.metrolist.shared.api.lyrics.lyricsTimingKind
import com.metrolist.shared.api.lyrics.lyricsTranslationFailure
import com.metrolist.shared.api.lyrics.retryLyricsTranslation
import com.metrolist.shared.api.lyrics.romanizer.LyricsRomanizer
import com.metrolist.shared.api.lyrics.romanizer.prepareJapaneseRomanizer
import com.metrolist.shared.db.daos.DatabaseDao
import com.metrolist.shared.db.entities.LyricsEntity
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsProviderResult
import com.metrolist.shared.model.LyricsTimingKind
import com.metrolist.shared.model.LyricsTranslationProgress
import com.metrolist.shared.model.MlxDocument
import com.metrolist.shared.model.MlxSong
import com.metrolist.shared.model.WordTimestamp
import com.metrolist.shared.model.lyricsProviderLabel
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.utils.getSystemLanguage
import com.metrolist.shared.utils.systemCountryCode
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import com.metrolist.shared.model.LyricsWithProvider as LyricsWithProviderModel

internal const val LYRICS_PREVIEW_VIDEO_ID = "dQw4w9WgXcQ"
internal const val LYRICS_PREVIEW_DURATION_MS = 213_000L
private val VIDEO_SPECIFIC_LYRICS_PROVIDERS =
    listOf(LyricsProvider.YOUTUBESUBTITLE, LyricsProvider.YOUTUBE, LyricsProvider.SIMPMUSIC)

// Keep saved lyrics from retired providers available offline.
private fun isRetiredLyricsProvider(provider: String): Boolean =
    provider.equals("LyricsPlus", ignoreCase = true) || provider.equals("Zemer", ignoreCase = true)

private fun configuredLyricsProviders(): List<LyricsProvider> =
    LyricsProvider.preferredProvidersForFetch(
        LyricsProvider.parseUserOrderFromStoredCsv(Settings.shared.lyricsProviderOrder),
        LyricsProvider.parseEnabledFromStoredCsv(Settings.shared.lyricsEnabledProviders),
    )

private fun providerPriority(
    provider: String,
    providers: List<LyricsProvider>,
): Int =
    providers
        .indexOf(
            if (provider == "YouTube Subtitle") {
                LyricsProvider.YOUTUBESUBTITLE
            } else {
                LyricsProvider.parseTokenFromSettings(provider.substringBefore('/'))
            },
        ).takeIf { it >= 0 } ?: Int.MAX_VALUE

internal fun prefersLyrics(
    candidate: LyricsWithProviderModel,
    current: LyricsWithProviderModel,
    providers: List<LyricsProvider> = configuredLyricsProviders(),
    preferWordSynced: Boolean = false,
): Boolean {
    if (candidate.provider == USER_LYRICS_PROVIDER && current.provider != USER_LYRICS_PROVIDER) return true
    if (current.provider == USER_LYRICS_PROVIDER && candidate.provider != USER_LYRICS_PROVIDER) return false
    val candidateTiming = lyricsTimingKind(LyricsUtils.parseLyrics(candidate.lyrics))
    val currentTiming = lyricsTimingKind(LyricsUtils.parseLyrics(current.lyrics))
    if (preferWordSynced && candidateTiming != currentTiming) return candidateTiming > currentTiming
    if ((candidateTiming == LyricsTimingKind.UNSYNCED) != (currentTiming == LyricsTimingKind.UNSYNCED)) {
        return candidateTiming != LyricsTimingKind.UNSYNCED
    }
    val candidatePriority = providerPriority(candidate.provider, providers)
    val currentPriority = providerPriority(current.provider, providers)
    if (candidatePriority != currentPriority) return candidatePriority < currentPriority
    return candidateTiming > currentTiming
}

const val USER_LYRICS_PROVIDER = "User"

const val LOCAL_LYRICS_PROVIDER = "METROLIST_LOCAL"

fun isLocalLyricsProvider(provider: String?): Boolean =
    provider == USER_LYRICS_PROVIDER || provider == LOCAL_LYRICS_PROVIDER || provider == "Local"

internal fun translationSourceLine(entry: LyricsEntry): String {
    if (entry.isBackground) return ""
    return entry.words
        ?.filterNot(WordTimestamp::isBackground)
        ?.joinToString("") { it.text + if (it.hasTrailingSpace) " " else "" }
        ?.trim()
        ?: entry.text
}

internal fun translationSourceWords(entry: LyricsEntry): List<String>? =
    entry.words
        ?.filter { !entry.isBackground && !it.isBackground }
        ?.map(WordTimestamp::text)

internal fun backgroundTranslationSourceLine(entry: LyricsEntry): String =
    if (entry.isBackground) {
        ""
    } else {
        entry.words
            ?.filter(WordTimestamp::isBackground)
            ?.joinToString("") { it.text + if (it.hasTrailingSpace) " " else "" }
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: entry.backgroundText.orEmpty()
    }

private fun romanizedWordText(words: List<WordTimestamp>?): String? =
    words
        ?.filterNot(WordTimestamp::isBackground)
        ?.joinToString("") { it.text + if (it.hasTrailingSpace) " " else "" }
        ?.trim()
        ?.takeIf(String::isNotBlank)

internal fun hasCompleteRomanizedWords(entry: LyricsEntry): Boolean {
    val romanizedWords = entry.romanizedWords ?: return false
    if (romanizedWordText(romanizedWords) == null || romanizedWords.any { it.text.isBlank() }) return false
    return entry.words?.let { romanizedWords.size >= it.size } ?: true
}

internal fun needsLocalRomanizationOrAlignment(entry: LyricsEntry): Boolean =
    !hasCompleteRomanizedWords(entry) &&
        (entry.romanizedText.isNullOrBlank() || !entry.words.isNullOrEmpty())

internal fun needsRomanizedWordAlignment(entry: LyricsEntry): Boolean =
    !entry.isBackground &&
        !hasCompleteRomanizedWords(entry) &&
        !entry.romanizedText.isNullOrBlank() &&
        entry.romanizedText != entry.text &&
        !entry.words.isNullOrEmpty()

internal fun needsJapaneseDictionaryPreparation(
    entry: LyricsEntry,
    romanizationEnabled: Boolean,
): Boolean = romanizationEnabled && needsLocalRomanizationOrAlignment(entry)

internal fun needsJapaneseDictionaryForAiAlignment(
    nonEmpty: List<Pair<Int, LyricsEntry>>,
    romanizedLines: List<String>?,
    romanizedWordsPerLine: List<List<String>?>?,
): Boolean =
    nonEmpty.indices.any { translationIndex ->
        val entry = nonEmpty[translationIndex].second
        val wordCount = entry.words.orEmpty().count { !entry.isBackground && !it.isBackground }
        val romanized = romanizedLines?.getOrNull(translationIndex)
        val aiWords = romanizedWordsPerLine?.getOrNull(translationIndex)
        !romanized.isNullOrBlank() &&
            wordCount > 0 &&
            !hasCompleteRomanizedWords(entry) &&
            aiWords?.size != wordCount
    }

private fun mergeRomanizedWords(
    entry: LyricsEntry,
    generated: List<WordTimestamp>,
): List<WordTimestamp> {
    val existing = entry.romanizedWords ?: return generated
    if (existing.size > generated.size) {
        return if (hasCompleteRomanizedWords(entry)) existing else generated
    }
    val isAlignedPrefix =
        existing.indices.all { index ->
            val source = entry.words?.getOrNull(index) ?: return@all false
            existing[index].isBackground == source.isBackground &&
                existing[index].startTime >= source.startTime &&
                existing[index].endTime <= source.endTime
        }
    if (!isAlignedPrefix) return generated
    return generated.mapIndexed { index, word ->
        existing.getOrNull(index)?.takeIf { it.text.isNotBlank() } ?: word
    }
}

internal fun romanizationDetectionText(entries: List<LyricsEntry>): String =
    entries.joinToString("\n") { entry ->
        entry.text + entry.words.orEmpty().joinToString("") { it.text }
    }

internal fun alignRomanizedWords(
    entry: LyricsEntry,
    romanizedLine: String,
    aiWords: List<String>?,
): List<WordTimestamp>? {
    val words = entry.words ?: return null
    val includedIndices = words.indices.filter { !entry.isBackground && !words[it].isBackground }
    if (includedIndices.isEmpty()) return words
    val includedWords = includedIndices.map(words::get)
    val guides =
        if (aiWords?.size == includedWords.size) {
            aiWords.map(LyricsRomanizer::stripAccents)
        } else {
            includedWords.map { LyricsRomanizer.romanizeIsolatedWord(it.text) }
        }
    val aligned = LyricsRomanizer.alignRomanizationAnchors(romanizedLine, guides, includedWords)
    val result = words.toMutableList()
    includedIndices.forEachIndexed { index, originalIndex ->
        result[originalIndex] = words[originalIndex].copy(text = aligned.getOrNull(index)?.takeIf(String::isNotBlank) ?: guides[index])
    }
    return result
}

internal fun LyricsEntry.withAlignedRomanizedWords(): LyricsEntry {
    if (!needsRomanizedWordAlignment(this)) return this
    val romanizedLine = romanizedText ?: return this
    return alignRomanizedWords(this, romanizedLine, null)?.let { copy(romanizedWords = mergeRomanizedWords(this, it)) } ?: this
}

internal fun usableProviderLyrics(entries: List<LyricsEntry>): Boolean {
    val text = entries.map { it.text.trim() }.filter(String::isNotBlank)
    return text.isNotEmpty() && !text.all { PLACEHOLDER_LYRIC.matches(it) }
}

private val PLACEHOLDER_LYRIC = Regex("(?:test|sample|placeholder) (?:line|lyrics?)(?: \\d+)?[.!]?", RegexOption.IGNORE_CASE)

class LyricsRepository(
    private val lyricsClient: LyricsFetcher,
    private val lyricsTranslationClient: LyricsTranslationClient,
    private val databaseDao: DatabaseDao? = null,
    private val youtubeLyricsApi: YouTubeLyricsApi? = null,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val prepareJapaneseDictionary: suspend () -> Boolean = ::prepareJapaneseRomanizer,
    private val providerResultsDispatcher: CoroutineDispatcher? = Dispatchers.Default,
    private val aiModelCatalog: AiModelCatalog? = null,
) {
    private val translationLocksGuard = Mutex()
    private val translationLocks = linkedMapOf<String, Mutex>()
    private val songwritersCacheGuard = Mutex()
    private val songwritersCache = linkedMapOf<String, List<String>>()

    private val previewLyricsMutex = Mutex()
    private var previewLyrics: LyricsProviderResult? = null

    internal suspend fun getPreviewLyrics(): LyricsProviderResult? =
        previewLyricsMutex.withLock {
            previewLyrics?.let { return@withLock it }
            val providers =
                LyricsProvider.preferredProvidersForFetch(
                    LyricsProvider.parseUserOrderFromStoredCsv(Settings.shared.lyricsProviderOrder),
                    LyricsProvider.parseEnabledFromStoredCsv(Settings.shared.lyricsEnabledProviders),
                )
            val synced =
                providers.firstNotNullOfOrNull { provider ->
                    getProviderResultsFlow(
                        videoId = LYRICS_PREVIEW_VIDEO_ID,
                        title = "Never Gonna Give You Up",
                        artist = "Rick Astley",
                        duration = LYRICS_PREVIEW_DURATION_MS,
                        preferredProviders = listOf(provider),
                    ).firstOrNull { it.timingKind != LyricsTimingKind.UNSYNCED }
                }
            synced.also { previewLyrics = it }
        }

    suspend fun getSongwriters(videoId: String): List<String> {
        songwritersCacheGuard.withLock {
            songwritersCache[videoId]?.let { return it }
        }
        val api = youtubeLyricsApi ?: return emptyList()
        return try {
            val browseResponse = api.browse("MPTC$videoId")
            val songwriters = YouTubeLyricsProvider.extractSongwriters(browseResponse)
            songwritersCacheGuard.withLock {
                if (songwritersCache.size >= 100) {
                    val oldestKey = songwritersCache.keys.firstOrNull()
                    if (oldestKey != null) songwritersCache.remove(oldestKey)
                }
                songwritersCache[videoId] = songwriters
            }
            songwriters
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getCachedLyrics(
        videoId: String,
        preferredProviders: List<LyricsProvider> = configuredLyricsProviders(),
        isVideoContext: Boolean = false,
    ): LyricsWithProviderModel? =
        cachedLyricsResult(
            databaseDao?.cachedLyrics(videoId),
            preferredProviders + if (isVideoContext) listOf(LyricsProvider.SIMPMUSIC) else emptyList(),
        )

    private fun cachedLyricsResult(
        entity: LyricsEntity?,
        providers: List<LyricsProvider>,
    ): LyricsWithProviderModel? =
        entity?.userLyrics?.let { LyricsWithProviderModel(it, USER_LYRICS_PROVIDER, isUserSelected = true) } ?: entity
            ?.takeIf {
                it.provider != LyricsEntity.LYRICS_NOT_FOUND && it.lyrics.isNotBlank() &&
                    (
                        it.isUserSelected || isLocalLyricsProvider(it.provider) ||
                            isRetiredLyricsProvider(it.provider) ||
                            providerPriority(it.provider, providers) != Int.MAX_VALUE
                    ) &&
                    (isLocalLyricsProvider(it.provider) || usableProviderLyrics(LyricsUtils.parseLyrics(it.lyrics)))
            }?.let { LyricsWithProviderModel(it.lyrics, it.provider, isUserSelected = it.isUserSelected) }

    suspend fun getLyrics(
        videoId: String,
        title: String,
        artist: String,
        duration: Long?,
        album: String? = null,
        preferredProviders: List<LyricsProvider> =
            LyricsProvider.preferredProvidersForFetch(
                LyricsProvider.parseUserOrderFromStoredCsv(Settings.shared.lyricsProviderOrder),
                LyricsProvider.parseEnabledFromStoredCsv(Settings.shared.lyricsEnabledProviders),
            ),
        skipCache: Boolean = false,
        isVideoContext: Boolean = false,
        preferVideoProviders: Boolean = false,
        ignoreVideoIdentity: Boolean = false,
        onLyricsAvailable: suspend (LyricsWithProviderModel) -> Unit = {},
    ): LyricsWithProviderModel? {
        getUserLyrics(videoId)?.let { return LyricsWithProviderModel(it, USER_LYRICS_PROVIDER, isUserSelected = true) }
        val lookupVideoId = videoId.takeUnless { ignoreVideoIdentity }
        val preferWordSynced = Settings.shared.preferWordSyncedLyrics
        val preferredTiming = if (preferWordSynced) LyricsTimingKind.WORD_SYNCED else LyricsTimingKind.LINE_SYNCED
        val providers =
            (preferredProviders + if (isVideoContext) listOf(LyricsProvider.SIMPMUSIC) else emptyList())
                .filter { it != LyricsProvider.AUTO }
                .distinct()
        val lookupKey =
            lyricsLookupKey(
                lookupVideoId.orEmpty(),
                title,
                artist,
                duration,
                album,
                preferredProviders,
                isVideoContext,
                preferVideoProviders,
            )
        val fetchOrder = lookupKey.providers
        val cachedLyrics =
            databaseDao?.cachedLyrics(videoId)?.let { cached ->
                if (skipCache && cached.provider == LyricsEntity.LYRICS_NOT_FOUND) {
                    databaseDao.cacheLyricsIfUnchanged(cached.copy(missLookupKey = null, missExpiresAt = null), cached)
                } else {
                    cached
                }
            }
        if (!skipCache && cachedMissExpiry(cachedLyrics, lookupKey) != null) return null
        val cachedResult = cachedLyricsResult(cachedLyrics, providers)
        if (!skipCache && cachedResult != null &&
            (
                cachedResult.isUserSelected || isLocalLyricsProvider(cachedResult.provider) ||
                    (
                        providerPriority(cachedResult.provider, providers) == 0 &&
                            lyricsTimingKind(LyricsUtils.parseLyrics(cachedResult.lyrics)) >= preferredTiming
                    )
            )
        ) {
            return cachedResult
        }

        var finalResult = cachedResult.takeUnless { skipCache }
        var bestTimingKind = finalResult?.let { lyricsTimingKind(LyricsUtils.parseLyrics(it.lyrics)) } ?: LyricsTimingKind.UNSYNCED
        var bestProviderIndex = finalResult?.let { providerPriority(it.provider, providers) } ?: Int.MAX_VALUE
        var hadProviderFailure = false
        finalResult?.let { onLyricsAvailable(it) }

        suspend fun acceptFetch(
            fetch: ProviderLyricsFetch,
            providerIndex: Int,
        ) {
            hadProviderFailure = hadProviderFailure || fetch.failed
            for (candidate in fetch.lyrics) {
                val parsed = LyricsUtils.parseLyrics(candidate.lyrics)
                if (!usableProviderLyrics(parsed)) {
                    hadProviderFailure = true
                    continue
                }
                val timingKind = lyricsTimingKind(parsed)
                val synced = timingKind != LyricsTimingKind.UNSYNCED
                val bestSynced = bestTimingKind != LyricsTimingKind.UNSYNCED
                val better =
                    if (preferWordSynced && timingKind != bestTimingKind) {
                        timingKind > bestTimingKind
                    } else {
                        (synced && !bestSynced) ||
                            (
                                synced == bestSynced &&
                                    (
                                        providerIndex < bestProviderIndex ||
                                            (providerIndex == bestProviderIndex && timingKind > bestTimingKind)
                                    )
                            )
                    }
                if (finalResult == null || better) {
                    finalResult = candidate
                    bestTimingKind = timingKind
                    bestProviderIndex = providerIndex
                    currentCoroutineContext().ensureActive()
                    onLyricsAvailable(candidate)
                }
            }
        }

        for ((fetchIndex, provider) in fetchOrder.withIndex()) {
            val index = providers.indexOf(provider).takeIf { it >= 0 } ?: Int.MAX_VALUE
            currentCoroutineContext().ensureActive()
            if (bestTimingKind >= preferredTiming && index >= bestProviderIndex) continue
            val fetch = fetchProviderLyrics(lookupVideoId, title, artist, duration, album, provider, isVideoContext = isVideoContext)
            acceptFetch(fetch, index)
            if (bestTimingKind >= preferredTiming &&
                fetchOrder.drop(fetchIndex + 1).none { providerPriority(it.name, providers) < bestProviderIndex }
            ) {
                break
            }
        }

        val selectedResult = finalResult
        if (skipCache && selectedResult == null && !cachedLyrics?.lyrics.isNullOrBlank()) return null
        if (databaseDao != null && selectedResult?.isComplete != false && (selectedResult != null || !hadProviderFailure)) {
            currentCoroutineContext().ensureActive()
            val offsetMs = savedLyricsOffset(videoId)
            var expected = cachedLyrics
            var stored: LyricsEntity?
            do {
                stored =
                    databaseDao.cacheLyricsIfUnchanged(
                        cachedLyrics?.takeIf { !skipCache && cachedResult != null && selectedResult === cachedResult }
                            ?: LyricsEntity(
                                id = videoId,
                                lyrics = selectedResult?.lyrics.orEmpty(),
                                provider = selectedResult?.provider ?: LyricsEntity.LYRICS_NOT_FOUND,
                                offsetMs = offsetMs,
                                missLookupKey = if (selectedResult == null) Json.encodeToString(lookupKey) else null,
                                missExpiresAt = if (selectedResult == null) nowMs() + NEGATIVE_CACHE_TTL_MS else null,
                            ),
                        expected = expected,
                    )
                if (stored == null || stored.isUserSelected || isLocalLyricsProvider(stored.provider) || selectedResult == null ||
                    !prefersLyrics(selectedResult, LyricsWithProviderModel(stored.lyrics, stored.provider), providers, preferWordSynced)
                ) {
                    break
                }
                expected = stored
            } while (true)
            return cachedLyricsResult(stored, providers)
        }

        return getUserLyrics(videoId)?.let { LyricsWithProviderModel(it, USER_LYRICS_PROVIDER, isUserSelected = true) } ?: selectedResult
    }

    fun getProviderResultsFlow(
        videoId: String?,
        title: String,
        artist: String,
        duration: Long?,
        album: String? = null,
        preferredProviders: List<LyricsProvider> = LyricsProvider.userConfigurable + LyricsProvider.afterUserLyricsFallbacks,
        failOnError: Boolean = false,
    ): Flow<LyricsProviderResult> {
        val providers = preferredProviders.filter { it != LyricsProvider.AUTO }.distinct()
        val semaphore = Semaphore(PROVIDER_RESULTS_CONCURRENCY)
        val rawFlow =
            channelFlow {
                for (provider in providers) {
                    launch {
                        val results =
                            semaphore.withPermit {
                                fetchProviderLyricsResults(videoId, title, artist, duration, album, provider, failOnError)
                            }
                        for (result in results) {
                            val document = LyricsUtils.parseMlxDocument(result.lyrics)
                            val parsed = document?.lines ?: LyricsUtils.parseLyrics(result.lyrics)
                            if (provider != LyricsProvider.USER && !usableProviderLyrics(parsed)) continue

                            send(
                                LyricsProviderResult(
                                    provider = provider,
                                    providerName = lyricsProviderLabel(result.provider, document) ?: result.provider,
                                    lyrics = result.lyrics,
                                    timingKind = lyricsTimingKind(parsed),
                                    song =
                                        MlxSong(
                                            title = document?.song?.title?.takeIf(String::isNotBlank) ?: title,
                                            artist = document?.song?.artist?.takeIf(String::isNotBlank) ?: artist,
                                            album = document?.song?.album?.takeIf(String::isNotBlank) ?: album,
                                            durationMs = document?.song?.durationMs?.takeIf { it > 0 } ?: duration,
                                        ),
                                    preview = lyricsResultPreview(parsed),
                                ),
                            )
                        }
                    }
                }
            }
        return if (providerResultsDispatcher != null) rawFlow.flowOn(providerResultsDispatcher) else rawFlow
    }

    suspend fun getProviderResults(
        videoId: String?,
        title: String,
        artist: String,
        duration: Long?,
        album: String? = null,
        preferredProviders: List<LyricsProvider> = LyricsProvider.userConfigurable + LyricsProvider.afterUserLyricsFallbacks,
        failOnError: Boolean = false,
    ): List<LyricsProviderResult> {
        val providerOrder =
            preferredProviders
                .filter { it != LyricsProvider.AUTO }
                .distinct()
                .withIndex()
                .associate { (index, provider) -> provider to index }
        return getProviderResultsFlow(
            videoId = videoId,
            title = title,
            artist = artist,
            duration = duration,
            album = album,
            preferredProviders = preferredProviders,
            failOnError = failOnError,
        ).toList()
            .sortedBy { providerOrder[it.provider] ?: Int.MAX_VALUE }
    }

    private suspend fun fetchProviderLyrics(
        videoId: String?,
        title: String,
        artist: String,
        duration: Long?,
        album: String?,
        provider: LyricsProvider,
        allSources: Boolean = false,
        isVideoContext: Boolean = false,
    ): ProviderLyricsFetch {
        val normalizedVideoId = videoId?.takeIf { it.isNotBlank() }
        return try {
            withTimeoutOrNull(
                (if (provider == LyricsProvider.LYPRIO) LyprioLyricsProvider.SEARCH_TIMEOUT_MS else PROVIDER_TIMEOUT_MS).milliseconds,
            ) {
                ProviderLyricsFetch(
                    lyrics =
                        if (allSources) {
                            lyricsClient.getLyricsResults(title, artist, duration, album, normalizedVideoId, provider, isVideoContext)
                        } else {
                            listOfNotNull(
                                lyricsClient.getLyrics(title, artist, duration, album, normalizedVideoId, provider, isVideoContext),
                            )
                        },
                )
            } ?: ProviderLyricsFetch(failed = true)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ProviderLyricsFetch(failed = true)
        }
    }

    private suspend fun fetchProviderLyricsResults(
        videoId: String?,
        title: String,
        artist: String,
        duration: Long?,
        album: String?,
        provider: LyricsProvider,
        failOnError: Boolean,
    ): List<LyricsWithProviderModel> {
        if (provider ==
            LyricsProvider.USER
        ) {
            return listOfNotNull(
                videoId
                    ?.let { databaseDao?.cachedLyrics(it) }
                    ?.let { it.userLyrics ?: it.stashedUserLyrics }
                    ?.let { LyricsWithProviderModel(it, USER_LYRICS_PROVIDER, isUserSelected = true) },
            )
        }
        val result = fetchProviderLyrics(videoId, title, artist, duration, album, provider, allSources = true)
        check(!failOnError || !result.failed) { "Lyrics provider search failed: $provider" }
        return result.lyrics
    }

    private data class ProviderLyricsFetch(
        val lyrics: List<LyricsWithProviderModel> = emptyList(),
        val failed: Boolean = false,
    )

    suspend fun getCachedLyricsMissExpiry(
        videoId: String,
        title: String,
        artist: String,
        duration: Long?,
        album: String? = null,
        preferredProviders: List<LyricsProvider>,
        isVideoContext: Boolean = false,
        preferVideoProviders: Boolean = false,
    ): Long? =
        cachedMissExpiry(
            databaseDao?.cachedLyrics(videoId),
            lyricsLookupKey(videoId, title, artist, duration, album, preferredProviders, isVideoContext, preferVideoProviders),
        )

    private fun cachedMissExpiry(
        entity: LyricsEntity?,
        lookupKey: LyricsLookupKey,
    ): Long? {
        if (entity?.provider != LyricsEntity.LYRICS_NOT_FOUND || entity.missLookupKey != Json.encodeToString(lookupKey)) return null
        val expiresAt = entity.missExpiresAt ?: return null
        return expiresAt.takeIf { nowMs() in (it - NEGATIVE_CACHE_TTL_MS) until it }
    }

    private fun lyricsLookupKey(
        videoId: String,
        title: String,
        artist: String,
        duration: Long?,
        album: String?,
        preferredProviders: List<LyricsProvider>,
        isVideoContext: Boolean,
        preferVideoProviders: Boolean,
    ): LyricsLookupKey {
        val providers =
            (preferredProviders + if (isVideoContext) listOf(LyricsProvider.SIMPMUSIC) else emptyList())
                .filter {
                    it != LyricsProvider.AUTO
                }.distinct()
                .let {
                    if (preferVideoProviders) (LyricsProvider.afterUserLyricsFallbacks + it).distinct() else it
                }
        return LyricsLookupKey(videoId, title, artist, duration, album, providers, isVideoContext, Settings.shared.preferWordSyncedLyrics)
    }

    @Serializable
    private data class LyricsLookupKey(
        val videoId: String,
        val title: String,
        val artist: String,
        val duration: Long?,
        val album: String?,
        val providers: List<LyricsProvider>,
        val isVideoContext: Boolean,
        val preferWordSynced: Boolean = false,
    )

    suspend fun selectProviderResult(
        videoId: String,
        result: LyricsProviderResult,
    ): LyricsProviderResult {
        if (result.provider == LyricsProvider.USER) {
            databaseDao?.restoreStashedUserLyrics(videoId)
            return getUserLyrics(videoId)?.let(::userProviderResult) ?: result
        }
        // An explicit provider choice replaces the User fork; keep it so the User entry can restore it.
        databaseDao?.stashUserLyrics(videoId)
        databaseDao?.saveLyrics(
            LyricsEntity(
                id = videoId,
                lyrics = result.lyrics,
                provider = result.providerName,
                isUserSelected = true,
            ),
        )
        return result
    }

    private fun userProviderResult(lyrics: String): LyricsProviderResult =
        LyricsProviderResult(
            LyricsProvider.USER,
            USER_LYRICS_PROVIDER,
            lyrics,
            lyricsTimingKind(LyricsUtils.parseLyrics(lyrics)),
            preview = lyricsResultPreview(LyricsUtils.parseLyrics(lyrics)),
        )

    suspend fun getLyricsOffset(videoId: String): Int = savedLyricsOffset(videoId)

    suspend fun getVideoSpecificLyrics(
        videoId: String,
        title: String,
        artist: String,
        duration: Long?,
        album: String? = null,
        skipCache: Boolean = false,
        onLyricsAvailable: suspend (LyricsWithProviderModel) -> Unit = {},
    ): LyricsWithProviderModel? {
        getUserLyrics(videoId)?.let { return LyricsWithProviderModel(it, USER_LYRICS_PROVIDER, isUserSelected = true) }
        val providers = (configuredLyricsProviders() + LyricsProvider.SIMPMUSIC).filter { it in VIDEO_SPECIFIC_LYRICS_PROVIDERS }
        val cached =
            databaseDao
                ?.cachedLyrics(videoId)
                ?.takeIf {
                    it.provider != LyricsEntity.LYRICS_NOT_FOUND && it.lyrics.isNotBlank() &&
                        (isLocalLyricsProvider(it.provider) || usableProviderLyrics(LyricsUtils.parseLyrics(it.lyrics)))
                }?.let { LyricsWithProviderModel(it.lyrics, it.provider, isUserSelected = it.isUserSelected) }
        if (!skipCache && cached != null && (cached.isUserSelected || isLocalLyricsProvider(cached.provider))) return cached
        var bestResult =
            cached?.takeIf {
                !skipCache && (isRetiredLyricsProvider(it.provider) || providerPriority(it.provider, providers) != Int.MAX_VALUE)
            }
        bestResult?.let { onLyricsAvailable(it) }
        for ((index, provider) in providers.withIndex()) {
            currentCoroutineContext().ensureActive()
            val results = fetchProviderLyrics(videoId, title, artist, duration, album, provider, isVideoContext = true)
            for (result in results.lyrics) {
                if (!usableProviderLyrics(LyricsUtils.parseLyrics(result.lyrics))) continue
                val current = bestResult
                if (current == null || prefersLyrics(result, current, providers, Settings.shared.preferWordSyncedLyrics)) {
                    bestResult = result
                    currentCoroutineContext().ensureActive()
                    onLyricsAvailable(result)
                }
            }
            val best = bestResult
            if (best != null && LyricsUtils.hasPlaybackTiming(LyricsUtils.parseLyrics(best.lyrics)) &&
                providerPriority(best.provider, providers) <= index
            ) {
                break
            }
        }
        return bestResult
    }

    suspend fun setLyricsOffset(
        videoId: String,
        offsetMs: Int,
    ): Boolean {
        val dao = databaseDao ?: return false
        dao.setLyricsOffset(videoId, offsetMs)
        return true
    }

    private suspend fun savedLyricsOffset(videoId: String): Int =
        databaseDao?.lyricsOffset(videoId) ?: databaseDao?.legacyLyricsOffset(videoId) ?: 0

    suspend fun getRetimedMvLyrics(
        videoId: String,
        richLyrics: MlxDocument,
        title: String? = null,
        artist: String? = null,
        duration: Long? = null,
        album: String? = null,
        minimumScore: Float = MusicVideoLyricsRetimer.SCORE_MEDIUM_CONFIDENCE,
    ): MlxDocument? {
        if (richLyrics.meta.ignoreRetiming || lyricsTimingKind(richLyrics.lines) == LyricsTimingKind.UNSYNCED) return null
        val unisonResult =
            try {
                lyricsClient.getLyrics(
                    title = title ?: richLyrics.song.title.orEmpty(),
                    artist = artist ?: richLyrics.song.artist.orEmpty(),
                    duration = duration,
                    album = album,
                    videoId = videoId,
                    provider = LyricsProvider.UNISON,
                    isVideoContext = true,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

        if (unisonResult != null) {
            val unisonDoc = withContext(Dispatchers.Default) { LyricsUtils.parseMlxDocument(unisonResult.lyrics) }
            if (unisonDoc != null) {
                val retimed =
                    withContext(Dispatchers.Default) {
                        MusicVideoLyricsRetimer.retimeLyrics(richLyrics, unisonDoc.toTranscriptCues())
                    }
                if (retimed != null && retimed.score >= minimumScore) {
                    return retimed.document
                }
            }
        }

        val api = youtubeLyricsApi ?: return null
        val subtitleProvider = YouTubeSubtitleLyricsProvider(api)

        val allCueTracks = subtitleProvider.getAllTranscriptCueTracks(videoId)
        if (allCueTracks.isEmpty()) return null

        val finalResult =
            withContext(Dispatchers.Default) {
                var bestResult: MusicVideoLyricsRetimer.RetimedLyricsResult? = null
                for (cues in allCueTracks) {
                    currentCoroutineContext().ensureActive()
                    val result = MusicVideoLyricsRetimer.retimeLyrics(richLyrics, cues) ?: continue
                    if (result.score >= minimumScore &&
                        (bestResult == null || result.score > bestResult.score)
                    ) {
                        bestResult = result
                    }
                    if (result.score >= MusicVideoLyricsRetimer.SCORE_PRECISE_CONFIDENCE) break
                }
                bestResult
            } ?: return null

        return finalResult.document
    }

    private fun MlxDocument.toTranscriptCues(): List<YouTubeTranscriptCue> =
        lines.mapIndexedNotNull { index, line ->
            val text =
                line.words
                    ?.filterNot { it.isBackground }
                    ?.joinToString("") { it.text + if (it.hasTrailingSpace) " " else "" }
                    ?.trim()
                    ?.ifBlank { line.text }
                    ?: line.text
            if (text.isBlank() || line.time < 0L) {
                null
            } else {
                val endMs = line.endTime ?: lines.getOrNull(index + 1)?.time ?: (line.time + 5_000L)
                YouTubeTranscriptCue(
                    text = text,
                    startMs = line.time,
                    durationMs = (endMs - line.time).coerceAtLeast(1L),
                )
            }
        }

    suspend fun enrichLyricsEntries(
        videoId: String,
        entries: List<LyricsEntry>,
        forceAiTranslation: Boolean = false,
        allowRemoteTranslation: Boolean = true,
        onDictionaryPreparation: suspend () -> Unit = {},
        onTranslationProgress: suspend (LyricsTranslationProgress) -> Unit = {},
        onTranslationFailure: suspend (LyricsTranslationFailure) -> Unit = {},
    ): List<LyricsEntry> {
        if (entries.isEmpty()) return entries
        val nonEmpty =
            entries.mapIndexedNotNull { index, entry ->
                if (entry.text.isNotBlank() ||
                    !entry.words.isNullOrEmpty() || !entry.backgroundText.isNullOrBlank()
                ) {
                    index to entry
                } else {
                    null
                }
            }
        if (nonEmpty.isEmpty()) return entries

        var enriched = entries
        val sourceLines = nonEmpty.map { (_, entry) -> translationSourceLine(entry) }
        val dictionaryDetectionText = romanizationDetectionText(entries)
        val prepareDictionary: suspend () -> Boolean = {
            onDictionaryPreparation()
            prepareJapaneseDictionary()
        }
        val enabledLanguages =
            Settings.shared.lyricsRomanizeLanguages
                .split(',')
                .mapNotNull { part ->
                    val pieces = part.split(':', limit = 2)
                    pieces.getOrNull(0)?.takeIf { pieces.getOrNull(1)?.toBooleanStrictOrNull() ?: false }
                }.toSet()
        val romanizationEnabled = Settings.shared.lyricsRomanizationEnabled
        val targetLanguage = resolveTargetLanguage(Settings.shared.lyricsAiTargetLanguage)
        val translationStyle = Settings.shared.lyricsAiTranslationMode
        val backgroundSources =
            if (translationStyle == "Romanized") {
                emptyList()
            } else {
                nonEmpty.mapNotNull { (index, entry) ->
                    backgroundTranslationSourceLine(entry).takeIf(String::isNotBlank)?.let { index to it }
                }
            }
        val separateVocals =
            translationStyle != "Romanized" &&
                (backgroundSources.isNotEmpty() || nonEmpty.any { it.second.isBackground })
        val translationLines =
            nonEmpty.map { (_, entry) ->
                if (separateVocals && entry.isBackground) entry.text else translationSourceLine(entry)
            } + backgroundSources.map { it.second }
        val sourceWordsPerLine = nonEmpty.map { (_, entry) -> translationSourceWords(entry) } + backgroundSources.map { null }
        val sourceHash = translationLines.joinToString("\n").hashCode().toString()
        val skipLanguageDetection = forceAiTranslation || Settings.shared.lyricsAiActivationMode != "Automatic"
        if (Settings.shared.lyricsAiTranslationEnabled && allowRemoteTranslation && targetLanguage.isNotBlank() &&
            (forceAiTranslation || Settings.shared.lyricsAiActivationMode != "Manual")
        ) {
            validateSavedAiModels()
        }
        val configKey = buildTranslationConfigKey() + if (translationStyle != "Romanized") "|passages-v1|$skipLanguageDetection" else ""

        if (Settings.shared.lyricsAiTranslationEnabled && targetLanguage.isNotBlank()) {
            val activationMode = Settings.shared.lyricsAiActivationMode
            val cachedTranslation =
                loadCachedTranslation(
                    videoId,
                    targetLanguage,
                    translationStyle,
                    sourceHash,
                    configKey,
                    translationLines,
                    sourceWordsPerLine,
                    allowNotNeeded = !forceAiTranslation,
                    allowLegacyTranslation = !allowRemoteTranslation,
                )
            val aiResult =
                if (cachedTranslation != null) {
                    cachedTranslation.result
                } else if (!allowRemoteTranslation || (activationMode == "Manual" && !forceAiTranslation)) {
                    null
                } else {
                    val lockKey = "$videoId|$targetLanguage|$translationStyle|$sourceHash|$configKey"
                    lockFor(lockKey).withLock {
                        val lockedCache =
                            loadCachedTranslation(
                                videoId,
                                targetLanguage,
                                translationStyle,
                                sourceHash,
                                configKey,
                                translationLines,
                                sourceWordsPerLine,
                                allowNotNeeded = !forceAiTranslation,
                            )
                        if (lockedCache != null) {
                            lockedCache.result
                        } else {
                            try {
                                translateAndCache(
                                    videoId = videoId,
                                    sourceLines = translationLines,
                                    sourceWordsPerLine = sourceWordsPerLine,
                                    targetLanguage = targetLanguage,
                                    translationStyle = translationStyle,
                                    sourceHash = sourceHash,
                                    configKey = configKey,
                                    skipLanguageDetection = skipLanguageDetection,
                                    onTranslationProgress = { selected ->
                                        onTranslationProgress(
                                            LyricsTranslationProgress(
                                                lines =
                                                    selected
                                                        .mapNotNull { nonEmpty.getOrNull(it)?.first }
                                                        .filter {
                                                            if (translationStyle == "Romanized") {
                                                                entries[it].romanizedText.isNullOrBlank()
                                                            } else {
                                                                entries[it].translatedText.isNullOrBlank()
                                                            }
                                                        }.toSet(),
                                                backgroundLines =
                                                    selected
                                                        .mapNotNull { backgroundSources.getOrNull(it - nonEmpty.size)?.first }
                                                        .filter { entries[it].backgroundTranslatedText.isNullOrBlank() }
                                                        .toSet(),
                                            ),
                                        )
                                    },
                                    onTranslationFailure = onTranslationFailure,
                                )
                            } finally {
                                withContext(NonCancellable) { onTranslationProgress(LyricsTranslationProgress()) }
                            }
                        }
                    }
                }
            if (aiResult != null) {
                val needsJapaneseDictionaryForAlignment =
                    needsJapaneseDictionaryForAiAlignment(
                        nonEmpty,
                        aiResult.romanizedLines,
                        aiResult.romanizedWordsPerLine,
                    )
                if (romanizationEnabled && enabledLanguages.isNotEmpty() && needsJapaneseDictionaryForAlignment) {
                    currentCoroutineContext().ensureActive()
                    LyricsRomanizer.prepareJapaneseRomanizationIfNeeded(dictionaryDetectionText, enabledLanguages, prepareDictionary)
                }
                enriched =
                    withContext(Dispatchers.Default) {
                        val coroutineContext = currentCoroutineContext()
                        enriched.toMutableList().also { mutable ->
                            nonEmpty.forEachIndexed { translationIndex, (entryIndex, entry) ->
                                coroutineContext.ensureActive()
                                val translated =
                                    aiResult.translatedLines?.getOrNull(translationIndex)?.takeIf {
                                        it.isNotBlank() && it != translationLines[translationIndex]
                                    }
                                val romanized = aiResult.romanizedLines?.getOrNull(translationIndex)?.takeIf { it.isNotBlank() }
                                val aiWords = aiResult.romanizedWordsPerLine?.getOrNull(translationIndex)
                                val wordRom =
                                    if (!romanized.isNullOrBlank() && !hasCompleteRomanizedWords(entry)) {
                                        alignRomanizedWords(entry, romanized, aiWords)
                                    } else {
                                        null
                                    }
                                val alignedWords = wordRom?.let { mergeRomanizedWords(entry, it) }
                                val mainRoman =
                                    if (hasCompleteRomanizedWords(entry)) {
                                        romanizedWordText(entry.romanizedWords)
                                    } else {
                                        romanizedWordText(alignedWords)
                                    }
                                mutable[entryIndex] =
                                    entry.copy(
                                        translatedText = entry.translatedText?.takeIf { it.isNotBlank() } ?: translated,
                                        romanizedText =
                                            if (!entry.romanizedText.isNullOrBlank()) {
                                                entry.romanizedText
                                            } else {
                                                (
                                                    mainRoman
                                                        ?: romanized
                                                        ?: entry.romanizedText
                                                )
                                            },
                                        romanizedWords =
                                            if (hasCompleteRomanizedWords(entry)) {
                                                entry.romanizedWords
                                            } else {
                                                alignedWords ?: entry.romanizedWords
                                            },
                                    )
                            }
                            backgroundSources.forEachIndexed { index, (entryIndex, source) ->
                                val translated =
                                    aiResult.translatedLines
                                        ?.getOrNull(nonEmpty.size + index)
                                        ?.takeIf { it.isNotBlank() && it != source }
                                mutable[entryIndex] =
                                    mutable[entryIndex].copy(
                                        backgroundTranslatedText = translated ?: mutable[entryIndex].backgroundTranslatedText,
                                    )
                            }
                        }
                    }
            }
        }

        val needsJapaneseDictionary =
            nonEmpty.any { (index, _) -> needsJapaneseDictionaryPreparation(enriched[index], romanizationEnabled) }
        if (enabledLanguages.isNotEmpty() && needsJapaneseDictionary) {
            currentCoroutineContext().ensureActive()
            LyricsRomanizer.prepareJapaneseRomanizationIfNeeded(dictionaryDetectionText, enabledLanguages, prepareDictionary)
        }
        if (romanizationEnabled && enabledLanguages.isNotEmpty()) {
            val fullText = sourceLines.joinToString("\n")
            enriched =
                withContext(Dispatchers.Default) {
                    val coroutineContext = currentCoroutineContext()
                    val romanized = enriched.toMutableList()
                    nonEmpty.forEachIndexed { i, (index, entry) ->
                        coroutineContext.ensureActive()
                        if (!romanized[index].romanizedText.isNullOrBlank()) {
                            return@forEachIndexed
                        }
                        if (hasCompleteRomanizedWords(romanized[index])) {
                            romanizedWordText(romanized[index].romanizedWords)?.let { text ->
                                romanized[index] = romanized[index].copy(romanizedText = text)
                                return@forEachIndexed
                            }
                        }
                        val lineToRomanize = sourceLines[i]
                        if (!entry.words.isNullOrEmpty()) {
                            val wordRoman =
                                LyricsRomanizer.romanizeWords(
                                    fullText = fullText,
                                    line = lineToRomanize,
                                    words = entry.words,
                                    enabledLanguages = enabledLanguages,
                                    cyrillicByLine = Settings.shared.lyricsRomanizeCyrillicByLine,
                                )
                            if (wordRoman != null) {
                                val romanizedWords = mergeRomanizedWords(romanized[index], wordRoman.second)
                                val mainRomanText =
                                    romanizedWords
                                        .filter { !it.isBackground }
                                        .joinToString("") { it.text + if (it.hasTrailingSpace) " " else "" }
                                        .trim()
                                        .ifBlank { wordRoman.first }
                                romanized[index] =
                                    romanized[index].copy(
                                        romanizedText = mainRomanText,
                                        romanizedWords = romanizedWords,
                                    )
                            }
                        } else {
                            val roman =
                                LyricsRomanizer.romanize(
                                    fullText = fullText,
                                    line = lineToRomanize,
                                    enabledLanguages = enabledLanguages,
                                    cyrillicByLine = Settings.shared.lyricsRomanizeCyrillicByLine,
                                )
                            if (!roman.isNullOrBlank()) {
                                romanized[index] = romanized[index].copy(romanizedText = roman)
                            }
                        }
                    }
                    romanized
                }
        }

        if (enriched.none { !it.romanizedText.isNullOrBlank() }) return enriched
        return withContext(Dispatchers.Default) { enriched.map { it.withAlignedRomanizedWords() } }
    }

    private suspend fun lockFor(key: String): Mutex =
        translationLocksGuard.withLock {
            translationLocks.getOrPut(key) {
                if (translationLocks.size >= 20) {
                    translationLocks.entries
                        .firstOrNull { !it.value.isLocked }
                        ?.let { translationLocks.remove(it.key) }
                }
                Mutex()
            }
        }

    private suspend fun validateSavedAiModels() {
        val catalog = aiModelCatalog ?: return
        val settings = Settings.shared
        val provider = settings.lyricsAiProvider
        val preset = AiProviderPresets.find(provider) ?: return
        val (apiKey, baseUrl) = settings.lyricsAiCredentials(provider)
        val model = settings.lyricsAiModel
        val detector = settings.lyricsAiDetectorModel
        val validated = catalog.validatedModel(preset, apiKey, baseUrl, model)
        val validatedDetector = if (detector.isBlank()) detector else catalog.validatedModel(preset, apiKey, baseUrl, detector)
        if (settings.lyricsAiProvider == provider && settings.lyricsAiCredentials(provider) == (apiKey to baseUrl)) {
            if (settings.lyricsAiModel == model) settings.lyricsAiModel = validated
            if (settings.lyricsAiDetectorModel == detector) settings.lyricsAiDetectorModel = validatedDetector
        }
    }

    private fun resolveTargetLanguage(configuredLanguage: String): String {
        if (configuredLanguage == "device" || configuredLanguage.isBlank()) {
            return runCatching {
                getSystemLanguage()
            }.getOrNull().orEmpty()
        }
        return configuredLanguage
    }

    private suspend fun loadCachedTranslation(
        videoId: String,
        targetLanguage: String,
        translationStyle: String,
        sourceHash: String,
        configKey: String,
        sourceLines: List<String>,
        sourceWordsPerLine: List<List<String>?>,
        allowNotNeeded: Boolean,
        allowLegacyTranslation: Boolean = false,
    ): CachedLyricsTranslation? {
        val entity = databaseDao?.lyrics(videoId)?.firstOrNull() ?: return null
        val configKeys =
            if (allowLegacyTranslation && configKey.endsWith("|passages-v1|false")) {
                val previousKey = configKey.removeSuffix("|passages-v1|false")
                listOf(configKey, previousKey, "$previousKey|vocals-v1|false")
            } else {
                listOf(configKey)
            }
        return configKeys.firstNotNullOfOrNull { key ->
            readCachedTranslation(
                entity,
                targetLanguage,
                translationStyle,
                sourceHash,
                key,
                sourceLines,
                sourceWordsPerLine,
                allowNotNeeded = allowNotNeeded && key == configKey,
            )
        }
    }

    internal fun readCachedTranslation(
        entity: LyricsEntity,
        targetLanguage: String,
        translationStyle: String,
        sourceHash: String,
        configKey: String,
        sourceLines: List<String>? = null,
        sourceWordsPerLine: List<List<String>?>? = null,
        allowNotNeeded: Boolean = true,
    ): CachedLyricsTranslation? {
        val configMatches =
            entity.translationConfigKey == configKey ||
                (!entity.translationNotNeeded && entity.translationConfigKey in legacyTranslationConfigKeys(configKey))
        val valid =
            entity.translationLanguage == targetLanguage &&
                entity.translationMode == translationStyle &&
                entity.translationSourceHash == sourceHash &&
                configMatches
        if (!valid) return null
        if (entity.translationNotNeeded) return CachedLyricsTranslation(null).takeIf { allowNotNeeded }
        val result = decodeCachedTranslation(entity.translatedLyrics) ?: return null
        if (sourceLines != null && !isAlignedCachedResult(result, translationStyle, sourceLines, sourceWordsPerLine)) return null
        return CachedLyricsTranslation(result)
    }

    private suspend fun translateAndCache(
        videoId: String,
        sourceLines: List<String>,
        sourceWordsPerLine: List<List<String>?>,
        targetLanguage: String,
        translationStyle: String,
        sourceHash: String,
        configKey: String,
        skipLanguageDetection: Boolean,
        onTranslationProgress: suspend (List<Int>) -> Unit,
        onTranslationFailure: suspend (LyricsTranslationFailure) -> Unit,
    ): LyricsAiResult? {
        val provider = Settings.shared.lyricsAiProvider
        val (apiKey, baseUrl) = Settings.shared.lyricsAiCredentials(provider)

        suspend fun failed(
            stage: String,
            error: Throwable,
        ) {
            val failure = error.lyricsTranslationFailure()
            PlaybackEventLog.warn(
                PlaybackEventKind.OTHER,
                "AI lyrics processing failed.",
                videoId = videoId,
                details =
                    mapOf(
                        "stage" to stage,
                        "provider" to provider,
                        "model" to Settings.shared.lyricsAiModel,
                        "detectorModel" to Settings.shared.lyricsAiDetectorModel.ifBlank { Settings.shared.lyricsAiModel },
                        "failure" to failure.name,
                        "errorType" to error::class.simpleName.orEmpty(),
                        "inputLines" to sourceLines.size.toString(),
                    ),
            )
            onTranslationFailure(failure)
        }
        val detectPerLine = translationStyle != "Romanized"
        val combineDetection = detectPerLine && provider == "OpenRouter" && Settings.shared.lyricsAiModel.endsWith(":free")
        val needed =
            when {
                skipLanguageDetection || combineDetection -> {
                    sourceLines.map(String::isNotBlank)
                }

                detectPerLine -> {
                    retryLyricsTranslation {
                        lyricsTranslationClient
                            .shouldTranslateLyricsLines(
                                lines = sourceLines,
                                provider = provider,
                                targetLanguage = targetLanguage,
                                apiKey = apiKey,
                                baseUrl = baseUrl,
                                model = Settings.shared.lyricsAiDetectorModel.ifBlank { Settings.shared.lyricsAiModel },
                                systemPrompt = Settings.shared.lyricsAiSystemPrompt,
                            )
                    }.getOrElse { error ->
                        if (error is CancellationException) throw error
                        failed("detection", error)
                        return null
                    }
                }

                else -> {
                    val shouldTranslate =
                        retryLyricsTranslation {
                            lyricsTranslationClient
                                .shouldTranslateLyrics(
                                    lines = sourceLines,
                                    provider = provider,
                                    targetLanguage = targetLanguage,
                                    apiKey = apiKey,
                                    baseUrl = baseUrl,
                                    model = Settings.shared.lyricsAiDetectorModel.ifBlank { Settings.shared.lyricsAiModel },
                                    systemPrompt = Settings.shared.lyricsAiSystemPrompt,
                                )
                        }.getOrElse { error ->
                            if (error is CancellationException) throw error
                            failed("detection", error)
                            return null
                        }
                    sourceLines.map { shouldTranslate && it.isNotBlank() }
                }
            }
        val selected = if (detectPerLine) sourceLines.indices.filter { needed[it] } else sourceLines.indices.toList()
        if (needed.none { it }) {
            updateTranslationCache(videoId, LyricsAiResult(), targetLanguage, translationStyle, sourceHash, configKey, notNeeded = true)
            return null
        }

        onTranslationProgress(selected)
        val aiResult =
            retryLyricsTranslation {
                lyricsTranslationClient
                    .translateLyricsLines(
                        lines = selected.map { sourceLines[it] },
                        provider = provider,
                        targetLanguage = targetLanguage,
                        mode = translationStyle,
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        model = Settings.shared.lyricsAiModel,
                        systemPrompt = Settings.shared.lyricsAiSystemPrompt,
                        deepLApiKey = Settings.shared.lyricsDeepLApiKey,
                        deepLFormality = Settings.shared.lyricsDeepLFormality,
                        wordsPerLine = selected.map { sourceWordsPerLine[it] },
                        preserveTargetLanguage = combineDetection && !skipLanguageDetection,
                    )
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                failed("translation", error)
                return null
            }
        onTranslationProgress(emptyList())
        if (!detectPerLine) {
            updateTranslationCache(videoId, aiResult, targetLanguage, translationStyle, sourceHash, configKey, notNeeded = false)
            return aiResult
        }
        if (!isAlignedCachedResult(aiResult, translationStyle, selected.map { sourceLines[it] }, selected.map { sourceWordsPerLine[it] })) {
            onTranslationFailure(LyricsTranslationFailure.INVALID_RESPONSE)
            return null
        }
        val positions = selected.withIndex().associate { it.value to it.index }
        val aligned =
            LyricsAiResult(
                translatedLines =
                    aiResult.translatedLines?.let { lines ->
                        sourceLines.mapIndexed { index, source -> positions[index]?.let { lines[it] } ?: source }
                    },
                romanizedLines =
                    aiResult.romanizedLines?.let { lines ->
                        sourceLines.mapIndexed { index, source -> positions[index]?.let { lines[it] } ?: source }
                    },
                romanizedWordsPerLine =
                    aiResult.romanizedWordsPerLine?.let { words ->
                        sourceLines.indices.map { index -> positions[index]?.let { words.getOrNull(it) } }
                    },
            )
        updateTranslationCache(videoId, aligned, targetLanguage, translationStyle, sourceHash, configKey, notNeeded = false)
        return aligned
    }

    private suspend fun updateTranslationCache(
        videoId: String,
        result: LyricsAiResult,
        targetLanguage: String,
        translationStyle: String,
        sourceHash: String,
        configKey: String,
        notNeeded: Boolean,
    ) {
        val current = databaseDao?.lyrics(videoId)?.firstOrNull() ?: return
        if (current.userLyrics != null) return
        val serialized =
            if (notNeeded) {
                ""
            } else {
                SYNCED_TRANSLATION_PREFIX +
                    buildJsonObject {
                        put("lines", JsonArray((result.translatedLines ?: emptyList()).map { JsonPrimitive(it) }))
                        if (!result.romanizedLines.isNullOrEmpty()) {
                            put("romanizedLines", JsonArray(result.romanizedLines.map { JsonPrimitive(it) }))
                        }
                        if (!result.romanizedWordsPerLine.isNullOrEmpty()) {
                            put(
                                "romanizedWordsPerLine",
                                JsonArray(
                                    result.romanizedWordsPerLine.map { words ->
                                        words?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull
                                    },
                                ),
                            )
                        }
                    }.toString()
            }
        databaseDao.cacheLyricsIfUnchanged(
            current.copy(
                translatedLyrics = serialized,
                translationLanguage = targetLanguage,
                translationMode = translationStyle,
                translationSourceHash = sourceHash,
                translationConfigKey = configKey,
                translationNotNeeded = notNeeded,
            ),
            expected = current,
        )
    }

    private fun legacyTranslationConfigKeys(configKey: String): List<String> =
        LEGACY_AI_TRANSLATION_CACHE_VERSIONS.map { version ->
            configKey.replaceFirst("$AI_TRANSLATION_CACHE_VERSION|", "$version|")
        }

    internal fun buildTranslationConfigKey(): String =
        listOf(
            AI_TRANSLATION_CACHE_VERSION,
            Settings.shared.lyricsAiProvider,
            Settings.shared.lyricsAiCredentials(Settings.shared.lyricsAiProvider).second,
            Settings.shared.lyricsAiModel,
            Settings.shared.lyricsAiDetectorModel.ifBlank { Settings.shared.lyricsAiModel },
            Settings.shared.lyricsAiSystemPrompt
                .hashCode()
                .toString(),
            Settings.shared.lyricsDeepLFormality,
        ).joinToString("|").let { key ->
            if (Settings.shared.lyricsAiProvider.startsWith("DeepL", ignoreCase = true)) {
                val country = Settings.shared.contentCountry.takeUnless { it == GlobalYouTube.SYSTEM } ?: systemCountryCode()
                "$key|$country"
            } else {
                key
            }
        }

    private fun isAlignedCachedResult(
        result: LyricsAiResult,
        translationStyle: String,
        sourceLines: List<String>,
        sourceWordsPerLine: List<List<String>?>?,
    ): Boolean {
        val outputLines = if (translationStyle == "Romanized") result.romanizedLines else result.translatedLines
        if (outputLines == null || outputLines.size != sourceLines.size) return false
        if (sourceLines.indices.any { sourceLines[it].isBlank() != outputLines[it].isBlank() }) return false
        val outputWords = result.romanizedWordsPerLine ?: return true
        if (outputWords.size != sourceLines.size) return false
        return outputWords.indices.all { index ->
            val sourceWords = sourceWordsPerLine?.getOrNull(index)
            sourceWords.isNullOrEmpty() ||
                (
                    outputWords[index]?.size == sourceWords.size &&
                        sourceWords.indices.all { sourceWords[it].isBlank() == outputWords[index]?.get(it).isNullOrBlank() }
                )
        }
    }

    private fun decodeCachedTranslation(value: String): LyricsAiResult? =
        when {
            value.isBlank() -> {
                null
            }

            value.startsWith(SYNCED_TRANSLATION_PREFIX) -> {
                runCatching {
                    val obj = Json.parseToJsonElement(value.removePrefix(SYNCED_TRANSLATION_PREFIX)).jsonObject
                    val lines = obj["lines"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull.orEmpty() }
                    val romanized =
                        obj["romanizedLines"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull.orEmpty() }
                            ?: obj["romanized_lines"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull.orEmpty() }
                    val romanizedWords =
                        obj["romanizedWordsPerLine"]?.jsonArray?.map { line ->
                            if (line is JsonNull) null else line.jsonArray.map { it.jsonPrimitive.contentOrNull.orEmpty() }
                        }
                    LyricsAiResult(
                        translatedLines = lines,
                        romanizedLines = romanized,
                        romanizedWordsPerLine = romanizedWords,
                    )
                }.getOrNull()
            }

            else -> {
                LyricsAiResult(translatedLines = value.split("\n"))
            }
        }

    suspend fun getUserLyrics(videoId: String): String? = databaseDao?.cachedLyrics(videoId)?.userLyrics

    suspend fun getUserLyricsDraft(videoId: String): String? = databaseDao?.cachedLyrics(videoId)?.userLyricsDraft

    suspend fun saveCustomLyrics(
        videoId: String,
        lyricsText: String,
        draft: String? = null,
    ) {
        checkNotNull(databaseDao).saveUserLyrics(videoId, lyricsText, draft)
    }

    suspend fun revertUserLyrics(videoId: String) {
        checkNotNull(databaseDao).saveUserLyrics(videoId, null, null)
    }

    private companion object {
        const val AI_TRANSLATION_CACHE_VERSION = "v5"
        val LEGACY_AI_TRANSLATION_CACHE_VERSIONS = listOf("v2", "v3", "v4")
        const val SYNCED_TRANSLATION_PREFIX = "METROLIST_TRANSLATION_V2:"
        const val PROVIDER_TIMEOUT_MS = 10_000L
        const val PROVIDER_RESULTS_CONCURRENCY = 12
        const val NEGATIVE_CACHE_TTL_MS = 7 * 24 * 60 * 60_000L
    }
}

internal data class CachedLyricsTranslation(
    val result: LyricsAiResult?,
)
