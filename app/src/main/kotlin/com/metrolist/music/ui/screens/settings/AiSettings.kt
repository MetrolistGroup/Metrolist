/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.AiProviderKey
import com.metrolist.music.constants.AutoTranslateLyricsKey
import com.metrolist.music.constants.AutoTranslateLyricsMismatchKey
import com.metrolist.music.constants.LanguageCodeToName
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.OpenRouterBaseUrlKey
import com.metrolist.music.constants.OpenRouterModelKey
import com.metrolist.music.constants.TranslateLanguageKey
import com.metrolist.music.constants.TranslateModeKey
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.EditTextPreference
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.utils.rememberPreference
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    var aiProvider by rememberPreference(AiProviderKey, "OpenRouter")
    var openRouterApiKey by rememberPreference(OpenRouterApiKey, "")
    var openRouterBaseUrl by rememberPreference(OpenRouterBaseUrlKey, "https://openrouter.ai/api/v1/chat/completions")
    var openRouterModel by rememberPreference(OpenRouterModelKey, "mistralai/mistral-small-3.1-24b-instruct:free")
    var autoTranslateLyrics by rememberPreference(AutoTranslateLyricsKey, false)
    var autoTranslateLyricsMismatch by rememberPreference(AutoTranslateLyricsMismatchKey, false)
    var translateLanguage by rememberPreference(TranslateLanguageKey, "en")
    var translateMode by rememberPreference(TranslateModeKey, "Literal")

    val aiProviders = mapOf(
        "OpenRouter" to "https://openrouter.ai/api/v1/chat/completions",
        "OpenAI" to "https://api.openai.com/v1/chat/completions",
        "Perplexity" to "https://api.perplexity.ai/chat/completions",
        "Claude" to "https://api.anthropic.com/v1/messages",
        "Gemini" to "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        "XAi" to "https://api.x.ai/v1/chat/completions",
        "Custom" to ""
    )

    val modelsByProvider = mapOf(
        "OpenRouter" to listOf(
            "google/gemini-3-flash-preview",
            "x-ai/grok-4.1-fast",
            "deepseek/deepseek-v3.1-terminus:exacto",
            "openai/gpt-oss-120b",
            "google/gemini-2.5-flash-lite",
            "google/gemini-2.5-flash",
            "openai/gpt-4o-mini"
        ),
        "OpenAI" to listOf(
            "gpt-5-mini-2025-08-07",
            "gpt-5.2-2025-12-11",
            "gpt-5-nano-2025-08-07"
        ),
        "Claude" to listOf(
            "claude-haiku-4-5",
            "claude-sonnet-4-5",
            "claude-opus-4-6"
        ),
        "Gemini" to listOf(
            "gemini-3-pro-preview",
            "gemini-3-flash-preview",
            "gemini-2.5-pro",
            "gemini-flash-latest",
            "gemini-flash-lite-latest",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
        ),
        "Perplexity" to listOf(
            "sonar-pro",
            "sonar",
            "sonar-reasoning",
            "sonar-reasoning-pro",
            "sonar-deep-research"
        ),
        "XAi" to listOf(
            "grok-4-1-fast-non-reasoning",
            "grok-4-1-fast-reasoning",
            "grok-4-fast-reasoning",
            "grok-4-fast-non-reasoning",
        ),
        "Custom" to listOf()
    )

    val commonModels = modelsByProvider[aiProvider] ?: listOf()

    var showProviderDialog by rememberSaveable { mutableStateOf(false) }
    var showTranslateModeDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showBaseUrlDialog by rememberSaveable { mutableStateOf(false) }
    var showModelDialog by rememberSaveable { mutableStateOf(false) }
    var showCustomModelInput by rememberSaveable { mutableStateOf(false) }

    if (showProviderDialog) {
        EnumDialog(
            onDismiss = { showProviderDialog = false },
            onSelect = {
                aiProvider = it
                if (it != "Custom") {
                    openRouterBaseUrl = aiProviders[it] ?: ""
                } else {
                    openRouterBaseUrl = ""
                }
                // Set model to first available model for the selected provider
                val modelsForProvider = modelsByProvider[it] ?: listOf()
                openRouterModel = if (modelsForProvider.isNotEmpty()) {
                    modelsForProvider[0]
                } else {
                    ""
                }
                showProviderDialog = false
            },
            title = stringResource(R.string.ai_provider),
            current = aiProvider,
            values = aiProviders.keys.toList(),
            valueText = { it }
        )
    }

    if (showTranslateModeDialog) {
        EnumDialog(
            onDismiss = { showTranslateModeDialog = false },
            onSelect = {
                translateMode = it
                showTranslateModeDialog = false
            },
            title = stringResource(R.string.ai_translation_mode),
            current = translateMode,
            values = listOf("Literal", "Transcribed"),
            valueText = {
                when (it) {
                    "Literal" -> stringResource(R.string.ai_translation_literal)
                    "Transcribed" -> stringResource(R.string.ai_translation_transcribed)
                    else -> it
                }
            }
        )
    }

    if (showLanguageDialog) {
        EnumDialog(
            onDismiss = { showLanguageDialog = false },
            onSelect = {
                translateLanguage = it
                showLanguageDialog = false
            },
            title = stringResource(R.string.ai_target_language),
            current = translateLanguage,
            values = LanguageCodeToName.keys.sortedBy { LanguageCodeToName[it] },
            valueText = { LanguageCodeToName[it] ?: it }
        )
    }

    if (showApiKeyDialog) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.ai_api_key)) },
            icon = { Icon(painterResource(R.drawable.key), null) },
            initialTextFieldValue = TextFieldValue(text = openRouterApiKey),
            onDone = {
                openRouterApiKey = it
                showApiKeyDialog = false
            },
            onDismiss = { showApiKeyDialog = false }
        )
    }

    if (showBaseUrlDialog && aiProvider == "Custom") {
        TextFieldDialog(
            title = { Text(stringResource(R.string.ai_base_url)) },
            icon = { Icon(painterResource(R.drawable.link), null) },
            initialTextFieldValue = TextFieldValue(text = openRouterBaseUrl),
            onDone = {
                openRouterBaseUrl = it
                showBaseUrlDialog = false
            },
            onDismiss = { showBaseUrlDialog = false }
        )
    }

    if (showModelDialog) {
        var tempModel by remember { mutableStateOf(openRouterModel) }
        EnumDialog(
            onDismiss = { showModelDialog = false },
            onSelect = {
                if (it == "custom_input") {
                    showCustomModelInput = true
                    showModelDialog = false
                } else {
                    openRouterModel = it
                    showModelDialog = false
                }
            },
            title = stringResource(R.string.ai_model),
            current = if (openRouterModel in commonModels) openRouterModel else "custom_input",
            values = commonModels + "custom_input",
            valueText = { 
                if (it == "custom_input") "Custom" else it
            }
        )
    }

    if (showCustomModelInput) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.ai_model)) },
            icon = { Icon(painterResource(R.drawable.discover_tune), null) },
            initialTextFieldValue = TextFieldValue(text = openRouterModel),
            onDone = {
                openRouterModel = it
                showCustomModelInput = false
            },
            onDismiss = { showCustomModelInput = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_lyrics_translation)) },
                navigationIcon = {
                    androidx.compose.material3.IconButton(onClick = { navController.navigateUp() }) {
                        androidx.compose.material3.Icon(
                            painterResource(R.drawable.arrow_back),
                            contentDescription = null
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                scrollBehavior = scrollBehavior
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom))
                .padding(horizontal = 16.dp)
        ) {
            Material3SettingsGroup(
                title = stringResource(R.string.ai_provider),
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.explore_outlined),
                        title = { Text(stringResource(R.string.ai_provider)) },
                        description = { Text(aiProvider) },
                        onClick = { showProviderDialog = true }
                    ),
                    if (aiProvider == "Custom") {
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.link),
                            title = { Text(stringResource(R.string.ai_base_url)) },
                            description = { Text(openRouterBaseUrl.ifBlank { stringResource(R.string.not_set) }) },
                            onClick = { showBaseUrlDialog = true }
                        )
                    } else {
                        null
                    }
                ).filterNotNull()
            )

            Spacer(modifier = Modifier.height(27.dp))

            Material3SettingsGroup(
                title = stringResource(R.string.ai_setup_guide),
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.key),
                        title = { Text(stringResource(R.string.ai_api_key)) },
                        description = { 
                            Text(
                                if (openRouterApiKey.isNotEmpty()) 
                                    "•".repeat(minOf(openRouterApiKey.length, 8))
                                else 
                                    stringResource(R.string.not_set)
                            )
                        },
                        onClick = { showApiKeyDialog = true }
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.discover_tune),
                        title = { Text(stringResource(R.string.ai_model)) },
                        description = { Text(openRouterModel.ifBlank { stringResource(R.string.not_set) }) },
                        onClick = { showModelDialog = true }
                    )
                )
            )

            Spacer(modifier = Modifier.height(27.dp))

            Material3SettingsGroup(
                title = stringResource(R.string.ai_auto_translate),
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.translate),
                        title = { Text(stringResource(R.string.ai_auto_translate)) },
                        description = { Text(stringResource(R.string.ai_auto_translate)) },
                        trailingContent = {
                            Switch(
                                checked = autoTranslateLyrics,
                                onCheckedChange = { autoTranslateLyrics = it },
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (autoTranslateLyrics) R.drawable.check else R.drawable.close
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        },
                        onClick = { autoTranslateLyrics = !autoTranslateLyrics }
                    )
                ).let { items ->
                    if (autoTranslateLyrics) {
                        items + listOf(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.info),
                                title = { Text(stringResource(R.string.ai_language_mismatch)) },
                                description = { Text(stringResource(R.string.ai_language_mismatch_desc)) },
                                trailingContent = {
                                    Switch(
                                        checked = autoTranslateLyricsMismatch,
                                        onCheckedChange = { autoTranslateLyricsMismatch = it },
                                        thumbContent = {
                                            Icon(
                                                painter = painterResource(
                                                    id = if (autoTranslateLyricsMismatch) R.drawable.check else R.drawable.close
                                                ),
                                                contentDescription = null,
                                                modifier = Modifier.size(SwitchDefaults.IconSize)
                                            )
                                        }
                                    )
                                },
                                onClick = { autoTranslateLyricsMismatch = !autoTranslateLyricsMismatch }
                            )
                        )
                    } else {
                        items
                    }
                }
            )

            Spacer(modifier = Modifier.height(27.dp))

            Material3SettingsGroup(
                title = stringResource(R.string.ai_translation_mode),
                items = listOf(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.translate),
                        title = { Text(stringResource(R.string.ai_translation_mode)) },
                        description = {
                            Text(
                                when (translateMode) {
                                    "Literal" -> stringResource(R.string.ai_translation_literal)
                                    "Transcribed" -> stringResource(R.string.ai_translation_transcribed)
                                    else -> translateMode
                                }
                            )
                        },
                        onClick = { showTranslateModeDialog = true }
                    ),
                    if (!autoTranslateLyricsMismatch || !autoTranslateLyrics) {
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.language),
                            title = { Text(stringResource(R.string.ai_target_language)) },
                            description = { Text(LanguageCodeToName[translateLanguage] ?: translateLanguage) },
                            onClick = { showLanguageDialog = true }
                        )
                    } else {
                        null
                    }
                ).filterNotNull()
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
