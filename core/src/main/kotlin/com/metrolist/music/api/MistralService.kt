/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object MistralService {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun translate(
        text: String,
        targetLanguage: String,
        apiKey: String,
        model: String,
        mode: String,
        maxRetries: Int = 3,
        sourceLanguage: String? = null,
        customSystemPrompt: String = "",
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            var currentAttempt = 0

            // Validate input
            if (text.isBlank()) {
                return@withContext Result.failure(Exception("Input text is empty"))
            }

            val lines = text.lines()
            val lineCount = lines.size

            while (currentAttempt < maxRetries) {
                try {
                    // Use custom system prompt if provided, otherwise use the default
                    val systemPrompt =
                        if (customSystemPrompt.isNotBlank()) {
                            customSystemPrompt.replace("{lineCount}", lineCount.toString())
                        } else {
                            """You are a precise lyrics translation assistant. Your output must ALWAYS be a valid JSON array of strings.

CRITICAL RULES:
1. Output ONLY a JSON array: ["line1", "line2", "line3"]
2. NO explanations, NO questions, NO additional text
3. Each input line maps to exactly one output line
4. Preserve empty lines as empty strings ""
5. Return EXACTLY $lineCount items in the array
6. If uncertain, provide best approximation but maintain line count"""
                        }

                    val userPrompt =
                        when (mode) {
                            "Romanized" -> {
                                """Romanize/transliterate the following $lineCount lines into simple Latin script using ONLY basic English letters (a-z, A-Z).

CRITICAL REQUIREMENTS:
- Use ONLY simple ASCII characters (a-z, A-Z, 0-9, basic punctuation)
- NO special characters like ā, ī, ū, ñ, ç, etc.
- NO diacritics or accent marks
- If text is already in Latin script, return it UNCHANGED
- For non-Latin scripts (Hindi, Chinese, Japanese, Korean, Cyrillic, etc.), provide simple romanization
- DO NOT translate meaning, only convert script to simple English letters
- Keep all punctuation and formatting
- Preserve line-by-line structure exactly

Examples of correct simple romanization:
- Sanskrit/Hindi "आ" → "aa" (not "ā")
- Japanese "東京" → "toukyou" or "tokyo" (not "tōkyō")
- Korean "서울" → "seoul" (not "sŏul")

Input ($lineCount lines):
$text

Output MUST be a JSON array with EXACTLY $lineCount strings using ONLY simple ASCII characters."""
                            }

                            "Transcribed" -> {
                                """Transcribe/transliterate the following $lineCount lines phonetically into $targetLanguage script.

CRITICAL REQUIREMENTS:
- Convert the SOUND/PRONUNCIATION of the original text into $targetLanguage script
- DO NOT translate the meaning - only represent how the original words SOUND
- Use the native script of $targetLanguage (e.g., Devanagari for Hindi, Hangul for Korean, etc.)
- Preserve the original pronunciation as closely as possible in the target script
- Keep punctuation and formatting
- Preserve line-by-line structure exactly
- If text is already in $targetLanguage script, return it UNCHANGED

Examples:
- Japanese "こんにちは" to Hindi → "कोन्निचिवा" (phonetic, not translation)
- English "Hello" to Hindi → "हेलो" (phonetic)
- Korean "안녕하세요" to Hindi → "अन्न्योंग हासेयो" (phonetic)

Input ($lineCount lines):
$text

Output MUST be a JSON array with EXACTLY $lineCount strings in $targetLanguage script."""
                            }

                            else -> {
                                """Translate the following $lineCount lines to $targetLanguage.

IMPORTANT:
- Provide natural, accurate translation
- Maintain poetic flow and meaning
- Keep punctuation appropriate for target language
- Preserve line-by-line structure exactly
- For song lyrics, prioritize singability

Input ($lineCount lines):
$text

Output MUST be a JSON array with EXACTLY $lineCount strings."""
                            }
                        }

                    val messages =
                        JSONArray().apply {
                            // Include system prompt when a custom one is provided
                            if (customSystemPrompt.isNotBlank()) {
                                put(
                                    JSONObject().apply {
                                        put("role", "system")
                                        put("content", systemPrompt)
                                    },
                                )
                            }
                            put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put("content", userPrompt)
                                },
                            )
                        }

                    val jsonBody =
                        JSONObject().apply {
                            if (model.isNotBlank()) {
                                put("model", model)
                            } else {
                                put("model", "mistral-small-latest")
                            }
                            put("messages", messages)
                            put("temperature", 0.3) // Lower temperature for more consistent output
                            put("max_tokens", lineCount * 100) // Adequate tokens for translation
                        }

                    val request =
                        Request
                            .Builder()
                            .url("https://api.mistral.ai/v1/chat/completions")
                            .apply {
                                if (apiKey.isNotBlank()) {
                                    addHeader("Authorization", "Bearer ${apiKey.trim()}")
                                }
                            }.addHeader("Content-Type", "application/json")
                            .post(jsonBody.toString().toRequestBody(JSON))
                            .build()

                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string()

                    if (!response.isSuccessful) {
                        // Retry on server errors (5xx)
                        if (response.code >= 500) {
                            currentAttempt++
                            kotlinx.coroutines.delay(1000L * currentAttempt)
                            continue
                        }

                        val errorMsg =
                            try {
                                JSONObject(responseBody ?: "").optJSONObject("error")?.optString("message")
                                    ?: "HTTP ${response.code}: ${response.message}"
                            } catch (e: Exception) {
                                "HTTP ${response.code}: ${response.message}"
                            }
                        return@withContext Result.failure(Exception("Translation failed: $errorMsg"))
                    }

                    if (responseBody == null) {
                        currentAttempt++
                        continue
                    }

                    val jsonResponse = JSONObject(responseBody)
                    val choices = jsonResponse.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val message = choices.getJSONObject(0).optJSONObject("message")
                        var content = message?.optString("content")?.trim()

                        if (!content.isNullOrBlank()) {
                            // Enhanced JSON extraction with multiple fallback strategies
                            var translatedLines: List<String>? = null

                            // Strategy 1: Try direct JSON parsing
                            try {
                                val jsonArray = JSONArray(content)
                                translatedLines = (0 until jsonArray.length()).map { jsonArray.optString(it) }
                            } catch (e: Exception) {
                                // Strategy 2: Extract JSON from markdown code blocks
                                content = content.replace("```json", "").replace("```", "").trim()

                                try {
                                    val jsonArray = JSONArray(content)
                                    translatedLines = (0 until jsonArray.length()).map { jsonArray.optString(it) }
                                } catch (e2: Exception) {
                                    // Strategy 3: Find first [ and last ]
                                    val startIdx = content.indexOf('[')
                                    val endIdx = content.lastIndexOf(']')

                                    if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                                        val jsonString = content.substring(startIdx, endIdx + 1)
                                        try {
                                            val jsonArray = JSONArray(jsonString)
                                            translatedLines = (0 until jsonArray.length()).map { jsonArray.optString(it) }
                                        } catch (e3: Exception) {
                                            // Strategy 4: Manual line-by-line parsing as last resort
                                            translatedLines =
                                                content
                                                    .lines()
                                                    .filter { it.trim().isNotEmpty() }
                                                    .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
                                        }
                                    }
                                }
                            }

                            if (translatedLines != null) {
                                // Validate line count matches
                                if (translatedLines.size == lineCount) {
                                    return@withContext Result.success(translatedLines)
                                } else if (translatedLines.size > lineCount) {
                                    // If we got more lines, take first N
                                    return@withContext Result.success(translatedLines.take(lineCount))
                                } else {
                                    // If we got fewer lines, pad with empty strings
                                    val paddedLines = translatedLines.toMutableList()
                                    while (paddedLines.size < lineCount) {
                                        paddedLines.add("")
                                    }
                                    return@withContext Result.success(paddedLines)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (currentAttempt == maxRetries - 1) {
                        return@withContext Result.failure(e)
                    }
                }
                currentAttempt++
                kotlinx.coroutines.delay(1000L * currentAttempt)
            }
            return@withContext Result.failure(Exception("Max retries exceeded"))
        }
}
