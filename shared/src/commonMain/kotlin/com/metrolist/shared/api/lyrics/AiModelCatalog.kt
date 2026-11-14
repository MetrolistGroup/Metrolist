/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import com.metrolist.shared.deviceconnect.DeviceCrypto
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.time.Clock

data class AiModelInfo(
    val id: String,
    val name: String = id,
    val inputUsdPerMTok: Double? = null,
    val outputUsdPerMTok: Double? = null,
    val contextLength: Int? = null,
    val tokensPerSecond: Double? = null,
) {
    val free: Boolean get() = inputUsdPerMTok == 0.0 && outputUsdPerMTok == 0.0
}

class AiModelCatalog(
    private val httpClient: HttpClient,
) {
    private val cache = mutableMapOf<String, List<AiModelInfo>>()
    private val availableIds = mutableMapOf<String, Set<String>>()
    private val throughputCache = mutableMapOf<String, Double?>()
    private val lock = Mutex()
    private var modelsDev: JsonObject? = null

    suspend fun models(
        preset: AiProviderPreset,
        apiKey: String,
        baseUrl: String,
        refresh: Boolean = false,
    ): Result<List<AiModelInfo>> {
        val cacheKey = cacheKey(preset, apiKey, baseUrl)
        if (!refresh) lock.withLock { cache[cacheKey] }?.let { return Result.success(it) }
        return try {
            val fromApi = if (preset.modelsEndpoint.isNotBlank()) fetchProviderModels(preset, apiKey, baseUrl, cacheKey) else null
            if (fromApi?.isFailure == true) lock.withLock { availableIds.remove(cacheKey) }
            val dev = preset.modelsDevId?.let { devProvider(it) }
            var result = fromApi?.getOrNull()?.let { priceFromModelsDev(it, dev, preset) }
            if (result.isNullOrEmpty() && (preset.id != "OpenRouter" || fromApi?.isSuccess != true)) {
                result = dev?.let(::parseModelsDevModels)?.let { priceFromModelsDev(it, dev, preset) }
            }
            if (result.isNullOrEmpty() && preset.id == "OpenRouter" && fromApi?.isSuccess != true) {
                result = filterAndSort(AiProviderPresets.openRouterOfflineModels)
            }
            if (result == null) {
                return Result.failure(fromApi?.exceptionOrNull() ?: IllegalStateException("No model list available"))
            }
            lock.withLock { cache[cacheKey] = result }
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun cacheKey(
        preset: AiProviderPreset,
        apiKey: String,
        baseUrl: String,
    ): String {
        val credentialHash = Base64.encode(DeviceCrypto.sha256(apiKey.trim().encodeToByteArray()))
        return "${preset.id}|$baseUrl|$credentialHash"
    }

    suspend fun validatedModel(
        preset: AiProviderPreset,
        apiKey: String,
        baseUrl: String,
        savedModel: String,
    ): String {
        if (!preset.isAi || preset.editableEndpoint || baseUrl != preset.endpoint) return savedModel
        val models = models(preset, apiKey, baseUrl).getOrNull() ?: return savedModel
        // Only a successful provider response can prove a saved model has disappeared.
        val ids = lock.withLock { availableIds[cacheKey(preset, apiKey, baseUrl)] } ?: return savedModel
        if (ids.isEmpty() || savedModel in ids) return savedModel
        val candidates = models.filter { it.id in ids }
        return defaultModel(preset, candidates, freeOnly = savedModel.endsWith(":free")) ?: savedModel
    }

    /** Median output speed over the last 30 minutes for the fastest OpenRouter endpoint; null when unknown. */
    suspend fun throughput(modelId: String): Double? {
        lock.withLock { if (throughputCache.containsKey(modelId)) return throughputCache[modelId] }
        val value =
            try {
                val response =
                    httpClient.get("https://openrouter.ai/api/v1/models/$modelId/endpoints") {
                        timeout {
                            requestTimeoutMillis =
                                15_000
                        }
                    }
                if (response.status.isSuccess()) parseOpenRouterThroughput(response.bodyAsText()) else null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        lock.withLock { throughputCache[modelId] = value }
        return value
    }

    private suspend fun fetchProviderModels(
        preset: AiProviderPreset,
        apiKey: String,
        baseUrl: String,
        cacheKey: String,
    ): Result<List<AiModelInfo>> {
        if (apiKey.isBlank() && !preset.publicModelList) return Result.failure(IllegalStateException("API key required"))
        val root = baseUrl.ifBlank { preset.endpoint }
        val url =
            AiProviderPreset(preset.id, preset.group, preset.protocol, root)
                .modelsEndpoint
                .let {
                    if (preset.protocol ==
                        AiProtocol.ANTHROPIC
                    ) {
                        "$it?limit=1000"
                    } else if (preset.protocol == AiProtocol.GEMINI) {
                        "$it?pageSize=1000"
                    } else {
                        it
                    }
                }
        return try {
            val response =
                httpClient.get(url) {
                    timeout { requestTimeoutMillis = 20_000 }
                    if (preset.id == "OpenRouter" || url.contains("openrouter.ai")) {
                        header("HTTP-Referer", "https://github.com/MetrolistGroup/Metrolist")
                        header("X-Title", "Metrolist")
                    }
                    if (apiKey.isNotBlank()) {
                        when (preset.protocol) {
                            AiProtocol.ANTHROPIC -> {
                                header("x-api-key", apiKey.trim())
                                header("anthropic-version", "2023-06-01")
                            }

                            AiProtocol.GEMINI -> {
                                header("x-goog-api-key", apiKey.trim())
                            }

                            AiProtocol.OPENAI -> {
                                header("Authorization", "Bearer ${apiKey.trim()}")
                            }
                        }
                    }
                }
            val text = response.bodyAsText()
            if (!response.status.isSuccess()) {
                Result.failure(IllegalStateException("Model list failed (${response.status.value})"))
            } else {
                val rootObject = Json.parseToJsonElement(text).jsonObject
                val entries = rootObject[if (preset.protocol == AiProtocol.GEMINI) "models" else "data"].asObjects()
                val idKey = if (preset.protocol == AiProtocol.GEMINI) "name" else "id"
                val ids = entries.mapNotNull { it.string(idKey)?.removePrefix("models/") }.toSet()
                val incomplete =
                    rootObject["has_more"]?.jsonPrimitive?.booleanOrNull == true ||
                        !rootObject.string("nextPageToken").isNullOrBlank()
                lock.withLock {
                    if (incomplete) availableIds.remove(cacheKey) else availableIds[cacheKey] = ids
                }
                Result.success(
                    when (preset.protocol) {
                        AiProtocol.ANTHROPIC -> parseAnthropicModels(text)
                        AiProtocol.GEMINI -> parseGeminiModels(text)
                        AiProtocol.OPENAI -> parseOpenAiModels(text, textOnly = preset.id == "OpenRouter")
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun devProvider(id: String): JsonObject? {
        val root =
            lock.withLock { modelsDev } ?: try {
                val response = httpClient.get("https://models.dev/api.json") { timeout { requestTimeoutMillis = 30_000 } }
                if (!response.status.isSuccess()) return null
                Json.parseToJsonElement(response.bodyAsText()).jsonObject.also { parsed -> lock.withLock { modelsDev = parsed } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            }
        return root[id] as? JsonObject
    }

    private fun priceFromModelsDev(
        models: List<AiModelInfo>,
        dev: JsonObject?,
        preset: AiProviderPreset,
    ): List<AiModelInfo> {
        val entries = dev?.get("models") as? JsonObject
        val sorted =
            models.filter { keep(it, entries, preset) }.map { model ->
                if (model.inputUsdPerMTok != null || entries == null) return@map model
                val cost = (entries[model.id] as? JsonObject)?.get("cost") as? JsonObject ?: return@map model
                model.copy(
                    inputUsdPerMTok = cost.number("input"),
                    outputUsdPerMTok = cost.number("output"),
                )
            }
        return filterAndSort(sorted)
    }

    private fun keep(
        model: AiModelInfo,
        known: JsonObject?,
        preset: AiProviderPreset,
    ): Boolean {
        if (known == null) return true
        val entry = known[model.id] as? JsonObject
        if (entry != null && !modelsDevTextModel(entry)) return false
        return when {
            preset.mixedProtocols -> entry == null || implemented(entry)
            preset.group == AiProviderGroup.DIRECT && preset.protocol == AiProtocol.OPENAI -> entry != null
            else -> true
        }
    }

    internal companion object {
        fun parseOpenAiModels(
            body: String,
            textOnly: Boolean = false,
        ): List<AiModelInfo> =
            Json
                .parseToJsonElement(body)
                .jsonObject["data"]
                .asObjects()
                .filter { openAiTextModel(it, requireMetadata = textOnly) && activeModel(it) }
                .mapNotNull { entry ->
                    val id = entry.string("id") ?: return@mapNotNull null
                    val pricing = entry["pricing"] as? JsonObject
                    AiModelInfo(
                        id = id,
                        name = entry.string("name") ?: id,
                        inputUsdPerMTok = pricing?.number("prompt")?.times(1_000_000),
                        outputUsdPerMTok = pricing?.number("completion")?.times(1_000_000),
                        contextLength = entry["context_length"]?.jsonPrimitive?.intOrNull,
                    )
                }

        fun parseAnthropicModels(body: String): List<AiModelInfo> =
            Json
                .parseToJsonElement(body)
                .jsonObject["data"]
                .asObjects()
                .mapNotNull { entry ->
                    val id = entry.string("id") ?: return@mapNotNull null
                    AiModelInfo(id, entry.string("display_name") ?: id)
                }

        fun parseGeminiModels(body: String): List<AiModelInfo> =
            Json
                .parseToJsonElement(body)
                .jsonObject["models"]
                .asObjects()
                .filter { entry ->
                    (entry["supportedGenerationMethods"] as? JsonArray)?.any { it.jsonPrimitive.contentOrNull == "generateContent" } == true
                }.mapNotNull { entry ->
                    val id = entry.string("name")?.removePrefix("models/") ?: return@mapNotNull null
                    AiModelInfo(id, entry.string("displayName") ?: id, contextLength = entry["inputTokenLimit"]?.jsonPrimitive?.intOrNull)
                }

        fun parseModelsDevModels(provider: JsonObject): List<AiModelInfo> =
            (provider["models"] as? JsonObject)
                .orEmpty()
                .mapNotNull { (id, element) ->
                    val entry = element as? JsonObject ?: return@mapNotNull null
                    if (!implemented(entry) || !modelsDevTextModel(entry)) return@mapNotNull null
                    val cost = entry["cost"] as? JsonObject
                    AiModelInfo(
                        id = id,
                        name = entry.string("name") ?: id,
                        inputUsdPerMTok = cost?.number("input"),
                        outputUsdPerMTok = cost?.number("output"),
                        contextLength = ((entry["limit"] as? JsonObject)?.get("context"))?.jsonPrimitive?.intOrNull,
                    )
                }

        fun parseOpenRouterThroughput(body: String): Double? =
            Json
                .parseToJsonElement(body)
                .jsonObject["data"]
                ?.jsonObject
                ?.get("endpoints")
                .asObjects()
                .mapNotNull { endpoint ->
                    when (val value = endpoint["throughput_last_30m"]) {
                        is JsonObject -> value.number("p50")
                        is JsonPrimitive -> value.doubleOrNull
                        else -> null
                    }
                }.maxOrNull()

        private fun implemented(entry: JsonObject): Boolean {
            val npm = (entry["provider"] as? JsonObject)?.string("npm") ?: return true
            return npm == "@ai-sdk/anthropic" || npm == "@ai-sdk/openai-compatible"
        }

        private fun JsonElement?.asObjects(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

        private fun JsonObject.modalities(key: String): List<String> =
            ((this["architecture"] as? JsonObject)?.get("${key}_modalities") as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()

        fun defaultModel(
            preset: AiProviderPreset,
            models: List<AiModelInfo>,
            freeOnly: Boolean = false,
        ): String? {
            val candidates = filterAndSort(models).filter { !freeOnly || (it.free && it.id.endsWith(":free")) }
            val preferred = if (freeOnly) AiProviderPresets.FREE_LYRICS_AI_MODEL else preset.defaultModel
            return candidates.firstOrNull { it.id == preferred }?.id ?: candidates.firstOrNull()?.id
        }

        fun filterAndSort(models: List<AiModelInfo>): List<AiModelInfo> =
            models
                .filter { model ->
                    val input = model.inputUsdPerMTok
                    val output = model.outputUsdPerMTok
                    !excludedId.containsMatchIn(model.id) &&
                        (input == null || (input.isFinite() && input in 0.0..1.0)) &&
                        (output == null || (output.isFinite() && output in 0.0..5.0))
                }.distinctBy { it.id }
                .sortedWith(
                    compareByDescending<AiModelInfo> { it.free }
                        .thenBy { (it.inputUsdPerMTok ?: Double.MAX_VALUE) + (it.outputUsdPerMTok ?: Double.MAX_VALUE) }
                        .thenBy { it.id },
                )

        private val excludedId =
            Regex(
                "^~|preview|experimental|deprecated|:batch|embed|realtime|audio|tts|whisper|transcri|image|safeguard|prompt-guard|moderation",
                RegexOption.IGNORE_CASE,
            )

        private fun activeModel(entry: JsonObject): Boolean {
            if (entry.string("status") in listOf("deprecated", "preview", "alpha", "beta")) return false
            val expiration = entry.string("expiration_date") ?: return true
            val today =
                Clock.System
                    .now()
                    .toString()
                    .take(10)
            return expiration > today
        }

        private fun modelsDevTextModel(entry: JsonObject): Boolean {
            val modalities = entry["modalities"] as? JsonObject
            val input = modalities?.get("input") as? JsonArray
            val output = modalities?.get("output") as? JsonArray
            return activeModel(entry) &&
                (input == null || input.any { it.jsonPrimitive.contentOrNull == "text" }) &&
                (output == null || output.map { it.jsonPrimitive.contentOrNull } == listOf("text"))
        }

        private fun openAiTextModel(
            entry: JsonObject,
            requireMetadata: Boolean,
        ): Boolean {
            val input = entry.modalities("input")
            val output = entry.modalities("output")
            val endpoints = entry["endpoints"] as? JsonArray
            if (endpoints != null && endpoints.none { it.jsonPrimitive.contentOrNull?.endsWith("/chat/completions") == true }) return false
            if (input.isEmpty() && output.isEmpty()) return !requireMetadata
            return input.contains("text") && output == listOf("text")
        }
    }
}
