/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.api

import com.metrolist.music.constants.OpenRouterDefaultBaseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val translationJson = Json { ignoreUnknownKeys = true }

object OpenRouterService {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun translate(
        text: String,
        targetLanguage: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        mode: String,
        maxRetries: Int = 3,
        customSystemPrompt: String = "",
    ): Result<List<String>> =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext Result.failure(Exception("Input text is empty"))

            repeat(maxRetries) { attempt ->
                try {
                    val body =
                        buildTranslationRequest(
                            text = text,
                            targetLanguage = targetLanguage,
                            model = model,
                            mode = mode,
                            customSystemPrompt = customSystemPrompt,
                            baseUrl = baseUrl.ifBlank { OpenRouterDefaultBaseUrl },
                        )
                    val request =
                        Request
                            .Builder()
                            .url(baseUrl.ifBlank { OpenRouterDefaultBaseUrl })
                            .apply {
                                if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer ${apiKey.trim()}")
                            }.addHeader("Content-Type", "application/json")
                            .addHeader("HTTP-Referer", "https://github.com/MetrolistGroup/Metrolist")
                            .addHeader("X-Title", "Metrolist")
                            .post(body.toString().toRequestBody(jsonMediaType))
                            .build()

                    client.newCall(request).execute().use { response ->
                        val responseBody = response.body.string()
                        if (!response.isSuccessful) {
                            val error = apiErrorMessage(responseBody, response.code, response.message)
                            if (response.code >= 500) throw Exception(error)
                            return@withContext Result.failure(Exception("Translation failed: $error"))
                        }

                        val content =
                            translationJson
                                .parseToJsonElement(responseBody.orEmpty())
                                .jsonObject["choices"]
                                ?.jsonArray
                                ?.getOrNull(0)
                                ?.jsonObject
                                ?.get("message")
                                ?.jsonObject
                                ?.get("content")
                                ?.jsonPrimitive
                                ?.contentOrNull
                                .orEmpty()
                        return@withContext parseTranslationContent(content, text.lines().size)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (attempt == maxRetries - 1) return@withContext Result.failure(error)
                    delay(1000L * (attempt + 1))
                }
            }

            Result.failure(Exception("Max retries exceeded"))
        }
}

internal fun buildTranslationRequest(
    text: String,
    targetLanguage: String,
    model: String,
    mode: String,
    customSystemPrompt: String,
    baseUrl: String = OpenRouterDefaultBaseUrl,
    stream: Boolean = false,
): JsonObject {
    val lineCount = text.lines().size
    val systemPrompt =
        customSystemPrompt.takeIf(String::isNotBlank)?.replace("{lineCount}", lineCount.toString())
            ?: """You are a precise lyrics translation assistant. Your output must ALWAYS be a valid JSON object of the form {"lines": ["line1", "line2", "line3"].

CRITICAL RULES:
1. Output ONLY the JSON object: {"lines": ["line1", "line2", "line3"]}
2. NO explanations, NO questions, NO additional text
3. Each input line maps to exactly one entry in the "lines" array
4. Preserve empty lines as empty strings ""
5. The "lines" array must contain EXACTLY $lineCount items
6. If uncertain, provide best approximation but maintain line count"""
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

Output MUST be a JSON object {"lines": [...]} with EXACTLY $lineCount strings using ONLY simple ASCII characters."""
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

Output MUST be a JSON object {"lines": [...]} with EXACTLY $lineCount strings in $targetLanguage script."""
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

Output MUST be a JSON object {"lines": [...]} with EXACTLY $lineCount strings."""
            }
        }

    return buildJsonObject {
        put(
            "messages",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    },
                )
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", userPrompt)
                    },
                )
            },
        )
        if (model.isNotBlank()) put("model", model)
        put("temperature", 0.3)
        put("max_tokens", lineCount * 100)
        put(
            "response_format",
            buildJsonObject {
                put("type", "json_schema")
                put(
                    "json_schema",
                    buildJsonObject {
                        put("name", "translated_lyrics")
                        put("strict", true)
                        put(
                            "schema",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put(
                                            "lines",
                                            buildJsonObject {
                                                put("type", "array")
                                                put(
                                                    "description",
                                                    "Translated lines, one per input line, empty lines preserved as empty strings",
                                                )
                                                put(
                                                    "items",
                                                    buildJsonObject {
                                                        put("type", "string")
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                                put(
                                    "required",
                                    buildJsonArray {
                                        add("lines")
                                    },
                                )
                                put("additionalProperties", false)
                            },
                        )
                    },
                )
            },
        )
        // OpenRouter-only routing preference; fail instead of silently degrading to
        // unvalidated JSON on endpoints without structured-output support
        if (baseUrl.contains("openrouter.ai")) {
            put(
                "provider",
                buildJsonObject {
                    put("require_parameters", true)
                },
            )
        }
        if (stream) put("stream", true)
    }
}

internal fun parseTranslationContent(
    content: String,
    expectedLineCount: Int,
): Result<List<String>> =
    runCatching {
        val cleaned = content.replace("```json", "").replace("```", "").trim()
        val bracketed =
            cleaned
                .substringAfter('[', "")
                .substringBeforeLast(']', "")
                .takeIf(String::isNotEmpty)
                ?.let { "[$it]" }
        val translatedLines =
            sequenceOf(content.trim(), cleaned, bracketed)
                .filterNotNull()
                .mapNotNull { candidate ->
                    runCatching { extractLines(translationJson.parseToJsonElement(candidate)) }.getOrNull()
                }.firstOrNull()
                ?: cleaned
                    .lines()
                    .filter(String::isNotBlank)
                    .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
                    .takeIf(List<String>::isNotEmpty)
                ?: error("Failed to parse translation")

        translatedLines.take(expectedLineCount) + List((expectedLineCount - translatedLines.size).coerceAtLeast(0)) { "" }
    }

private fun extractLines(element: JsonElement): List<String>? =
    when (element) {
        // structured-output schema shape
        is JsonObject -> (element["lines"] as? JsonArray)?.map { it.jsonPrimitive.content }
        // legacy lenient path: bare arrays from models/providers that ignore the schema
        is JsonArray -> element.map { it.jsonPrimitive.content }
        else -> null
    }

internal fun apiErrorMessage(
    body: String?,
    code: Int,
    message: String,
): String =
    runCatching {
        translationJson
            .parseToJsonElement(body.orEmpty())
            .jsonObject["error"]
            ?.jsonObject
            ?.get("message")
            ?.jsonPrimitive
            ?.contentOrNull
    }.getOrNull()
        .takeUnless { it.isNullOrBlank() }
        ?: "HTTP $code: $message"
