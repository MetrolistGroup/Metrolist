/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@file:Suppress("FunctionName", "ktlint:standard:no-wildcard-imports")

package com.metrolist.shared.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.shared.api.lyrics.AiProviderPresets
import com.metrolist.shared.generated.resources.*
import com.metrolist.shared.settings.LyricsTextSizeRange
import com.metrolist.shared.settings.PlayerBackgroundStyle
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.lyrics.LyricsOffsetEditor
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private fun isDirectLyricsTranslationProvider(provider: String): Boolean = provider == "DeepL (keyless)" || provider == "Google Translate"

@Composable
internal fun SettingsUiRegistryBuilder.registerLyricsSettings(host: SharedSettingsHost) {
    val settings = Settings.shared
    val lyricsPositionChoices =
        listOf(
            SettingsChoice("LEFT", stringResource(Res.string.lyrics_text_position_left)),
            SettingsChoice("CENTER", stringResource(Res.string.lyrics_text_position_center)),
            SettingsChoice("RIGHT", stringResource(Res.string.lyrics_text_position_right)),
        )
    val lyricsAnimationChoices =
        listOf(
            SettingsChoice("Apple", stringResource(Res.string.apple_music_style)),
            SettingsChoice("Braccato", stringResource(Res.string.braccato_style)),
            SettingsChoice("Expressive", stringResource(Res.string.expressive_style)),
            SettingsChoice("Simple", stringResource(Res.string.simple_style)),
            SettingsChoice("None", stringResource(Res.string.none)),
        )
    page(
        id = SettingsPageId.LYRICS,
        title = stringResource(Res.string.lyrics_settings_title),
        subtitle = stringResource(Res.string.settings_lyrics_description),
        icon = MetrolistIcons.Outlined.Lyrics,
        tone = SettingsTone.ROSE,
    ) {
        group("alignment", stringResource(Res.string.lyrics_alignment)) {
            choice(
                id = "lyricsPosition",
                title = stringResource(Res.string.lyrics_alignment),
                valueLabel = lyricsPositionChoices.firstOrNull { it.id == settings.lyricsTextPosition.uppercase() }?.label,
                choices = lyricsPositionChoices,
                selectedChoiceId = settings.lyricsTextPosition.uppercase(),
            ) { settings.lyricsTextPosition = it }
            choice(
                id = "activeLyricsPosition",
                title = stringResource(Res.string.active_lyrics_alignment),
                valueLabel = lyricsPositionChoices.firstOrNull { it.id == settings.activeLyricsTextPosition.uppercase() }?.label,
                choices = lyricsPositionChoices,
                selectedChoiceId = settings.activeLyricsTextPosition.uppercase(),
            ) { settings.activeLyricsTextPosition = it }
            switch(
                id = "respectAgentPositioning",
                title = stringResource(Res.string.lyrics_respect_agent_positioning),
                description = stringResource(Res.string.lyrics_respect_agent_positioning_desc),
                checked = settings.respectAgentPositioning,
            ) { settings.respectAgentPositioning = it }
        }
        group("behavior", stringResource(Res.string.lyrics_behavior)) {
            switch(
                id = "preferWordSyncedLyrics",
                title = stringResource(Res.string.prefer_word_synced_lyrics),
                description = stringResource(Res.string.prefer_word_synced_lyrics_description),
                checked = settings.preferWordSyncedLyrics,
            ) { settings.preferWordSyncedLyrics = it }
            custom(
                id = "globalLyricsOffset",
                title = stringResource(Res.string.global_lyrics_offset),
                description = stringResource(Res.string.global_lyrics_offset_description),
            ) { onSettingValueChanged ->
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)) {
                    LyricsOffsetEditor(
                        offsetMs = settings.globalLyricsOffsetMs,
                        onOffsetChange = {
                            settings.globalLyricsOffsetMs = it
                            onSettingValueChanged()
                        },
                        onDone = null,
                        title = stringResource(Res.string.global_lyrics_offset),
                    )
                    Text(
                        text = stringResource(Res.string.global_lyrics_offset_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            switch(
                id = "lyricsClickToSeek",
                title = stringResource(Res.string.lyrics_change_on_tap),
                description = stringResource(Res.string.lyrics_change_on_tap_desc),
                checked = settings.lyricsClickToSeek,
            ) { settings.lyricsClickToSeek = it }
            switch(
                id = "lyricsAutoScroll",
                title = stringResource(Res.string.lyrics_auto_scroll),
                description = stringResource(Res.string.lyrics_auto_scroll_desc),
                checked = settings.lyricsAutoScroll,
            ) { settings.lyricsAutoScroll = it }
            switch(
                id = "showLyricsOnPlayer",
                title = stringResource(Res.string.lyrics_show_on_player),
                description = stringResource(Res.string.lyrics_show_on_player_desc),
                checked = settings.showLyricsOnPlayer,
            ) { settings.showLyricsOnPlayer = it }
            switch(
                id = "hideStatusBar",
                title = stringResource(Res.string.lyrics_hide_status_bar_fullscreen),
                description = stringResource(Res.string.lyrics_hide_status_bar_fullscreen_desc),
                checked = settings.lyricsHideStatusBarInFullscreen,
            ) { settings.lyricsHideStatusBarInFullscreen = it }
        }
        group("display", stringResource(Res.string.lyrics_display)) {
            val colorChoices =
                listOf(
                    SettingsChoice("Theme", stringResource(Res.string.lyrics_color_follow_theme)),
                    SettingsChoice("Neutral", stringResource(Res.string.theme_palette_neutral)),
                )
            val colorChoice = if (settings.lyricsNeutralColor) "Neutral" else "Theme"
            choice(
                id = "lyricsColor",
                title = stringResource(Res.string.lyrics_text_color),
                valueLabel = colorChoices.first { it.id == colorChoice }.label,
                choices = colorChoices,
                selectedChoiceId = colorChoice,
            ) { settings.lyricsNeutralColor = it == "Neutral" }
            choice(
                id = "lyricsAnimation",
                title = stringResource(Res.string.lyrics_animation_style),
                valueLabel = lyricsAnimationChoices.firstOrNull { it.id == settings.lyricsAnimationStyle }?.label,
                choices = lyricsAnimationChoices,
                selectedChoiceId = settings.lyricsAnimationStyle,
            ) { settings.lyricsAnimationStyle = it }
            inlineSlider(
                id = "lyricsTextSize",
                title = stringResource(Res.string.lyrics_text_size),
                valueLabel = settings.lyricsTextSize.roundToInt().toString(),
                value = settings.lyricsTextSize,
                defaultValue = settings.defaultValue(settings::lyricsTextSize),
                range = LyricsTextSizeRange,
                steps = 29,
            ) { settings.lyricsTextSize = it.roundToInt().toFloat() }
            inlineSlider(
                id = "lyricsLineSpacing",
                title = stringResource(Res.string.lyrics_line_spacing),
                valueLabel = "${(settings.lyricsLineSpacing * 10).roundToInt() / 10f}x",
                value = settings.lyricsLineSpacing,
                defaultValue = settings.defaultValue(settings::lyricsLineSpacing),
                range = 1f..2.5f,
                steps = 14,
            ) { settings.lyricsLineSpacing = (it * 10).roundToInt() / 10f }
            inlineSlider(
                id = "subtitleTextSize",
                title = stringResource(Res.string.subtitle_text_size),
                valueLabel = settings.subtitleTextSize.roundToInt().toString(),
                value = settings.subtitleTextSize,
                defaultValue = settings.defaultValue(settings::subtitleTextSize),
                range = 10f..40f,
                steps = 29,
            ) { settings.subtitleTextSize = it.roundToInt().toFloat() }
        }
        group("accessibility", stringResource(Res.string.lyrics_accessibility)) {
            switch(
                id = "lyricsActiveByRealTiming",
                title = stringResource(Res.string.lyrics_active_by_real_timing),
                description = stringResource(Res.string.lyrics_active_by_real_timing_description),
                checked = settings.lyricsActiveByRealTiming,
            ) { settings.lyricsActiveByRealTiming = it }
            switch(
                id = "lyricsProgressiveBlur",
                title = stringResource(Res.string.lyrics_progressive_blur),
                description = stringResource(Res.string.lyrics_progressive_blur_description),
                checked = settings.lyricsProgressiveBlur,
            ) { settings.lyricsProgressiveBlur = it }
            if (settings.playerBackgroundStyle == PlayerBackgroundStyle.LIQUID) {
                switch(
                    id = "lyricsPlusLighter",
                    title = stringResource(Res.string.lyrics_plus_lighter),
                    description = stringResource(Res.string.lyrics_plus_lighter_desc),
                    checked = settings.lyricsPlusLighter,
                ) { settings.lyricsPlusLighter = it }
            }
        }
        group("lyricsAdvanced", stringResource(Res.string.lyrics_settings_title)) {
            if (SettingsPageId.LYRICS_SOURCES in host.availablePages) {
                navigation(
                    "sources",
                    stringResource(Res.string.lyrics_source_preference),
                    SettingsPageId.LYRICS_SOURCES,
                    description = stringResource(Res.string.settings_lyrics_sources_overview),
                )
            }
            if (SettingsPageId.LYRICS_AI_TRANSLATION in host.availablePages) {
                navigation(
                    "aiTranslation",
                    stringResource(Res.string.settings_ai_lyrics_translation),
                    SettingsPageId.LYRICS_AI_TRANSLATION,
                    description = stringResource(Res.string.settings_lyrics_translation_overview),
                )
            }
            if (SettingsPageId.LYRICS_ROMANIZATION in host.availablePages) {
                navigation(
                    "romanization",
                    stringResource(Res.string.lyrics_romanize_title),
                    SettingsPageId.LYRICS_ROMANIZATION,
                    description = stringResource(Res.string.settings_lyrics_romanization_overview),
                )
            }
        }
    }

    if (SettingsPageId.LYRICS_SOURCES in host.availablePages) {
        page(
            id = SettingsPageId.LYRICS_SOURCES,
            title = stringResource(Res.string.lyrics_source_preference),
            subtitle = stringResource(Res.string.settings_lyrics_sources_overview),
            icon = MetrolistIcons.Outlined.Lyrics,
            tone = SettingsTone.ROSE,
        ) {
            group(
                id = "providers",
                title = stringResource(Res.string.lyrics_source_preference),
                description = stringResource(Res.string.drag_to_reorder),
            ) {
                custom(
                    id = "providerOrder",
                    title = stringResource(Res.string.lyrics_source_preference),
                ) {
                    LyricsProviderOrderSetting()
                }
            }
        }
    }

    if (SettingsPageId.LYRICS_AI_TRANSLATION in host.availablePages) {
        val preset = AiProviderPresets.find(settings.lyricsAiProvider) ?: AiProviderPresets.all.first()
        val provider = preset.id
        val directProvider = isDirectLyricsTranslationProvider(provider)
        val oauthConnected = preset.supportsOAuth && settings.lyricsOpenRouterOAuthKey.isNotBlank()
        val activationChoices =
            listOf(
                SettingsChoice("Automatic", stringResource(Res.string.automatic)),
                SettingsChoice("Manual", stringResource(Res.string.manual)),
            )
        val translationModeChoices =
            listOf(
                SettingsChoice("Literal", stringResource(Res.string.ai_translation_mode_literal)),
                SettingsChoice("Transcribed", stringResource(Res.string.ai_translation_mode_transcribed)),
            )
        val targetLanguageChoices = host.hostChoices[SettingsHostChoice.LYRICS_TARGET_LANGUAGE].orEmpty()
        val formalityChoices =
            listOf(
                SettingsChoice("default", stringResource(Res.string.ai_deepl_formality_default)),
                SettingsChoice("prefer_more", stringResource(Res.string.ai_deepl_formality_more)),
                SettingsChoice("prefer_less", stringResource(Res.string.ai_deepl_formality_less)),
            )
        customPage(
            id = SettingsPageId.LYRICS_AI_SETUP,
            title = stringResource(Res.string.ai_setup_title),
            subtitle = stringResource(Res.string.ai_setup_description),
            icon = MetrolistIcons.Outlined.Translate,
            tone = SettingsTone.ROSE,
        )
        page(
            id = SettingsPageId.LYRICS_AI_TRANSLATION,
            title = stringResource(Res.string.settings_ai_lyrics_translation),
            subtitle = stringResource(Res.string.settings_lyrics_translation_overview),
            icon = MetrolistIcons.Outlined.Translate,
            tone = SettingsTone.ROSE,
        ) {
            group("setup", stringResource(Res.string.ai_setup_title)) {
                navigation(
                    "aiSetup",
                    stringResource(Res.string.ai_setup_title),
                    SettingsPageId.LYRICS_AI_SETUP,
                    description = stringResource(Res.string.ai_setup_description),
                )
            }
            group("runtime", stringResource(Res.string.ai_runtime)) {
                switch(
                    id = "translationEnabled",
                    title = stringResource(Res.string.ai_translation_enable),
                    description = stringResource(Res.string.ai_translation_enable_description),
                    checked = settings.lyricsAiTranslationEnabled,
                ) { settings.lyricsAiTranslationEnabled = it }
                choice(
                    id = "activationMode",
                    title = stringResource(Res.string.ai_translation_trigger),
                    description =
                        stringResource(
                            if (settings.lyricsAiActivationMode == "Manual") {
                                Res.string.ai_translation_trigger_manual_description
                            } else {
                                Res.string.ai_translation_trigger_automatic_description
                            },
                        ),
                    valueLabel = activationChoices.firstOrNull { it.id == settings.lyricsAiActivationMode }?.label,
                    choices = activationChoices,
                    selectedChoiceId = settings.lyricsAiActivationMode,
                ) { settings.lyricsAiActivationMode = it }
                choice(
                    id = "translationMode",
                    title = stringResource(Res.string.mode),
                    valueLabel = translationModeChoices.firstOrNull { it.id == settings.lyricsAiTranslationMode }?.label,
                    choices = translationModeChoices,
                    selectedChoiceId = settings.lyricsAiTranslationMode,
                    enabled = !directProvider,
                    description = stringResource(Res.string.settings_translation_mode_summary),
                ) { settings.lyricsAiTranslationMode = it }
                if (targetLanguageChoices.isNotEmpty()) {
                    choice(
                        id = "targetLanguage",
                        title = stringResource(Res.string.ai_target_language),
                        valueLabel = targetLanguageChoices.firstOrNull { it.id == settings.lyricsAiTargetLanguage }?.label,
                        choices = targetLanguageChoices,
                        selectedChoiceId = settings.lyricsAiTargetLanguage,
                    ) { settings.lyricsAiTargetLanguage = it }
                }
            }
            group("provider", stringResource(Res.string.ai_provider)) {
                custom(
                    id = "provider",
                    title = stringResource(Res.string.ai_provider),
                    description = stringResource(Res.string.settings_translation_provider_summary),
                ) { onSettingValueChanged ->
                    AiProviderPicker(preset) { selected ->
                        settings.setLyricsAiApiKey(provider, settings.lyricsAiApiKeyFor(provider))
                        settings.lyricsAiProvider = selected.id
                        if (selected.id != "OpenRouter") settings.lyricsOpenRouterOAuthSession = ""
                        if (selected.endpoint.isNotBlank()) settings.lyricsAiBaseUrl = selected.endpoint
                        settings.lyricsAiModel = selected.defaultModel
                        settings.lyricsAiDetectorModel = ""
                        if (isDirectLyricsTranslationProvider(selected.id)) settings.lyricsAiTranslationMode = "Literal"
                        onSettingValueChanged()
                    }
                }
            }
            if (!directProvider) {
                group("connection", stringResource(Res.string.ai_connection)) {
                    if (provider == "OpenRouter") {
                        custom(id = "openRouterSignIn", title = stringResource(Res.string.openrouter_sign_in_title)) {
                            OpenRouterAuthSetting()
                        }
                    }
                    val apiKey = if (provider == "DeepL") settings.lyricsDeepLApiKey else settings.lyricsAiApiKeyFor(provider)
                    text(
                        id = "apiKey",
                        title = stringResource(if (provider == "DeepL") Res.string.deepl_api_key else Res.string.ai_api_key_label),
                        description =
                            if (oauthConnected) {
                                stringResource(Res.string.ai_openrouter_oauth_active)
                            } else {
                                stringResource(if (apiKey.isBlank()) Res.string.required else Res.string.configured)
                            },
                        value = apiKey,
                        enabled = !oauthConnected,
                        secret = true,
                    ) {
                        if (provider == "DeepL") settings.lyricsDeepLApiKey = it else settings.setLyricsAiApiKey(provider, it)
                    }
                    if (preset.editableEndpoint) {
                        text(
                            id = "baseUrl",
                            title = stringResource(Res.string.ai_base_url),
                            description = settings.lyricsAiBaseUrl,
                            value = settings.lyricsAiBaseUrl,
                            placeholder = preset.endpoint,
                        ) { settings.lyricsAiBaseUrl = it }
                    }
                    if (provider == "DeepL") {
                        choice(
                            id = "deepLFormality",
                            title = stringResource(Res.string.ai_deepl_formality),
                            valueLabel = formalityChoices.firstOrNull { it.id == settings.lyricsDeepLFormality }?.label,
                            choices = formalityChoices,
                            selectedChoiceId = settings.lyricsDeepLFormality,
                            description = stringResource(Res.string.settings_translation_formality_summary),
                        ) { settings.lyricsDeepLFormality = it }
                    }
                }
            }
            if (preset.isAi && !directProvider) {
                val apiKey = settings.lyricsAiApiKeyFor(provider)
                val modelsReady = oauthConnected || apiKey.isNotBlank() || preset.publicModelList
                val (modelKey, modelUrl) = settings.lyricsAiCredentials(provider)
                group("models", stringResource(Res.string.ai_models)) {
                    custom(
                        id = "translationModel",
                        title = stringResource(Res.string.ai_translation_model),
                        enabled = modelsReady,
                        description = stringResource(Res.string.settings_translation_model_summary),
                    ) {
                        AiModelPicker(
                            title = stringResource(Res.string.ai_translation_model),
                            description = stringResource(Res.string.settings_translation_model_summary),
                            preset = preset,
                            model = settings.lyricsAiModel,
                            apiKey = modelKey,
                            baseUrl = modelUrl,
                            allowBlank = false,
                            enabled = modelsReady,
                        ) { settings.lyricsAiModel = it }
                    }
                    if (settings.lyricsAiActivationMode == "Automatic") {
                        custom(
                            id = "detectorModel",
                            title = stringResource(Res.string.ai_judge_detector_model),
                            enabled = modelsReady,
                            description = stringResource(Res.string.settings_detector_model_summary),
                        ) {
                            AiModelPicker(
                                title = stringResource(Res.string.ai_judge_detector_model),
                                description = stringResource(Res.string.settings_detector_model_summary),
                                preset = preset,
                                model = settings.lyricsAiDetectorModel,
                                apiKey = modelKey,
                                baseUrl = modelUrl,
                                allowBlank = true,
                                enabled = modelsReady,
                            ) { settings.lyricsAiDetectorModel = it }
                        }
                    }
                }
            }
            group(
                id = "prompt",
                title = stringResource(Res.string.ai_prompt),
                description = stringResource(Res.string.ai_prompt_line_count_description),
            ) {
                text(
                    id = "systemPrompt",
                    title = stringResource(Res.string.ai_system_prompt_label),
                    description =
                        stringResource(
                            if (settings.lyricsAiSystemPrompt.isBlank()) Res.string.ai_default_json_array_prompt else Res.string.configured,
                        ),
                    value = settings.lyricsAiSystemPrompt,
                    enabled =
                        preset.isAi && !directProvider &&
                            (oauthConnected || settings.lyricsAiApiKeyFor(provider).isNotBlank() || preset.publicModelList),
                    multiline = true,
                ) { settings.lyricsAiSystemPrompt = it }
            }
            if (!directProvider) {
                group("connectionTest", stringResource(Res.string.ai_test_connection)) {
                    custom(id = "aiConnectionTest", title = stringResource(Res.string.ai_test_connection)) {
                        AiLyricsConnectionTestSetting(preset, provider, oauthConnected)
                    }
                }
            }
        }
    }

    if (SettingsPageId.LYRICS_ROMANIZATION in host.availablePages) {
        val romanizationLanguages = parseRomanizationLanguages(settings.lyricsRomanizeLanguages)
        page(
            id = SettingsPageId.LYRICS_ROMANIZATION,
            title = stringResource(Res.string.lyrics_romanize_title),
            subtitle = stringResource(Res.string.settings_lyrics_romanization_overview),
            icon = MetrolistIcons.Outlined.Translate,
            tone = SettingsTone.ROSE,
        ) {
            group("display", stringResource(Res.string.lyrics_romanization_display)) {
                switch(
                    id = "romanizationEnabled",
                    title = stringResource(Res.string.lyrics_romanization_enable),
                    description = stringResource(Res.string.lyrics_romanization_enable_description),
                    checked = settings.lyricsRomanizationEnabled,
                ) { settings.lyricsRomanizationEnabled = it }
                switch(
                    id = "romanizeAsMain",
                    title = stringResource(Res.string.lyrics_romanize_as_main),
                    description = stringResource(Res.string.lyrics_romanization_as_main_description),
                    checked = settings.lyricsRomanizeAsMain,
                ) { settings.lyricsRomanizeAsMain = it }
                switch(
                    id = "syllableRomanization",
                    title = stringResource(Res.string.lyrics_romanization_syllable_title),
                    description = stringResource(Res.string.lyrics_romanization_syllable_description),
                    checked = settings.lyricsSyllableRomanizationEnabled,
                ) { settings.lyricsSyllableRomanizationEnabled = it }
                switch(
                    id = "cyrillicPerLine",
                    title = stringResource(Res.string.lyrics_romanization_detect_cyrillic_per_line),
                    description = stringResource(Res.string.lyrics_romanization_detect_cyrillic_per_line_description),
                    checked = settings.lyricsRomanizeCyrillicByLine,
                ) { settings.lyricsRomanizeCyrillicByLine = it }
            }
            group(
                id = "languages",
                title = stringResource(Res.string.languages),
                description = stringResource(Res.string.lyrics_romanization_languages_description),
            ) {
                romanizationLanguages.forEach { (language, checked) ->
                    switch(
                        id = "romanize:$language",
                        title = romanizationLanguageLabel(language),
                        checked = checked,
                    ) { enabled ->
                        settings.lyricsRomanizeLanguages =
                            romanizationLanguages
                                .toMutableMap()
                                .apply { this[language] = enabled }
                                .entries
                                .joinToString(",") { (name, selected) -> "$name:$selected" }
                    }
                }
            }
        }
    }
}
