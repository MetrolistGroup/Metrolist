/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.api

import com.metrolist.music.constants.OpenRouterDefaultBaseUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit

object OpenRouterStreamingService {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }

    fun streamTranslation(
        text: String,
        targetLanguage: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        mode: String,
        customSystemPrompt: String = "",
    ): Flow<StreamChunk> =
        flow {
            if (text.isBlank()) {
                emit(StreamChunk.Error("Input text is empty"))
                return@flow
            }

            try {
                val body =
                    buildTranslationRequest(
                        text = text,
                        targetLanguage = targetLanguage,
                        model = model,
                        mode = mode,
                        customSystemPrompt = customSystemPrompt,
                        baseUrl = baseUrl.ifBlank { OpenRouterDefaultBaseUrl },
                        stream = true,
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
                    if (!response.isSuccessful) {
                        emit(
                            StreamChunk.Error(
                                "Translation failed: ${apiErrorMessage(response.body.string(), response.code, response.message)}",
                            ),
                        )
                        return@flow
                    }

                    val content = StringBuilder()
                    response.body.byteStream().bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (!line.startsWith("data: ")) continue
                            val data = line.removePrefix("data: ")
                            if (data == "[DONE]") break

                            runCatching {
                                json
                                    .parseToJsonElement(data)
                                    .jsonObject["choices"]
                                    ?.jsonArray
                                    ?.getOrNull(0)
                                    ?.jsonObject
                                    ?.get("delta")
                                    ?.jsonObject
                                    ?.get("content")
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                            }.getOrNull()?.let { chunk ->
                                content.append(chunk)
                                emit(StreamChunk.Content(chunk))
                            }
                        }
                    }

                    parseTranslationContent(content.toString(), text.lines().size)
                        .onSuccess { emit(StreamChunk.Complete(it)) }
                        .onFailure { emit(StreamChunk.Error(it.message ?: "Parsing failed")) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.e(error, "Streaming translation failed")
                emit(StreamChunk.Error(error.message ?: "Unknown error"))
            }
        }.flowOn(Dispatchers.IO)

    sealed interface StreamChunk {
        data class Content(
            val text: String,
        ) : StreamChunk

        data class Complete(
            val translatedLines: List<String>,
        ) : StreamChunk

        data class Error(
            val message: String,
        ) : StreamChunk
    }
}
