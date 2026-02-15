/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import timber.log.Timber

object OpenRouterStreamingService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Stream translation from OpenRouter with real-time updates
     */
    fun streamTranslation(
        text: String,
        targetLanguage: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        mode: String
    ): Flow<StreamChunk> = flow {
        if (text.isBlank()) {
            emit(StreamChunk.Error("Input text is empty"))
            return@flow
        }

        val lines = text.lines()
        val lineCount = lines.size
        
        Timber.d("Starting streaming translation for $lineCount lines")

        try {
            val systemPrompt = """You are a precise lyrics translation assistant. Your output must ALWAYS be a valid JSON array of strings.

CRITICAL RULES:
1. Output ONLY a JSON array: ["line1", "line2", "line3"]
2. NO explanations, NO questions, NO additional text
3. Each input line maps to exactly one output line
4. Preserve empty lines as empty strings ""
5. Return EXACTLY $lineCount items in the array
6. If uncertain, provide best approximation but maintain line count"""

            val userPrompt = when (mode) {
                "Transcribed" -> """Transcribe/transliterate the following $lineCount lines phonetically into $targetLanguage script.

CRITICAL REQUIREMENTS:
- Convert the SOUND/PRONUNCIATION of the original text into $targetLanguage script
- DO NOT translate the meaning - only represent how the original words SOUND
- Use the native script of $targetLanguage
- Preserve the original pronunciation as closely as possible
- Keep punctuation and formatting

Input ($lineCount lines):
$text

Output MUST be a JSON array with EXACTLY $lineCount strings."""

                else -> """Translate the following $lineCount lines to $targetLanguage.

IMPORTANT:
- Provide natural, accurate translation
- Maintain poetic flow and meaning
- Keep punctuation appropriate for target language
- Preserve line-by-line structure exactly

Input ($lineCount lines):
$text

Output MUST be a JSON array with EXACTLY $lineCount strings."""
            }

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            }

            val jsonBody = JSONObject().apply {
                if (model.isNotBlank()) {
                    put("model", model)
                }
                put("messages", messages)
                put("stream", true)
                put("temperature", 0.3)
                put("max_tokens", lineCount * 100)
            }

            val request = Request.Builder()
                .url(baseUrl.ifBlank { "https://openrouter.ai/api/v1/chat/completions" })
                .apply {
                    if (apiKey.isNotBlank()) {
                        addHeader("Authorization", "Bearer ${apiKey.trim()}")
                    }
                }
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "https://github.com/CryptoLiveApp/Metrolist")
                .addHeader("X-Title", "Metrolist")
                .post(jsonBody.toString().toRequestBody(JSON))
                .build()

            client.newCall(request).execute().use { response ->
                Timber.d("Got streaming response: ${response.code}")
                
                if (!response.isSuccessful) {
                    val errorMsg = try {
                        JSONObject(response.body?.string() ?: "")
                            .optJSONObject("error")?.optString("message")
                            ?: "HTTP ${response.code}: ${response.message}"
                    } catch (e: Exception) {
                        "HTTP ${response.code}: ${response.message}"
                    }
                    emit(StreamChunk.Error("Translation failed: $errorMsg"))
                    return@flow
                }

                val reader = BufferedReader(InputStreamReader(response.body?.byteStream()))
                var line: String?
                val contentBuilder = StringBuilder()
                var chunkCount = 0

                while (reader.readLine().also { line = it } != null) {
                    line?.let { currentLine ->
                        if (currentLine.startsWith("data: ")) {
                            val data = currentLine.substring(6)
                            if (data == "[DONE]") {
                                Timber.d("Streaming complete, received $chunkCount chunks")
                                // Processing complete, parse the full content
                                val fullContent = contentBuilder.toString()
                                Timber.d("Full content length: ${fullContent.length}")
                                val result = parseTranslationContent(fullContent, lineCount)
                                result.onSuccess { translatedLines ->
                                    Timber.d("Successfully parsed ${translatedLines.size} lines")
                                    emit(StreamChunk.Complete(translatedLines))
                                }.onFailure { error ->
                                    Timber.e(error, "Failed to parse translation")
                                    emit(StreamChunk.Error(error.message ?: "Parsing failed"))
                                }
                                return@flow
                            }

                            try {
                                val jsonObject = json.parseToJsonElement(data).jsonObject
                                val choices = jsonObject["choices"]?.jsonArray
                                val delta = choices?.get(0)?.jsonObject?.get("delta")?.jsonObject
                                val content = delta?.get("content")?.jsonPrimitive?.content

                                content?.let { chunk ->
                                    contentBuilder.append(chunk)
                                    chunkCount++
                                    emit(StreamChunk.Content(chunk))
                                }
                            } catch (e: Exception) {
                                // Ignore malformed JSON chunks
                                Timber.v("Ignored malformed chunk: ${e.message}")
                            }
                        }
                    }
                }
                
                // If we got here without seeing [DONE], try to parse what we have
                if (contentBuilder.isNotEmpty()) {
                    Timber.w("Stream ended without [DONE] marker, attempting to parse content")
                    val fullContent = contentBuilder.toString()
                    val result = parseTranslationContent(fullContent, lineCount)
                    result.onSuccess { translatedLines ->
                        emit(StreamChunk.Complete(translatedLines))
                    }.onFailure { error ->
                        emit(StreamChunk.Error(error.message ?: "Parsing failed"))
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Streaming error")
            emit(StreamChunk.Error(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    private fun parseTranslationContent(content: String, expectedLineCount: Int): Result<List<String>> {
        var translatedLines: List<String>? = null

        // Strategy 1: Try direct JSON parsing
        try {
            val jsonArray = JSONArray(content.trim())
            translatedLines = (0 until jsonArray.length()).map { jsonArray.optString(it) }
        } catch (e: Exception) {
            // Strategy 2: Extract JSON from markdown code blocks
            var cleanedContent = content.replace("```json", "").replace("```", "").trim()

            try {
                val jsonArray = JSONArray(cleanedContent)
                translatedLines = (0 until jsonArray.length()).map { jsonArray.optString(it) }
            } catch (e2: Exception) {
                // Strategy 3: Find first [ and last ]
                val startIdx = cleanedContent.indexOf('[')
                val endIdx = cleanedContent.lastIndexOf(']')

                if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                    val jsonString = cleanedContent.substring(startIdx, endIdx + 1)
                    try {
                        val jsonArray = JSONArray(jsonString)
                        translatedLines = (0 until jsonArray.length()).map { jsonArray.optString(it) }
                    } catch (e3: Exception) {
                        // Strategy 4: Manual line-by-line parsing as last resort
                        translatedLines = cleanedContent.lines()
                            .filter { it.trim().isNotEmpty() }
                            .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
                    }
                }
            }
        }

        if (translatedLines == null) {
            return Result.failure(Exception("Failed to parse translation"))
        }

        // Adjust line count
        return when {
            translatedLines.size == expectedLineCount -> Result.success(translatedLines)
            translatedLines.size > expectedLineCount -> Result.success(translatedLines.take(expectedLineCount))
            else -> {
                val paddedLines = translatedLines.toMutableList()
                while (paddedLines.size < expectedLineCount) {
                    paddedLines.add("")
                }
                Result.success(paddedLines)
            }
        }
    }

    sealed class StreamChunk {
        data class Content(val text: String) : StreamChunk()
        data class Complete(val translatedLines: List<String>) : StreamChunk()
        data class Error(val message: String) : StreamChunk()
    }
}
