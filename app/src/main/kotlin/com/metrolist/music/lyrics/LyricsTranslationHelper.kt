/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.api.DeepLService
import com.metrolist.music.api.OpenRouterService
import com.metrolist.music.api.OpenRouterStreamingService
import com.metrolist.music.constants.LanguageCodeToName
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.LyricsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale

object LyricsTranslationHelper {
    private val _status = MutableStateFlow<TranslationStatus>(TranslationStatus.Idle)
    val status: StateFlow<TranslationStatus> = _status.asStateFlow()

    private val _manualTrigger = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val manualTrigger: SharedFlow<Unit> = _manualTrigger.asSharedFlow()
    
    private var translationJob: Job? = null
    private var isCompositionActive = true
    
    // Cache for translations: key = hash of (lyrics content + mode + language), value = list of translations
    private val translationCache = mutableMapOf<String, List<String>>()
    
    private fun getCacheKey(lyricsText: String, mode: String, language: String): String {
        return "${lyricsText.hashCode()}_${mode}_$language"
    }
    
    /**
     * Try to parse partial JSON array from streaming content
     * Returns whatever complete lines we can extract so far
     */
    private fun tryParsePartialTranslation(content: String, expectedCount: Int): List<String> {
        // Look for opening bracket
        val startIdx = content.indexOf('[')
        if (startIdx == -1) return emptyList()
        
        // Try to find complete string entries in the array
        val result = mutableListOf<String>()
        var pos = startIdx + 1
        var inString = false
        var escaping = false
        val currentString = StringBuilder()
        
        while (pos < content.length && result.size < expectedCount) {
            val char = content[pos]
            
            when {
                escaping -> {
                    currentString.append(char)
                    escaping = false
                }
                char == '\\' && inString -> {
                    currentString.append(char)
                    escaping = true
                }
                char == '"' -> {
                    if (inString) {
                        // End of string - we have a complete entry
                        result.add(currentString.toString())
                        currentString.clear()
                        inString = false
                    } else {
                        // Start of string
                        inString = true
                    }
                }
                inString -> {
                    currentString.append(char)
                }
                char == ']' -> {
                    // End of array
                    break
                }
            }
            pos++
        }
        
        return result
    }
    
    fun getCachedTranslations(lyrics: List<LyricsEntry>, mode: String, language: String): List<String>? {
        val lyricsText = lyrics.filter { it.text.isNotBlank() }.joinToString("\n") { it.text }
        val key = getCacheKey(lyricsText, mode, language)
        return translationCache[key]
    }
    
    fun applyCachedTranslations(lyrics: List<LyricsEntry>, mode: String, language: String): Boolean {
        val cached = getCachedTranslations(lyrics, mode, language) ?: return false
        val nonEmptyEntries = lyrics.mapIndexedNotNull { index, entry ->
            if (entry.text.isNotBlank()) index to entry else null
        }
        
        if (cached.size >= nonEmptyEntries.size) {
            nonEmptyEntries.forEachIndexed { idx, (originalIndex, _) ->
                lyrics[originalIndex].translatedTextFlow.value = cached[idx]
            }
            return true
        }
        return false
    }

    fun triggerManualTranslation() {
        _manualTrigger.tryEmit(Unit)
    }
    
    fun resetStatus() {
        _status.value = TranslationStatus.Idle
    }
    
    fun clearCache() {
        translationCache.clear()
    }
    
    fun setCompositionActive(active: Boolean) {
        isCompositionActive = active
    }
    
    fun cancelTranslation() {
        isCompositionActive = false
        translationJob?.cancel()
        translationJob = null
    }
    
    /**
     * Load translations from database into lyrics entries
     */
    fun loadTranslationsFromDatabase(
        lyrics: List<LyricsEntry>,
        lyricsEntity: LyricsEntity?,
        targetLanguage: String,
        mode: String
    ) {
        if (lyricsEntity?.translatedLyrics.isNullOrBlank()) return
        if (lyricsEntity.translationLanguage != targetLanguage) return
        if (lyricsEntity.translationMode != mode) return
        
        val translatedLines = lyricsEntity.translatedLyrics.lines()
        val nonEmptyEntries = lyrics.mapIndexedNotNull { index, entry ->
            if (entry.text.isNotBlank()) index to entry else null
        }
        
        nonEmptyEntries.forEachIndexed { idx, (originalIndex, _) ->
            if (idx < translatedLines.size) {
                lyrics[originalIndex].translatedTextFlow.value = translatedLines[idx]
            }
        }
    }

