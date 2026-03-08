/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import com.metrolist.shared.network.ExpectedHttpFailureStatusCodes
import com.metrolist.shared.network.createHttpClient
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.utils.systemCountryCode
import com.metrolist.shared.youtube.playback.PlaybackEventKind
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.time.Clock

data class LyricsAiResult(
    val translatedLines: List<String>? = null,
    val romanizedLines: List<String>? = null,
    val romanizedWordsPerLine: List<List<String>?>? = null,
)

data class AiLyricsProbeResult(
    val detectionNeedsTranslation: Boolean? = null,
    val translatedSample: String? = null,
)

class LyricsTranslationClient(
    private val httpClient: HttpClient = createHttpClient(),
    private val modelCatalog: AiModelCatalog = AiModelCatalog(httpClient),
) {
    suspend fun verifyOpenRouterTranslation(
        apiKey: String,
        model: String,
        targetLanguage: String,
    ): Result<String> {
        require(model.endsWith(":free") && targetLanguage.isNotBlank())
        val preset = checkNotNull(AiProviderPresets.find("OpenRouter"))
        recheckFreeQuota(apiKey).exceptionOrNull()?.let { return Result.failure(it) }
        var verifiedModel = model
        return translateWithAiProvider(
            lines = listOf("Hello"),
            provider = preset.id,
            targetLanguage = languageName(targetLanguage),
            mode = "Literal",
            apiKey = apiKey,
            baseUrl = preset.endpoint,
            model = model,
            systemPrompt = "",
            maxTokens = 128,
            onModelSelected = { verifiedModel = it },
        ).map { verifiedModel }
    }

    private fun HttpRequestBuilder.aiTimeout() {
        timeout {
            requestTimeoutMillis = AI_REQUEST_TIMEOUT_MS
            socketTimeoutMillis = AI_REQUEST_TIMEOUT_MS
        }
    }

    private val deepLInstanceId =
        Random.nextBytes(16).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }.let {
            "${it.take(8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.drop(20)}"
        }

    suspend fun translateLyricsLines(
        lines: List<String>,
        provider: String,
        targetLanguage: String,
        mode: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String = "",
        deepLApiKey: String = "",
        deepLFormality: String = "default",
        wordsPerLine: List<List<String>?>? = null,
        preserveTargetLanguage: Boolean = false,
    ): Result<LyricsAiResult> {
        val nonNullLines = lines.map { it.trimEnd() }
        if (nonNullLines.isEmpty() || nonNullLines.all { it.isBlank() }) {
            return Result.failure(IllegalArgumentException("No lyrics to translate"))
        }
        return when {
            provider.equals("DeepL", ignoreCase = true) -> {
                translateWithDeepL(nonNullLines, targetLanguage, deepLApiKey, deepLFormality)
                    .map { if (mode == "Romanized") LyricsAiResult(romanizedLines = it) else LyricsAiResult(translatedLines = it) }
            }

            provider.equals("DeepL (keyless)", ignoreCase = true) -> {
                translateWithDeepLKeyless(nonNullLines, targetLanguage)
                    .map { if (mode == "Romanized") LyricsAiResult(romanizedLines = it) else LyricsAiResult(translatedLines = it) }
            }

            provider.equals("Google Translate", ignoreCase = true) -> {
                if (mode == "Romanized") {
                    romanizeWithGoogle(nonNullLines)
                } else {
                    translateWithGoogle(nonNullLines, targetLanguage, mode)
                }
            }

            else -> {
                translateWithAiProvider(
                    lines = nonNullLines,
                    provider = provider,
                    targetLanguage = languageName(targetLanguage),
                    mode = mode,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    model = model,
                    systemPrompt = systemPrompt,
                    wordsPerLine = wordsPerLine,
                    preserveTargetLanguage = preserveTargetLanguage,
                )
            }
        }
    }

    suspend fun shouldTranslateLyrics(
        lines: List<String>,
        provider: String,
        targetLanguage: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String = "",
    ): Result<Boolean> {
        if (provider.equals("DeepL", ignoreCase = true) ||
            provider.equals("DeepL (keyless)", ignoreCase = true) ||
            provider.equals("Google Translate", ignoreCase = true)
        ) {
            return Result.success(true)
        }
        val nonBlank = lines.map { it.trim() }.filter { it.isNotBlank() }
        if (nonBlank.isEmpty()) return Result.success(false)
        return try {
            val target = languageName(targetLanguage)
            val schema = detectionSchema()
            for (batch in detectionBatches(nonBlank)) {
                currentCoroutineContext().ensureActive()
                val prompt =
                    "Inspect every passage in every lyric line. " +
                        foreignPassageInstructions(target) +
                        " Copy one short matching passage verbatim into foreign_text, or return an empty string if none exists.\n\n$batch"
                val content =
                    requestStructuredAi(
                        provider = provider,
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        model = model,
                        systemPrompt =
                            systemPrompt.takeIf { it.isNotBlank() }
                                ?: "You are a fast language detector. Return only the requested JSON object.",
                        userPrompt = prompt,
                        schemaName = "lyrics_language_detection",
                        schema = schema,
                        maxTokens = 256,
                        temperature = 0.0,
                    ).getOrThrow()
                val json =
                    parseJsonObjectPayload(content)
                        ?: return Result.failure(
                            LyricsTranslationException(LyricsTranslationFailure.INVALID_RESPONSE, "AI detector did not return JSON"),
                        )
                if (hasForeignPassage(json["foreign_text"])) return Result.success(true)
            }
            Result.success(false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun shouldTranslateLyricsLines(
        lines: List<String>,
        provider: String,
        targetLanguage: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String = "",
    ): Result<List<Boolean>> {
        if (provider.equals("DeepL", ignoreCase = true) ||
            provider.equals("DeepL (keyless)", ignoreCase = true) ||
            provider.equals("Google Translate", ignoreCase = true)
        ) {
            return Result.success(lines.map(String::isNotBlank))
        }
        return try {
            val decisions = mutableMapOf<String, Boolean>()
            for (batch in lines.filter(String::isNotBlank).distinct().chunked(40)) {
                val input = JsonArray(batch.map(::JsonPrimitive))
                val content =
                    requestStructuredAi(
                        provider = provider,
                        apiKey = apiKey,
                        baseUrl = baseUrl,
                        model = model,
                        systemPrompt = systemPrompt.ifBlank { "You are a fast language detector. Return only the requested JSON object." },
                        userPrompt =
                            "Inspect every passage within each lyric independently. " +
                                foreignPassageInstructions(languageName(targetLanguage)) +
                                " In lines, return one string per input in the same order: copy one short matching passage verbatim, " +
                                "or use an empty string if none exists. Do not let another lyric's language affect the decision.\n\n{\"lines\":$input}",
                        schemaName = "lyrics_line_language_detection",
                        schema = lyricsLinesSchema(),
                        maxTokens = 2048,
                        temperature = 0.0,
                    ).getOrThrow()
                val passages =
                    (parseJsonObjectPayload(content)?.get("lines") as? JsonArray)
                        ?: throw LyricsTranslationException(
                            LyricsTranslationFailure.INVALID_RESPONSE,
                            "AI detector did not return lyric decisions",
                        )
                if (passages.size != batch.size) {
                    throw LyricsTranslationException(
                        LyricsTranslationFailure.INVALID_RESPONSE,
                        "AI detector returned misaligned lyric decisions",
                    )
                }
                batch.forEachIndexed { index, line ->
                    decisions[line] = hasForeignPassage(passages[index])
                }
            }
            Result.success(lines.map { decisions[it] ?: false })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun translateWithAiProvider(
        lines: List<String>,
        provider: String,
        targetLanguage: String,
        mode: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String,
        wordsPerLine: List<List<String>?>? = null,
        maxTokens: Int? = null,
        preserveTargetLanguage: Boolean = false,
        onModelSelected: (String) -> Unit = {},
    ): Result<LyricsAiResult> =
        try {
            if (apiKey.isBlank()) return Result.failure(IllegalArgumentException("AI API key is required"))
            val lineCount = lines.size
            val effectiveSystemPrompt =
                systemPrompt.takeIf { it.isNotBlank() }
                    ?: defaultLyricsSystemPrompt(lineCount)
            val hasWords = mode == "Romanized" && wordsPerLine != null && wordsPerLine.any { !it.isNullOrEmpty() }
            val inputJson =
                if (hasWords) {
                    JsonArray(
                        lines.mapIndexed { i, line ->
                            val words = wordsPerLine.getOrNull(i)
                            buildJsonObject {
                                put("line", JsonPrimitive(line))
                                if (!words.isNullOrEmpty()) {
                                    put("words", JsonArray(words.map { JsonPrimitive(it) }))
                                }
                            }
                        },
                    ).toString()
                } else {
                    JsonArray(lines.map { JsonPrimitive(it) }).toString()
                }
            val languageInstruction =
                if (preserveTargetLanguage) {
                    " Leave text already in $targetLanguage unchanged. Within mixed-language lines, translate only the foreign passages."
                } else {
                    ""
                }
            val userPrompt =
                when {
                    mode == "Transcribed" -> {
                        "Transliterate the `lines` JSON array phonetically into $targetLanguage script. Do not translate meaning. Return exactly $lineCount output lines.$languageInstruction\n\n{\"lines\":$inputJson}"
                    }

                    mode == "Romanized" && hasWords -> {
                        "Romanize each item in the `lines` JSON array into simple ASCII Latin script. Do not translate meaning. " +
                            "For items with a `words` array, preserve full line context for pronunciation, " +
                            "return `romanizedLine` for the full line, and return `romanizedWords` with exact 1-to-1 ASCII Latin " +
                            "romanization for each word in `words`. " +
                            "Return exactly $lineCount output items in `lines`.\n\n{\"lines\":$inputJson}"
                    }

                    mode == "Romanized" -> {
                        "Romanize the `lines` JSON array into simple ASCII Latin script. Do not translate meaning. Return exactly $lineCount output lines.\n\n{\"lines\":$inputJson}"
                    }

                    else -> {
                        "Translate the `lines` JSON array to $targetLanguage for synced song lyrics. Keep line order, repeated hooks, names, punctuation, and empty lines.$languageInstruction Return exactly $lineCount output lines.\n\n{\"lines\":$inputJson}"
                    }
                }
            val schema = if (hasWords) lyricsRomanizedLinesWithWordsSchema() else lyricsLinesSchema()
            val schemaName = if (hasWords) "lyrics_romanization_words" else "lyrics_translation"
            val content =
                requestStructuredAi(
                    provider = provider,
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                    model = model,
                    systemPrompt = effectiveSystemPrompt.replace("{lineCount}", lineCount.toString()),
                    userPrompt = userPrompt,
                    schemaName = schemaName,
                    schema = schema,
                    maxTokens = maxTokens ?: (lineCount * 192 + 2048).coerceAtLeast(768),
                    temperature = 0.1,
                    onModelSelected = onModelSelected,
                ).getOrThrow()
            if (hasWords) {
                val parsedWithWords =
                    parseJsonRomanizedLinesWithWordsPayload(content, lineCount)?.takeIf { parsed ->
                        linesAreAligned(lines, parsed.first) &&
                            parsed.second.indices.all { index ->
                                val sourceWords = wordsPerLine.getOrNull(index)
                                sourceWords.isNullOrEmpty() || wordsAreAligned(sourceWords, parsed.second[index])
                            }
                    }
                if (parsedWithWords != null) {
                    Result.success(LyricsAiResult(romanizedLines = parsedWithWords.first, romanizedWordsPerLine = parsedWithWords.second))
                } else {
                    val fallbackLines = parseJsonLinePayload(content, lineCount)?.takeIf { linesAreAligned(lines, it) }
                    if (fallbackLines != null) {
                        Result.success(LyricsAiResult(romanizedLines = fallbackLines))
                    } else {
                        Result.failure(unalignedLyricsFailure(content, lineCount))
                    }
                }
            } else {
                val parsed = parseJsonLinePayload(content, lineCount)?.takeIf { linesAreAligned(lines, it) }
                if (parsed != null) {
                    val result =
                        if (mode == "Romanized") {
                            LyricsAiResult(romanizedLines = parsed)
                        } else {
                            LyricsAiResult(translatedLines = parsed)
                        }
                    Result.success(result)
                } else {
                    Result.failure(unalignedLyricsFailure(content, lineCount))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private suspend fun requestStructuredAi(
        provider: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        schemaName: String,
        schema: JsonObject,
        maxTokens: Int,
        temperature: Double,
        onModelSelected: (String) -> Unit = {},
    ): Result<String> {
        if (apiKey.isBlank()) return Result.failure(IllegalArgumentException("AI API key is required"))
        return when (AiProviderPresets.protocolFor(provider, baseUrl, model)) {
            AiProtocol.ANTHROPIC -> {
                requestAnthropicCompatible(
                    apiKey,
                    AiProviderPresets.requestUrl(provider, baseUrl, model),
                    model,
                    systemPrompt,
                    userPrompt,
                    schemaName,
                    schema,
                    maxTokens,
                    temperature,
                )
            }

            AiProtocol.GEMINI -> {
                requestGeminiCompatible(
                    apiKey,
                    baseUrl,
                    model,
                    systemPrompt,
                    userPrompt,
                    schema,
                    maxTokens,
                    temperature,
                )
            }

            AiProtocol.OPENAI -> {
                withFreeModelFallback(provider, apiKey, baseUrl, model) { selectedModel ->
                    requestOpenAiCompatible(
                        provider,
                        apiKey,
                        baseUrl,
                        selectedModel,
                        systemPrompt,
                        userPrompt,
                        schemaName,
                        schema,
                        maxTokens,
                        temperature,
                    ).onSuccess { onModelSelected(selectedModel) }
                }
            }
        }
    }

    private class FreeModelSession {
        val lock = Mutex()
        val throttled = mutableMapOf<String, Long>()
        var lastBusyFailure: Throwable? = null
        var dailyFailure: Throwable? = null
        var dailyResetMillis = 0L
    }

    private val freeModelSessions = mutableMapOf<String, FreeModelSession>()
    private val freeModelSessionsLock = Mutex()

    private suspend fun freeModelSession(apiKey: String): FreeModelSession =
        freeModelSessionsLock.withLock { freeModelSessions.getOrPut(apiKey) { FreeModelSession() } }

    private suspend fun recheckFreeQuota(apiKey: String): Result<Unit> =
        runCatchingCancellable {
            val session = freeModelSession(apiKey)
            session.lock.withLock {
                if (session.dailyFailure != null && Clock.System.now().toEpochMilliseconds() < session.dailyResetMillis) {
                    val response =
                        httpClient.get("https://openrouter.ai/api/v1/key") {
                            aiTimeout()
                            header("Authorization", "Bearer ${apiKey.trim()}")
                        }
                    val text = response.bodyAsText()
                    if (!response.status.isSuccess()) throw LyricsTranslationHttpException(response.status.value, text, response.headers)
                    val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                    val data = root?.get("data") as? JsonObject
                    val quota = data?.get("free_model_daily_requests") as? JsonObject
                    val remaining = (quota?.get("remaining") as? JsonPrimitive)?.intOrNull
                    if (remaining != null && remaining > 0) session.dailyFailure = null
                }
            }
        }

    private suspend fun withFreeModelFallback(
        provider: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        request: suspend (String) -> Result<String>,
    ): Result<String> {
        val preset = checkNotNull(AiProviderPresets.find("OpenRouter"))
        if (!provider.equals(preset.id, ignoreCase = true) || baseUrl.ifBlank { preset.endpoint } != preset.endpoint ||
            !model.endsWith(":free")
        ) {
            return request(model)
        }
        val session = freeModelSession(apiKey)
        session.lock.lock()
        try {
            val now = Clock.System.now().toEpochMilliseconds()
            session.dailyFailure?.takeIf { now < session.dailyResetMillis }?.let { return Result.failure(it) }
            session.dailyFailure = null
            val attempted = mutableSetOf<String>()
            var candidates: List<String>? = null
            var selected = model.takeUnless { (session.throttled[it] ?: 0) > now }
            while (true) {
                if (selected == null) {
                    if (candidates == null) {
                        candidates =
                            modelCatalog
                                .models(preset, "", preset.endpoint)
                                .getOrDefault(emptyList())
                                .filter { it.free && it.id.endsWith(":free") }
                                .map { it.id }
                    }
                    selected =
                        candidates.firstOrNull {
                            it !in attempted &&
                                (session.throttled[it] ?: 0) <= Clock.System.now().toEpochMilliseconds()
                        }
                }
                val candidate =
                    selected ?: return Result.failure(
                        session.lastBusyFailure ?: LyricsTranslationException(LyricsTranslationFailure.MODEL_BUSY, "Free models are busy"),
                    )
                attempted += candidate
                val result = request(candidate)
                if (result.isSuccess) {
                    return result
                }
                val error = checkNotNull(result.exceptionOrNull())
                when (error.lyricsTranslationFailure()) {
                    LyricsTranslationFailure.FREE_DAILY_LIMIT -> {
                        session.dailyFailure = error
                        session.dailyResetMillis = (error as? LyricsTranslationHttpException)?.retryAtMillis(now)
                            ?: ((now / 86_400_000 + 1) * 86_400_000)
                        return result
                    }

                    LyricsTranslationFailure.MODEL_BUSY -> {
                        session.throttled[candidate] = (error as? LyricsTranslationHttpException)?.retryAtMillis(now) ?: (now + 60_000)
                        session.lastBusyFailure = error
                        PlaybackEventLog.info(
                            PlaybackEventKind.OTHER,
                            "AI lyrics free model is busy.",
                            details = mapOf("model" to candidate, "failure" to LyricsTranslationFailure.MODEL_BUSY.name),
                        )
                        selected = null
                    }

                    else -> {
                        return result
                    }
                }
            }
        } finally {
            session.lock.unlock()
        }
    }

    private suspend fun requestOpenAiCompatible(
        provider: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        schemaName: String,
        schema: JsonObject,
        maxTokens: Int,
        temperature: Double,
    ): Result<String> {
        fun body(
            responseFormat: JsonObject?,
            includeReasoning: Boolean = true,
        ) = buildJsonObject {
            if (model.isNotBlank()) put("model", JsonPrimitive(model))
            put(
                "messages",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", JsonPrimitive("system"))
                            put("content", JsonPrimitive(systemPrompt))
                        },
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive(userPrompt))
                        },
                    ),
                ),
            )
            put("temperature", JsonPrimitive(temperature))
            put("max_tokens", JsonPrimitive(maxTokens))
            if (includeReasoning && (provider.equals("OpenRouter", ignoreCase = true) || baseUrl.contains("openrouter.ai"))) {
                put(
                    "reasoning",
                    buildJsonObject {
                        // These presets support disabling reasoning so it cannot consume the detection budget.
                        if (AiProviderPresets.openRouterOfflineModels.any { it.id == model }) put("enabled", JsonPrimitive(false))
                        put("exclude", JsonPrimitive(true))
                    },
                )
            }
            if (responseFormat != null) put("response_format", responseFormat)
        }

        return requestWithStructuredFallback(
            url = baseUrl.ifBlank { "https://openrouter.ai/api/v1/chat/completions" },
            bodies =
                listOf(
                    body(openAiResponseFormat(schemaName, schema)),
                    body(buildJsonObject { put("type", JsonPrimitive("json_object")) }),
                    body(null),
                    body(null, includeReasoning = false),
                ).distinct(),
            parse = ::extractOpenAiContent,
            applyHeaders = {
                header("Authorization", "Bearer ${apiKey.trim()}")
                header("HTTP-Referer", "https://github.com/MetrolistGroup/Metrolist")
                header("X-Title", "Metrolist")
            },
        )
    }

    private suspend fun requestAnthropicCompatible(
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        schemaName: String,
        schema: JsonObject,
        maxTokens: Int,
        temperature: Double,
    ): Result<String> {
        val toolName = "return_$schemaName"
        val body =
            buildAnthropicRequestBody(
                model = model,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                toolName = toolName,
                schema = schema,
                maxTokens = maxTokens,
                temperature = temperature,
            )
        val response =
            httpClient.post(baseUrl.ifBlank { "https://api.anthropic.com/v1/messages" }) {
                contentType(ContentType.Application.Json)
                aiTimeout()
                header("x-api-key", apiKey.trim())
                header("anthropic-version", "2023-06-01")
                setBody(body.toString())
            }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return Result.failure(
                LyricsTranslationHttpException(response.status.value, text, response.headers),
            )
        }
        return parseAiCompletion(text, ::extractAnthropicContent)
    }

    internal fun buildAnthropicRequestBody(
        model: String,
        systemPrompt: String,
        userPrompt: String,
        toolName: String,
        schema: JsonObject,
        maxTokens: Int,
        temperature: Double,
    ): JsonObject =
        buildJsonObject {
            put("model", JsonPrimitive(model.ifBlank { "claude-3-5-haiku-latest" }))
            put("max_tokens", JsonPrimitive(maxTokens))
            put("temperature", JsonPrimitive(temperature))
            put("system", JsonPrimitive(systemPrompt))
            put(
                "messages",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive(userPrompt))
                        },
                    ),
                ),
            )
            put(
                "tools",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("name", JsonPrimitive(toolName))
                            put("description", JsonPrimitive("Return the structured lyrics result."))
                            put("input_schema", schema)
                        },
                    ),
                ),
            )
            put(
                "tool_choice",
                buildJsonObject {
                    put("type", JsonPrimitive("tool"))
                    put("name", JsonPrimitive(toolName))
                },
            )
        }

    private suspend fun requestGeminiCompatible(
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        schema: JsonObject,
        maxTokens: Int,
        temperature: Double,
    ): Result<String> {
        val effectiveModel = model.ifBlank { "gemini-flash-latest" }
        val url =
            baseUrl
                .ifBlank {
                    "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
                }.replace("{model}", effectiveModel)
        val body = buildGeminiRequestBody(systemPrompt, userPrompt, temperature, maxTokens, schema)
        val response =
            httpClient.post(url) {
                contentType(ContentType.Application.Json)
                aiTimeout()
                if (!url.contains("key=")) header("x-goog-api-key", apiKey.trim())
                setBody(body.toString())
            }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return Result.failure(
                LyricsTranslationHttpException(response.status.value, text, response.headers),
            )
        }
        return parseAiCompletion(text, ::extractGeminiContent)
    }

    internal fun buildGeminiRequestBody(
        systemPrompt: String,
        userPrompt: String,
        temperature: Double,
        maxTokens: Int,
        schema: JsonObject,
    ): JsonObject =
        buildJsonObject {
            put(
                "contents",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put(
                                "parts",
                                JsonArray(listOf(buildJsonObject { put("text", JsonPrimitive("$systemPrompt\n\n$userPrompt")) })),
                            )
                        },
                    ),
                ),
            )
            put(
                "generationConfig",
                buildJsonObject {
                    put("temperature", JsonPrimitive(temperature))
                    put("maxOutputTokens", JsonPrimitive(maxTokens))
                    put("responseMimeType", JsonPrimitive("application/json"))
                    put("responseSchema", geminiSchema(schema))
                },
            )
        }

    private suspend fun requestWithStructuredFallback(
        url: String,
        bodies: List<JsonObject>,
        parse: (String) -> String?,
        applyHeaders: HttpRequestBuilder.() -> Unit,
    ): Result<String> {
        bodies.forEachIndexed { bodyIndex, body ->
            val response =
                httpClient.post(url) {
                    attributes.put(
                        ExpectedHttpFailureStatusCodes,
                        buildSet {
                            if (bodyIndex < bodies.lastIndex) addAll(setOf(400, 422))
                            addAll(500..599)
                        },
                    )
                    contentType(ContentType.Application.Json)
                    aiTimeout()
                    applyHeaders()
                    setBody(body.toString())
                }
            val responseText = response.bodyAsText()
            if (!response.status.isSuccess()) {
                if (bodyIndex < bodies.lastIndex && response.status.value in setOf(400, 422)) return@forEachIndexed
                return Result.failure(
                    LyricsTranslationHttpException(
                        response.status.value,
                        responseText,
                        response.headers,
                    ),
                )
            }
            return parseAiCompletion(responseText, parse)
        }
        return Result.failure(IllegalStateException("AI request failed"))
    }

    private fun parseAiCompletion(
        responseText: String,
        parse: (String) -> String?,
    ): Result<String> {
        val responseJson = runCatching { Json.parseToJsonElement(responseText).jsonObject }.getOrNull()
        val choice = (responseJson?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
        val candidate = (responseJson?.get("candidates") as? JsonArray)?.firstOrNull() as? JsonObject
        val finish = choice?.get("finish_reason") ?: candidate?.get("finishReason") ?: responseJson?.get("stop_reason")
        val finishReason = (finish as? JsonPrimitive)?.contentOrNull
        val incomplete = finishReason in setOf("length", "MAX_TOKENS", "max_tokens")
        val content = parse(responseText)?.takeIf(String::isNotBlank)
        val message = choice?.get("message") as? JsonObject
        val parts = ((candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
        val reasoningPresent =
            listOf("reasoning", "reasoning_details").any { message?.get(it) !in listOf(null, JsonNull) } ||
                parts.any { (it as? JsonObject)?.get("thought") == JsonPrimitive(true) }
        val knownFinishReasons =
            setOf("stop", "length", "MAX_TOKENS", "STOP", "max_tokens", "end_turn", "tool_use", "content_filter", "SAFETY")
        PlaybackEventLog.info(
            PlaybackEventKind.OTHER,
            "AI lyrics response received.",
            details =
                mapOf(
                    "responseChars" to responseText.length.toString(),
                    "contentChars" to (content?.length ?: 0).toString(),
                    "finishReason" to (finishReason?.takeIf { it in knownFinishReasons } ?: "other"),
                    "reasoningPresent" to reasoningPresent.toString(),
                ),
        )
        if (incomplete) {
            return Result.failure(
                LyricsTranslationException(LyricsTranslationFailure.INCOMPLETE_RESPONSE, "AI output reached the token limit"),
            )
        }
        if (responseJson == null) {
            return Result.failure(
                LyricsTranslationException(LyricsTranslationFailure.INVALID_RESPONSE, "AI response was not JSON"),
            )
        }
        if (responseJson["error"] != null || choice?.get("error") != null || finishReason in setOf("error", "content_filter", "SAFETY")) {
            return Result.failure(
                LyricsTranslationException(classifyLyricsTranslationHttpFailure(200, responseText), "AI provider rejected the completion"),
            )
        }
        if (content != null) return Result.success(content)
        return Result.failure(
            LyricsTranslationException(LyricsTranslationFailure.EMPTY_RESPONSE, "AI response did not contain content"),
        )
    }

    private suspend fun translateWithDeepLKeyless(
        lines: List<String>,
        targetLanguage: String,
    ): Result<List<String>> =
        runCatchingCancellable {
            translateInBatches(lines, maxBatchCharacters = 1_400) { batch ->
                val response =
                    httpClient.post("https://oneshot-free.www.deepl.com/v1/translate") {
                        header("Origin", "chrome-extension://cofdbpoegempjloogbagkncekinflcnj")
                        header("Authorization", "None")
                        contentType(ContentType.Application.Json)
                        setBody(
                            buildJsonObject {
                                put("text", JsonArray(batch.map(::JsonPrimitive)))
                                put("target_lang", JsonPrimitive(deepLTargetLanguage(targetLanguage)))
                                put("usage_type", JsonPrimitive("translate"))
                                put(
                                    "app_information",
                                    buildJsonObject {
                                        put("app_version", JsonPrimitive("1.59.0"))
                                        put("app_build", JsonPrimitive("10059000"))
                                        put("app_name", JsonPrimitive("chromeExtension"))
                                        put("os", JsonPrimitive("macOS"))
                                        put("os_version", JsonPrimitive("10.15.7"))
                                        put("device", JsonPrimitive("MacIntel"))
                                        put("instance_id", JsonPrimitive(deepLInstanceId))
                                    },
                                )
                            }.toString(),
                        )
                    }
                if (!response.status.isSuccess()) {
                    throw LyricsTranslationHttpException(response.status.value, "DeepL keyless request failed (${response.status.value})")
                }
                val translations =
                    Json
                        .parseToJsonElement(response.bodyAsText())
                        .jsonObject["translations"]
                        ?.jsonArray
                        ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                        .orEmpty()
                check(translations.size == batch.size) { "DeepL returned an incomplete translation" }
                translations
            }
        }

    private suspend fun translateWithGoogle(
        lines: List<String>,
        targetLanguage: String,
        mode: String,
    ): Result<LyricsAiResult> =
        runCatchingCancellable {
            val isRomanizedOnly = mode == "Romanized"
            var allTranslated = mutableListOf<String>()
            var allRomanized = mutableListOf<String>()
            translateInBatches(lines, maxBatchCharacters = 1_200) { batch ->
                val markers = List((batch.size - 1).coerceAtLeast(0)) { "__METROLIST_LINE_${it + 1}__" }
                val input =
                    batch
                        .mapIndexed { index, line ->
                            line + markers.getOrNull(index)?.let { "\n$it\n" }.orEmpty()
                        }.joinToString("")
                val (translatedText, romanizedText) = translateGoogleText(input, targetLanguage)
                val splitTranslated = splitByMarkers(translatedText, markers) ?: batch.map { translateGoogleText(it, targetLanguage).first }
                val splitRomanized = romanizedText?.let { splitByMarkers(it, markers) }
                allTranslated.addAll(splitTranslated)
                if (splitRomanized != null) {
                    allRomanized.addAll(splitRomanized)
                }
                splitTranslated
            }
            if (isRomanizedOnly) {
                LyricsAiResult(romanizedLines = allRomanized.ifEmpty { allTranslated })
            } else {
                LyricsAiResult(
                    translatedLines = allTranslated,
                    romanizedLines = allRomanized.takeIf { it.size == lines.size },
                )
            }
        }

    private suspend fun romanizeWithGoogle(lines: List<String>): Result<LyricsAiResult> =
        runCatchingCancellable {
            val resultsMap = mutableMapOf<Int, String>()
            val validIndices = mutableListOf<Int>()
            val textsToFetch = mutableListOf<String>()

            lines.forEachIndexed { index, text ->
                if (text.isNotBlank() && !isPurelyLatinScript(text)) {
                    validIndices.add(index)
                    textsToFetch.add(text)
                }
            }

            if (textsToFetch.isEmpty()) {
                return@runCatchingCancellable LyricsAiResult(romanizedLines = lines)
            }

            val batchSize = 50
            for (i in 0 until textsToFetch.size step batchSize) {
                val batchEnd = (i + batchSize).coerceAtMost(textsToFetch.size)
                val batch = textsToFetch.subList(i, batchEnd)
                val batchText = batch.joinToString("|")
                try {
                    val response =
                        httpClient.post("https://translate.googleapis.com/translate_a/single") {
                            setBody(
                                FormDataContent(
                                    Parameters.build {
                                        append("client", "gtx")
                                        append("sl", "auto")
                                        append("tl", "la")
                                        append("dt", "rm")
                                        append("q", batchText)
                                    },
                                ),
                            )
                        }
                    if (response.status.isSuccess()) {
                        val root = Json.parseToJsonElement(response.bodyAsText()).jsonArray
                        val firstArr = root.firstOrNull()?.jsonArray
                        val fullRomanizedString =
                            firstArr
                                ?.mapNotNull { segment ->
                                    runCatching {
                                        val arr = segment.jsonArray
                                        arr.getOrNull(3)?.jsonPrimitive?.contentOrNull
                                            ?: arr.getOrNull(2)?.jsonPrimitive?.contentOrNull
                                            ?: arr.getOrNull(0)?.jsonPrimitive?.contentOrNull
                                    }.getOrNull()
                                }?.joinToString("")
                                .orEmpty()

                        if (fullRomanizedString.isNotBlank()) {
                            val split = fullRomanizedString.replace(Regex("\\s*\\|\\s*"), "|").split("|")
                            batch.forEachIndexed { batchIndex, originalText ->
                                val rom = split.getOrNull(batchIndex)?.trim()?.ifBlank { originalText } ?: originalText
                                resultsMap[validIndices[i + batchIndex]] = rom
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }

            val finalRomanized = lines.mapIndexed { index, text -> resultsMap[index] ?: text }
            LyricsAiResult(romanizedLines = finalRomanized)
        }

    private fun isPurelyLatinScript(text: String): Boolean {
        if (text.isBlank()) return true
        return text.all { char ->
            char.isWhitespace() || char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
        }
    }

    private suspend fun translateGoogleText(
        text: String,
        targetLanguage: String,
    ): Pair<String, String?> {
        val response =
            httpClient.post("https://translate.googleapis.com/translate_a/single") {
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("client", "gtx")
                            append("sl", "auto")
                            append("tl", targetLanguage)
                            append("dt", "t")
                            append("dt", "rm")
                            append("q", text)
                        },
                    ),
                )
            }
        if (!response.status.isSuccess()) {
            throw LyricsTranslationHttpException(
                response.status.value,
                "Google Translate request failed (${response.status.value})",
            )
        }
        val root = Json.parseToJsonElement(response.bodyAsText()).jsonArray
        val firstArr = root.firstOrNull()?.jsonArray ?: return Pair("", null)
        val translated =
            firstArr
                .mapNotNull { segment ->
                    runCatching {
                        segment.jsonArray
                            .firstOrNull()
                            ?.jsonPrimitive
                            ?.contentOrNull
                    }.getOrNull()
                }.joinToString("")

        var romanized: String? = null
        for (element in firstArr) {
            val arr = runCatching { element.jsonArray }.getOrNull() ?: continue
            if (arr.size >= 3 && arr.getOrNull(0)?.jsonPrimitive?.contentOrNull == null) {
                val candidate = arr.getOrNull(2)?.jsonPrimitive?.contentOrNull ?: arr.getOrNull(3)?.jsonPrimitive?.contentOrNull
                if (!candidate.isNullOrBlank()) {
                    romanized = candidate
                    break
                }
            }
        }
        return Pair(translated, romanized)
    }

    private suspend fun translateWithDeepL(
        lines: List<String>,
        targetLanguage: String,
        apiKey: String,
        formality: String,
    ): Result<List<String>> =
        try {
            if (apiKey.isBlank()) return Result.failure(IllegalArgumentException("DeepL API key is required"))
            val language =
                if (targetLanguage
                        .substringBefore(
                            '-',
                        ).equals("zh", ignoreCase = true)
                ) {
                    "ZH"
                } else {
                    deepLTargetLanguage(targetLanguage).uppercase()
                }
            val body =
                buildJsonObject {
                    put("text", JsonArray(lines.map { JsonPrimitive(it) }))
                    put("target_lang", JsonPrimitive(language))
                    put("preserve_formatting", JsonPrimitive(true))
                    if (formality != "default") put("formality", JsonPrimitive(formality))
                }
            val endpoint = if (apiKey.endsWith(":fx")) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"
            val response =
                httpClient.post(endpoint) {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "DeepL-Auth-Key ${apiKey.trim()}")
                    setBody(body)
                }
            val responseText = response.bodyAsText()
            if (!response.status.isSuccess()) {
                return Result.failure(
                    LyricsTranslationHttpException(
                        response.status.value,
                        "DeepL translation failed (${response.status.value}): ${responseText.take(200)}",
                    ),
                )
            }
            val json = Json.parseToJsonElement(responseText).jsonObject
            val translated =
                json["translations"]
                    ?.jsonArray
                    ?.map {
                        it.jsonObject["text"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            .orEmpty()
                    }.orEmpty()
            if (translated.size != lines.size) {
                Result.failure(IllegalStateException("DeepL returned an incomplete response"))
            } else {
                Result.success(translated)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private fun extractOpenAiContent(responseText: String): String? =
        runCatching {
            val message =
                Json
                    .parseToJsonElement(responseText)
                    .jsonObject["choices"]
                    ?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("message")
                    ?.jsonObject
                    ?: return null
            message["content"]
                ?.let(::extractTextContent)
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: message["tool_calls"]
                    ?.jsonArray
                    ?.firstNotNullOfOrNull { call ->
                        call.jsonObject["function"]
                            ?.jsonObject
                            ?.get("arguments")
                            ?.let { if (it is JsonPrimitive) it.contentOrNull else it.toString() }
                            ?.trim()
                            ?.takeIf(String::isNotBlank)
                    }
        }.getOrNull()

    private fun extractAnthropicContent(responseText: String): String? =
        runCatching {
            val content = Json.parseToJsonElement(responseText).jsonObject["content"]?.jsonArray ?: return null
            content
                .firstNotNullOfOrNull { part ->
                    part.jsonObject
                        .takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "tool_use" }
                        ?.get("input")
                        ?.toString()
                }?.trim()
                ?: content
                    .mapNotNull { part ->
                        part.jsonObject
                            .takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                            ?.get(
                                "text",
                            )?.let(::extractTextContent)
                    }.joinToString("")
                    .trim()
                    .takeIf(String::isNotBlank)
        }.getOrNull()

    private fun extractTextContent(element: JsonElement): String? =
        when (element) {
            JsonNull -> {
                null
            }

            is JsonPrimitive -> {
                element.contentOrNull
            }

            is JsonArray -> {
                element
                    .mapNotNull { item ->
                        when (item) {
                            is JsonPrimitive -> item.contentOrNull
                            is JsonObject -> item["text"]?.let(::extractTextContent)
                            else -> null
                        }
                    }.joinToString("")
                    .takeIf(String::isNotBlank)
            }

            is JsonObject -> {
                element["text"]?.let(::extractTextContent)
            }
        }

    private fun extractGeminiContent(responseText: String): String? =
        runCatching {
            Json
                .parseToJsonElement(responseText)
                .jsonObject["candidates"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("content")
                ?.jsonObject
                ?.get("parts")
                ?.jsonArray
                ?.filter { (it as? JsonObject)?.get("thought") != JsonPrimitive(true) }
                ?.let(::JsonArray)
                ?.let(::extractTextContent)
                ?.trim()
        }.getOrNull()

    private fun parseJsonObjectPayload(content: String): JsonObject? = parseJsonPayload(content, '{', '}') as? JsonObject

    private fun parseJsonPayload(
        content: String,
        opening: Char,
        closing: Char,
    ): JsonElement? {
        // Ignore prose and fences outside JSON without changing quoted lyric text.
        for (start in content.indices.filter { content[it] == opening }) {
            var depth = 0
            var quoted = false
            var escaped = false
            for (index in start..<content.length) {
                val char = content[index]
                if (quoted) {
                    when {
                        escaped -> escaped = false
                        char == '\\' -> escaped = true
                        char == '"' -> quoted = false
                    }
                } else {
                    when (char) {
                        '"' -> quoted = true
                        opening -> depth++
                        closing -> depth--
                    }
                    if (depth == 0) {
                        val parsed = runCatching { Json.parseToJsonElement(content.substring(start, index + 1)) }.getOrNull()
                        if (parsed != null) return parsed
                        break
                    }
                }
            }
        }
        return null
    }

    private fun parseJsonLinePayload(
        content: String,
        expectedLines: Int,
    ): List<String>? {
        val json = parseJsonObjectPayload(content)
        val lines = (json?.get("lines") as? JsonArray)?.let(::parseStringLines)
        if (lines?.size == expectedLines) return lines
        return parseJsonLineArray(content, expectedLines)
    }

    private fun parseJsonLineArray(
        content: String,
        expectedLines: Int,
    ): List<String>? {
        val lines = (parseJsonPayload(content, '[', ']') as? JsonArray)?.let(::parseStringLines)
        return lines?.takeIf { it.size == expectedLines }
    }

    private fun unalignedLyricsFailure(
        content: String,
        expectedLines: Int,
    ): LyricsTranslationException {
        val objectLines = parseJsonObjectPayload(content)?.get("lines") as? JsonArray
        val arrayLines = runCatching { Json.parseToJsonElement(content).jsonArray }.getOrNull()
        PlaybackEventLog.warn(
            PlaybackEventKind.OTHER,
            "AI lyrics response has invalid or misaligned lines.",
            details =
                mapOf(
                    "expectedLines" to expectedLines.toString(),
                    "actualLines" to (objectLines ?: arrayLines)?.size.toString(),
                    "contentChars" to content.length.toString(),
                ),
        )
        return LyricsTranslationException(LyricsTranslationFailure.INVALID_RESPONSE, "AI response did not contain aligned lyric lines")
    }

    private fun parseStringLines(lines: JsonArray): List<String>? =
        lines.map { ((it as? JsonPrimitive)?.takeIf { value -> value.isString } ?: return null).content }

    private fun openAiResponseFormat(
        name: String,
        schema: JsonObject,
    ): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("json_schema"))
            put(
                "json_schema",
                buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("strict", JsonPrimitive(true))
                    put("schema", schema)
                },
            )
        }

    private fun lyricsLinesSchema(): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "lines",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                        },
                    )
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("lines"))))
            put("additionalProperties", JsonPrimitive(false))
        }

    private fun lyricsRomanizedLinesWithWordsSchema(): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "lines",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", JsonPrimitive("object"))
                                    put(
                                        "properties",
                                        buildJsonObject {
                                            put("romanizedLine", buildJsonObject { put("type", JsonPrimitive("string")) })
                                            put(
                                                "romanizedWords",
                                                buildJsonObject {
                                                    put("type", JsonPrimitive("array"))
                                                    put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                                                },
                                            )
                                        },
                                    )
                                    put(
                                        "required",
                                        JsonArray(listOf(JsonPrimitive("romanizedLine"), JsonPrimitive("romanizedWords"))),
                                    )
                                    put("additionalProperties", JsonPrimitive(false))
                                },
                            )
                        },
                    )
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("lines"))))
            put("additionalProperties", JsonPrimitive(false))
        }

    private fun parseJsonRomanizedLinesWithWordsPayload(
        content: String,
        expectedLines: Int,
    ): Pair<List<String>, List<List<String>?>>? {
        val json = parseJsonObjectPayload(content) ?: return null
        val linesArray = json["lines"]?.jsonArray ?: return null
        val parsedLines = mutableListOf<String>()
        val parsedWords = mutableListOf<List<String>?>()

        for (elem in linesArray) {
            if (elem is JsonObject) {
                val line =
                    elem["romanizedLine"]?.jsonPrimitive?.contentOrNull
                        ?: elem["line"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                val words =
                    elem["romanizedWords"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull.orEmpty() }
                        ?: elem["words"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull.orEmpty() }
                parsedLines.add(line)
                parsedWords.add(words)
            } else if (elem is JsonPrimitive) {
                parsedLines.add(elem.content)
                parsedWords.add(null)
            } else {
                parsedLines.add("")
                parsedWords.add(null)
            }
        }
        if (parsedLines.size != expectedLines || parsedWords.size != expectedLines) return null
        return Pair(parsedLines, parsedWords)
    }

    private fun foreignPassageInstructions(target: String): String =
        "Find meaningful text in a language other than $target, even inside a line otherwise written in $target. " +
            "Do not use the majority or dominant language or a minimum percentage. " +
            "Check both different scripts and languages sharing a script. " +
            "A script used by $target is not by itself foreign. Ignore names, short interjections, punctuation and numbers. " +
            "Text entirely in $target needs no translation."

    private fun hasForeignPassage(value: JsonElement?): Boolean =
        (
            (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?: throw LyricsTranslationException(
                    LyricsTranslationFailure.INVALID_RESPONSE,
                    "AI detector returned an invalid foreign passage",
                )
        ).any(Char::isLetter)

    private fun detectionSchema(): JsonObject =
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("foreign_text", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("foreign_text"))))
            put("additionalProperties", JsonPrimitive(false))
        }

    private fun geminiSchema(schema: JsonObject): JsonObject =
        buildJsonObject {
            schema.forEach { (key, value) ->
                if (key != "additionalProperties") {
                    put(key, if (key == "type") JsonPrimitive(value.jsonPrimitive.content.uppercase()) else value.toGeminiSchemaValue())
                }
            }
        }

    private fun JsonElement.toGeminiSchemaValue(): JsonElement =
        when (this) {
            is JsonObject -> {
                geminiSchema(this)
            }

            is JsonArray -> {
                JsonArray(map { it.toGeminiSchemaValue() })
            }

            is JsonPrimitive -> {
                if (isString && contentOrNull in setOf("object", "array", "string", "boolean", "number", "integer")) {
                    JsonPrimitive(content.uppercase())
                } else {
                    this
                }
            }
        }

    private fun defaultLyricsSystemPrompt(lineCount: Int): String =
        "You are a lyrics localization engine. Return only JSON that matches the provided schema. " +
            "Do not explain, think aloud, use markdown, merge lines, split lines, or add notes. " +
            "Return exactly $lineCount strings in `lines`, preserving input line order. Empty input lines must stay empty. " +
            "Make translations natural for music listening while staying faithful to meaning, tense, names, punctuation, " +
            "and repeated hooks. " +
            "Preserve text already in the target language verbatim, including the target-language parts of mixed lines."

    private fun linesAreAligned(
        source: List<String>,
        output: List<String>,
    ): Boolean = source.size == output.size && source.indices.all { source[it].isBlank() == output[it].isBlank() }

    private fun wordsAreAligned(
        source: List<String>,
        output: List<String>?,
    ): Boolean = output != null && source.size == output.size && source.indices.all { source[it].isBlank() == output[it].isBlank() }

    private suspend fun translateInBatches(
        lines: List<String>,
        maxBatchCharacters: Int,
        translateBatch: suspend (List<String>) -> List<String>,
    ): List<String> {
        val translated = MutableList(lines.size) { "" }
        val nonBlank = lines.withIndex().filter { it.value.isNotBlank() }
        var cursor = 0
        while (cursor < nonBlank.size) {
            currentCoroutineContext().ensureActive()
            val batch = mutableListOf<IndexedValue<String>>()
            var characters = 0
            while (cursor < nonBlank.size) {
                val candidate = nonBlank[cursor]
                check(candidate.value.length <= maxBatchCharacters) { "A lyric line is too long to translate safely" }
                if (batch.isNotEmpty() && characters + candidate.value.length > maxBatchCharacters) break
                batch += candidate
                characters += candidate.value.length
                cursor++
            }
            val batchResult = translateBatch(batch.map { it.value })
            check(batchResult.size == batch.size) { "Translation provider returned an incomplete response" }
            batch.forEachIndexed { index, original -> translated[original.index] = batchResult[index].trim() }
        }
        return translated
    }

    private fun splitByMarkers(
        translated: String,
        markers: List<String>,
    ): List<String>? {
        if (markers.isEmpty()) return listOf(translated.trim())
        val parts = mutableListOf<String>()
        var remaining = translated
        markers.forEach { marker ->
            val markerIndex = remaining.indexOf(marker)
            if (markerIndex < 0) return null
            parts += remaining.substring(0, markerIndex).trim()
            remaining = remaining.substring(markerIndex + marker.length)
        }
        parts += remaining.trim()
        return parts
    }

    suspend fun probeLyricsAiConnection(
        provider: String,
        targetLanguage: String,
        translationMode: String,
        activationMode: String,
        apiKey: String,
        baseUrl: String,
        translationModel: String,
        detectorModel: String,
        systemPrompt: String = "",
        deepLApiKey: String = "",
        deepLFormality: String = "default",
    ): Result<AiLyricsProbeResult> =
        runCatchingCancellable {
            val sample = listOf("Hello")
            when {
                provider.equals("DeepL", ignoreCase = true) -> {
                    val lines =
                        translateWithDeepL(sample, targetLanguage, deepLApiKey, deepLFormality)
                            .getOrThrow()
                    AiLyricsProbeResult(translatedSample = lines.first())
                }

                provider.equals("DeepL (keyless)", ignoreCase = true) -> {
                    val lines = translateWithDeepLKeyless(sample, targetLanguage).getOrThrow()
                    AiLyricsProbeResult(translatedSample = lines.first())
                }

                provider.equals("Google Translate", ignoreCase = true) -> {
                    val result = translateWithGoogle(sample, targetLanguage, translationMode).getOrThrow()
                    AiLyricsProbeResult(
                        translatedSample =
                            result.translatedLines
                                ?.firstOrNull()
                                ?.trim()
                                .orEmpty(),
                    )
                }

                else -> {
                    if (apiKey.isBlank()) throw IllegalArgumentException("missing credentials")
                    val detector = detectorModel.ifBlank { translationModel }
                    val detection =
                        if (activationMode == "Automatic") {
                            shouldTranslateLyrics(
                                sample,
                                provider,
                                targetLanguage,
                                apiKey,
                                baseUrl,
                                detector,
                                systemPrompt,
                            ).getOrThrow()
                        } else {
                            null
                        }
                    val translated =
                        translateLyricsLines(
                            sample,
                            provider,
                            targetLanguage,
                            translationMode,
                            apiKey,
                            baseUrl,
                            translationModel,
                            systemPrompt,
                        ).getOrThrow()
                    AiLyricsProbeResult(
                        detectionNeedsTranslation = detection,
                        translatedSample =
                            translated.translatedLines
                                ?.firstOrNull()
                                ?.trim()
                                .orEmpty(),
                    )
                }
            }
        }

    private suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private fun detectionBatches(lines: List<String>): Sequence<String> =
        sequence {
            val text = lines.joinToString("\n")
            var start = 0
            while (start < text.length) {
                var end = minOf(start + MAX_DETECTION_BATCH_CHARS, text.length)
                if (end < text.length) {
                    val boundary =
                        (end - 1 downTo start).firstOrNull { text[it] == '\n' }
                            ?: (end - 1 downTo start).firstOrNull { text[it].isWhitespace() }
                    if (boundary != null) {
                        end = boundary + 1
                    } else if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) {
                        end--
                    }
                }
                yield(text.substring(start, end))
                start = end
            }
        }

    private fun deepLTargetLanguage(code: String): String =
        if (code.equals("en", ignoreCase = true) || code.equals("pt", ignoreCase = true)) {
            resolveDeepLTargetLanguage(
                code,
                Settings.shared.contentCountry.takeUnless { it == GlobalYouTube.SYSTEM } ?: systemCountryCode(),
            )
        } else {
            resolveDeepLTargetLanguage(code)
        }

    private companion object {
        // AI models can spend 30 seconds reasoning before returning content.
        const val AI_REQUEST_TIMEOUT_MS = 120_000L
        const val MAX_DETECTION_BATCH_CHARS = 8_000
    }

    private fun languageName(code: String): String =
        when (code.lowercase()) {
            "en" -> "English"
            "es" -> "Spanish"
            "fr" -> "French"
            "de" -> "German"
            "it" -> "Italian"
            "pt" -> "Portuguese"
            "ru" -> "Russian"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "zh" -> "Chinese"
            "ar" -> "Arabic"
            "hi" -> "Hindi"
            "bn" -> "Bengali"
            "pa" -> "Punjabi"
            "tr" -> "Turkish"
            "vi" -> "Vietnamese"
            "th" -> "Thai"
            "id" -> "Indonesian"
            "pl" -> "Polish"
            "nl" -> "Dutch"
            "sv" -> "Swedish"
            "uk" -> "Ukrainian"
            else -> code
        }
}

internal fun resolveDeepLTargetLanguage(
    code: String,
    country: String? = null,
): String =
    when (code.lowercase()) {
        "zh-cn", "zh-hans" -> "zh-Hans"
        "zh-tw", "zh-hant" -> "zh-Hant"
        "en" -> if (country?.uppercase() in setOf("US", "GB")) "en-${country?.uppercase()}" else code
        "pt" -> if (country?.uppercase() in setOf("PT", "BR")) "pt-${country?.uppercase()}" else code
        else -> code
    }
