/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

enum class AiProtocol { OPENAI, ANTHROPIC, GEMINI }

enum class AiProviderGroup { GATEWAY, DIRECT, CUSTOM, SERVICE }

data class AiProviderPreset(
    val id: String,
    val group: AiProviderGroup,
    val protocol: AiProtocol = AiProtocol.OPENAI,
    /** Full request endpoint; Gemini retains a {model} placeholder. */
    val endpoint: String = "",
    val defaultModel: String = "",
    val modelsDevId: String? = null,
    val editableEndpoint: Boolean = false,
    val supportsOAuth: Boolean = false,
    val publicModelList: Boolean = false,
    val mixedProtocols: Boolean = false,
) {
    val isAi: Boolean get() = group != AiProviderGroup.SERVICE

    val modelsEndpoint: String
        get() =
            endpoint
                .replace(Regex("/(chat/completions|messages)$"), "/models")
                .replace(Regex("/models/\\{model\\}:generateContent$"), "/models")
}

object AiProviderPresets {
    private const val LEGACY_CUSTOM = "Custom"

    const val DEFAULT_LYRICS_AI_MODEL = "qwen/qwen3.7-flash"
    const val FREE_LYRICS_AI_MODEL = "google/gemma-4-26b-a4b-it:free"

    // OpenRouter public catalog, checked 2026-11-13. Prices are USD per million tokens.
    val openRouterOfflineModels =
        listOf(
            AiModelInfo(FREE_LYRICS_AI_MODEL, inputUsdPerMTok = 0.0, outputUsdPerMTok = 0.0),
            AiModelInfo("google/gemma-4-31b-it:free", inputUsdPerMTok = 0.0, outputUsdPerMTok = 0.0),
            AiModelInfo(DEFAULT_LYRICS_AI_MODEL, inputUsdPerMTok = 0.03, outputUsdPerMTok = 0.13),
            AiModelInfo("google/gemini-2.5-flash-lite", inputUsdPerMTok = 0.1, outputUsdPerMTok = 0.4),
        )

    val all: List<AiProviderPreset> =
        listOf(
            AiProviderPreset(
                "OpenRouter",
                AiProviderGroup.GATEWAY,
                endpoint = "https://openrouter.ai/api/v1/chat/completions",
                defaultModel = DEFAULT_LYRICS_AI_MODEL,
                modelsDevId = "openrouter",
                supportsOAuth = true,
                publicModelList = true,
            ),
            AiProviderPreset(
                "OpenCodeZen",
                AiProviderGroup.GATEWAY,
                endpoint = "https://opencode.ai/zen/v1/chat/completions",
                defaultModel = "deepseek-v4-flash",
                modelsDevId = "opencode",
                publicModelList = true,
                mixedProtocols = true,
            ),
            AiProviderPreset(
                "OpenCodeGo",
                AiProviderGroup.GATEWAY,
                endpoint = "https://opencode.ai/zen/go/v1/chat/completions",
                defaultModel = "glm-5.3-flash",
                modelsDevId = "opencode-go",
                publicModelList = true,
                mixedProtocols = true,
            ),
            AiProviderPreset(
                "Groq",
                AiProviderGroup.GATEWAY,
                endpoint = "https://api.groq.com/openai/v1/chat/completions",
                defaultModel = "openai/gpt-oss-20b",
                modelsDevId = "groq",
            ),
            AiProviderPreset(
                "OpenAI",
                AiProviderGroup.DIRECT,
                endpoint = "https://api.openai.com/v1/chat/completions",
                defaultModel = "gpt-6-luna",
                modelsDevId = "openai",
            ),
            AiProviderPreset(
                "Anthropic",
                AiProviderGroup.DIRECT,
                AiProtocol.ANTHROPIC,
                "https://api.anthropic.com/v1/messages",
                "claude-haiku-5-5",
                "anthropic",
            ),
            AiProviderPreset(
                "Gemini",
                AiProviderGroup.DIRECT,
                AiProtocol.GEMINI,
                "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
                "gemini-2.5-flash-lite",
                "google",
            ),
            AiProviderPreset(
                "XAi",
                AiProviderGroup.DIRECT,
                endpoint = "https://api.x.ai/v1/chat/completions",
                defaultModel = "grok-build-0.1",
                modelsDevId = "xai",
            ),
            AiProviderPreset(
                "Mistral",
                AiProviderGroup.DIRECT,
                endpoint = "https://api.mistral.ai/v1/chat/completions",
                defaultModel = "mistral-small-latest",
                modelsDevId = "mistral",
            ),
            AiProviderPreset(
                "Inception",
                AiProviderGroup.DIRECT,
                endpoint = "https://api.inceptionlabs.ai/v1/chat/completions",
                defaultModel = "mercury-2.5",
                modelsDevId = "inception",
                publicModelList = true,
            ),
            AiProviderPreset(
                "DeepSeek",
                AiProviderGroup.DIRECT,
                endpoint = "https://api.deepseek.com/v1/chat/completions",
                defaultModel = "deepseek-flash",
                modelsDevId = "deepseek",
            ),
            AiProviderPreset(
                "Perplexity",
                AiProviderGroup.DIRECT,
                endpoint = "https://api.perplexity.ai/chat/completions",
                defaultModel = "sonar",
                modelsDevId = "perplexity",
            ),
            AiProviderPreset(
                "OpenAI-compatible",
                AiProviderGroup.CUSTOM,
                endpoint = "https://api.openai.com/v1/chat/completions",
                editableEndpoint = true,
            ),
            AiProviderPreset(
                "Anthropic-compatible",
                AiProviderGroup.CUSTOM,
                AiProtocol.ANTHROPIC,
                "https://api.anthropic.com/v1/messages",
                editableEndpoint = true,
            ),
            AiProviderPreset(
                "Gemini-compatible",
                AiProviderGroup.CUSTOM,
                AiProtocol.GEMINI,
                "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
                editableEndpoint = true,
            ),
            AiProviderPreset("DeepL", AiProviderGroup.SERVICE),
            AiProviderPreset("DeepL (keyless)", AiProviderGroup.SERVICE),
            AiProviderPreset("Google Translate", AiProviderGroup.SERVICE),
        )

    fun find(id: String): AiProviderPreset? {
        val resolved = if (id == LEGACY_CUSTOM) "OpenAI-compatible" else id
        return all.firstOrNull { it.id == resolved }
    }

    fun protocolFor(
        provider: String,
        baseUrl: String,
        model: String,
    ): AiProtocol {
        val preset = find(provider)
        return when {
            preset?.mixedProtocols == true -> if (model.startsWith("claude", ignoreCase = true)) AiProtocol.ANTHROPIC else AiProtocol.OPENAI
            preset != null && preset.protocol != AiProtocol.OPENAI -> preset.protocol
            provider.contains("anthropic", ignoreCase = true) || baseUrl.contains("anthropic.com") -> AiProtocol.ANTHROPIC
            provider.contains("gemini", ignoreCase = true) && !baseUrl.contains("/openai/") -> AiProtocol.GEMINI
            baseUrl.contains("generativelanguage.googleapis.com") && !baseUrl.contains("/openai/") -> AiProtocol.GEMINI
            else -> AiProtocol.OPENAI
        }
    }

    fun requestUrl(
        provider: String,
        baseUrl: String,
        model: String,
    ): String =
        if (find(provider)?.mixedProtocols == true && protocolFor(provider, baseUrl, model) == AiProtocol.ANTHROPIC) {
            baseUrl.replace(Regex("/chat/completions$"), "/messages")
        } else {
            baseUrl
        }
}