    fun translateLyrics(
        lyrics: List<LyricsEntry>,
        targetLanguage: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        mode: String,
        scope: CoroutineScope,
        context: Context,
        provider: String = "OpenRouter",
        deeplApiKey: String = "",
        deeplFormality: String = "default",
        useStreaming: Boolean = true,
        songId: String = "",
        database: MusicDatabase? = null
    ) {
        translationJob?.cancel()
        _status.value = TranslationStatus.Translating
        
        // Clear existing translations to indicate re-translation
        lyrics.forEach { it.translatedTextFlow.value = null }
        
        translationJob = scope.launch(Dispatchers.IO) {
            try {
                // Validate inputs
                val effectiveApiKey = if (provider == "DeepL") deeplApiKey else apiKey
                if (effectiveApiKey.isBlank()) {
                    _status.value = TranslationStatus.Error(context.getString(com.metrolist.music.R.string.ai_error_api_key_required))
                    return@launch
                }
                
                if (lyrics.isEmpty()) {
                    _status.value = TranslationStatus.Error(context.getString(com.metrolist.music.R.string.ai_error_no_lyrics))
                    return@launch
                }
                
                // Filter out empty lines and keep track of their indices
                val nonEmptyEntries = lyrics.mapIndexedNotNull { index, entry ->
                    if (entry.text.isNotBlank()) index to entry else null
                }
                
                if (nonEmptyEntries.isEmpty()) {
                    _status.value = TranslationStatus.Error(context.getString(com.metrolist.music.R.string.ai_error_lyrics_empty))
                    return@launch
                }
                
                // Create text from non-empty lines only
                val fullText = nonEmptyEntries.joinToString("\n") { it.second.text }

                // Check cache first
                val cacheKey = getCacheKey(fullText, mode, targetLanguage)
                val cachedTranslations = translationCache[cacheKey]
                if (cachedTranslations != null && cachedTranslations.size >= nonEmptyEntries.size) {
                    // Use cached translations
                    nonEmptyEntries.forEachIndexed { idx, (originalIndex, _) ->
                        if (idx < cachedTranslations.size) {
                            lyrics[originalIndex].translatedTextFlow.value = cachedTranslations[idx]
                        }
                    }
                    _status.value = TranslationStatus.Success
                    delay(3000)
                    if (_status.value is TranslationStatus.Success && isCompositionActive) {
                        _status.value = TranslationStatus.Idle
                    }
                    return@launch
                }

                // Validate language for all modes
                if (targetLanguage.isBlank()) {
                    _status.value = TranslationStatus.Error(context.getString(com.metrolist.music.R.string.ai_error_language_required))
                    return@launch
                }

                // Convert language code to full language name for better AI understanding
                val fullLanguageName = LanguageCodeToName[targetLanguage] 
                    ?: try {
                        Locale.forLanguageTag(targetLanguage).displayLanguage.takeIf { it.isNotBlank() && it != targetLanguage }
                    } catch (e: Exception) { null }
                    ?: targetLanguage

                val result = if (provider == "DeepL") {
                    Timber.d("Using DeepL for translation")
                    // DeepL only supports translation mode
                    DeepLService.translate(
                        text = fullText,
                        targetLanguage = targetLanguage,
                        apiKey = deeplApiKey,
                        formality = deeplFormality
                    )
                } else if (useStreaming && provider != "Custom") {
                    Timber.d("Using streaming for translation with provider: $provider")
                    // Use streaming for supported providers
                    var translatedLines: List<String>? = null
                    var hasError = false
                    var errorMessage = ""
                    val contentAccumulator = StringBuilder()
                    
                    OpenRouterStreamingService.streamTranslation(
                        text = fullText,
                        targetLanguage = fullLanguageName,
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        model = model,
                        mode = mode
                    ).collect { chunk ->
                        Timber.v("Received streaming chunk: $chunk")
                        when (chunk) {
                            is OpenRouterStreamingService.StreamChunk.Content -> {
                                // Accumulate content for progressive parsing
                                contentAccumulator.append(chunk.text)
                                
                                // Try to parse partial content and update UI progressively
                                val partialContent = contentAccumulator.toString()
                                val partialResult = tryParsePartialTranslation(partialContent, nonEmptyEntries.size)
                                if (partialResult.isNotEmpty()) {
                                    // Update lyrics with partial translations as they become available
                                    partialResult.forEachIndexed { idx, translation ->
                                        if (idx < nonEmptyEntries.size && translation.isNotBlank()) {
                                            val originalIndex = nonEmptyEntries[idx].first
                                            lyrics[originalIndex].translatedTextFlow.value = translation
                                        }
                                    }
                                    _status.value = TranslationStatus.Translating
                                }
                            }
                            is OpenRouterStreamingService.StreamChunk.Complete -> {
                                Timber.d("Streaming complete with ${chunk.translatedLines.size} lines")
                                translatedLines = chunk.translatedLines
                            }
                            is OpenRouterStreamingService.StreamChunk.Error -> {
                                Timber.e("Streaming error: ${chunk.message}")
                                hasError = true
                                errorMessage = chunk.message
                            }
                        }
                    }
                    
                    Timber.d("Streaming collection complete. hasError=$hasError, translatedLines=${translatedLines?.size}")
                    if (hasError) {
                        Result.failure(Exception(errorMessage))
                    } else if (translatedLines != null) {
                        Result.success(translatedLines)
                    } else {
                        Result.failure(Exception("No translation received"))
                    }
                } else {
                    Timber.d("Using non-streaming for translation")
                    // Use non-streaming for Custom provider or when streaming is disabled
                    OpenRouterService.translate(
                        text = fullText,
                        targetLanguage = fullLanguageName,
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        model = model,
                        mode = mode
                    )
                }
                
                result.onSuccess { translatedLines ->
                    // Check if composition is still active before updating state
                    if (!isCompositionActive) {
                        return@onSuccess
                    }
                    
                    // Cache the translations
                    val cacheKey = getCacheKey(fullText, mode, targetLanguage)
                    translationCache[cacheKey] = translatedLines
                    
                    // Save to database if songId is provided
                    if (songId.isNotBlank() && database != null) {
                        scope.launch(Dispatchers.IO) {
                            try {
                                val currentLyrics = database.lyrics(songId).first()
                                if (currentLyrics != null) {
                                    database.query {
                                        upsert(
                                            currentLyrics.copy(
                                                translatedLyrics = translatedLines.joinToString("\n"),
                                                translationLanguage = targetLanguage,
                                                translationMode = mode
                                            )
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                timber.log.Timber.e(e, "Failed to save translated lyrics to database")
                            }
                        }
                    }
                    
                    // Map translations back to original non-empty entries only
                    val expectedCount = nonEmptyEntries.size
                    
                    when {
                        translatedLines.size >= expectedCount -> {
                            // Perfect match or more - map to non-empty entries
                            nonEmptyEntries.forEachIndexed { idx, (originalIndex, _) ->
                                lyrics[originalIndex].translatedTextFlow.value = translatedLines[idx]
                            }
                            _status.value = TranslationStatus.Success
                        }
                        translatedLines.size < expectedCount -> {
                            // Fewer translations than expected - map what we have
                            translatedLines.forEachIndexed { idx, translation ->
                                if (idx < nonEmptyEntries.size) {
                                    val originalIndex = nonEmptyEntries[idx].first
                                    lyrics[originalIndex].translatedTextFlow.value = translation
                                }
                            }
                            _status.value = TranslationStatus.Success
                        }
                        else -> {
                            _status.value = TranslationStatus.Error(context.getString(com.metrolist.music.R.string.ai_error_unexpected))
                        }
                    }
                    
                    // Auto-hide success message after 3 seconds
                    delay(3000)
                    if (_status.value is TranslationStatus.Success && isCompositionActive) {
                        _status.value = TranslationStatus.Idle
                    }
                }.onFailure { error ->
                    if (!isCompositionActive) {
                        return@onFailure
                    }
                    
                    val errorMessage = error.message ?: context.getString(com.metrolist.music.R.string.ai_error_unknown)
                    
                    // Show error in UI
                    _status.value = TranslationStatus.Error(errorMessage)
                }
            } catch (e: Exception) {
                // Ignore cancellation exceptions or if composition is no longer active
                if (e !is kotlinx.coroutines.CancellationException && isCompositionActive) {
                    val errorMessage = e.message ?: context.getString(com.metrolist.music.R.string.ai_error_translation_failed)
                    _status.value = TranslationStatus.Error(errorMessage)
                }
            }
        }
    }

    sealed class TranslationStatus {
        data object Idle : TranslationStatus()
        data object Translating : TranslationStatus()
        data object Success : TranslationStatus()
        data class Error(val message: String) : TranslationStatus()
    }
}
