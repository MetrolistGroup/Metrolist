/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.AiProviderKey
import com.metrolist.music.constants.DeeplApiKey
import com.metrolist.music.constants.DeeplFormalityKey
import com.metrolist.music.constants.LanguageCodeToName
import com.metrolist.music.constants.OpenRouterApiKey
import com.metrolist.music.constants.OpenRouterBaseUrlKey
import com.metrolist.music.constants.OpenRouterModelKey
import com.metrolist.music.constants.TranslateLanguageKey
import com.metrolist.music.constants.TranslateModeKey
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    var aiProvider by rememberPreference(AiProviderKey, "OpenRouter")
    var openRouterApiKey by rememberPreference(OpenRouterApiKey, "")
    var openRouterBaseUrl by rememberPreference(OpenRouterBaseUrlKey, "https://openrouter.ai/api/v1/chat/completions")
    var openRouterModel by rememberPreference(OpenRouterModelKey, "google/gemini-2.5-flash-lite")
    var translateLanguage by rememberPreference(TranslateLanguageKey, "en")
    var translateMode by rememberPreference(TranslateModeKey, "Literal")
    var deeplApiKey by rememberPreference(DeeplApiKey, "")
    var deeplFormality by rememberPreference(DeeplFormalityKey, "default")

    val aiProviders = mapOf(
        "OpenRouter" to "https://openrouter.ai/api/v1/chat/completions",
        "OpenAI" to "https://api.openai.com/v1/chat/completions",
        "Perplexity" to "https://api.perplexity.ai/chat/completions",
        "Claude" to "https://api.anthropic.com/v1/messages",
        "Gemini" to "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        "XAi" to "https://api.x.ai/v1/chat/completions",
        "DeepL" to "https://api.deepl.com/v2/translate",
        "Custom" to ""
    )

    val providerHelpText = mapOf(
        "OpenRouter" to stringResource(R.string.ai_provider_openrouter_help),
        "OpenAI" to stringResource(R.string.ai_provider_openai_help),
        "Perplexity" to stringResource(R.string.ai_provider_perplexity_help),
        "Claude" to stringResource(R.string.ai_provider_claude_help),
        "Gemini" to stringResource(R.string.ai_provider_gemini_help),
        "XAi" to stringResource(R.string.ai_provider_xai_help),
        "DeepL" to stringResource(R.string.ai_provider_deepl_help),
        "Custom" to ""
    )

    val modelsByProvider = mapOf(
        "OpenRouter" to listOf(
            "google/gemini-2.5-flash-lite",
            "google/gemini-2.5-flash",
            "x-ai/grok-4.1-fast",
            "deepseek/deepseek-v3.1-terminus:exacto",
            "openai/gpt-4o-mini",
            "google/gemini-3-flash-preview"
        ),
        "OpenAI" to listOf(
            "gpt-4o-mini",
            "gpt-4o",
            "gpt-4-turbo"
        ),
        "Claude" to listOf(
            "claude-3-5-haiku-latest",
            "claude-3-5-sonnet-latest",
            "claude-3-opus-latest"
        ),
        "Gemini" to listOf(
            "gemini-2.5-flash-lite-latest",
            "gemini-2.5-flash-latest",
            "gemini-2.5-pro-latest"
        ),
        "Perplexity" to listOf(
            "sonar",
            "sonar-pro",
            "sonar-reasoning"
        ),
        "XAi" to listOf(
            "grok-4-1-fast",
            "grok-vision-beta"
        ),
        "DeepL" to listOf(),
        "Custom" to listOf()
    )

    val commonModels = modelsByProvider[aiProvider] ?: listOf()

    var showProviderDialog by rememberSaveable { mutableStateOf(false) }
    var showProviderHelpDialog by rememberSaveable { mutableStateOf(false) }
    var showTranslateModeDialog by rememberSaveable { mutableStateOf(false) }
    var showTranslateModeHelpDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showDeeplApiKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showDeeplFormalityDialog by rememberSaveable { mutableStateOf(false) }
    var showBaseUrlDialog by rememberSaveable { mutableStateOf(false) }
    var showModelDialog by rememberSaveable { mutableStateOf(false) }
    var showCustomModelInput by rememberSaveable { mutableStateOf(false) }

    if (showProviderHelpDialog) {
        AlertDialog(
            onDismissRequest = { showProviderHelpDialog = false },
            confirmButton = {
                TextButton(onClick = { showProviderHelpDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            icon = { Icon(painterResource(R.drawable.info), null) },
            title = { Text(stringResource(R.string.ai_provider_help)) },
            text = { 
                Column {
                    providerHelpText.forEach { (provider, help) ->
                        if (help.isNotEmpty()) {
                            Text(
                                text = "$provider: $help",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        )
    }

    if (showTranslateModeHelpDialog) {
        AlertDialog(
            onDismissRequest = { showTranslateModeHelpDialog = false },
            confirmButton = {
                TextButton(onClick = { showTranslateModeHelpDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            icon = { Icon(painterResource(R.drawable.info), null) },
            title = { Text(stringResource(R.string.ai_translation_mode)) },
            text = { 
                Column {
                    Text(
                        text = "${stringResource(R.string.ai_translation_literal)}:",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        text = stringResource(R.string.ai_translation_literal_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    Text(
                        text = "${stringResource(R.string.ai_translation_transcribed)}:",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(R.string.ai_translation_transcribed_desc),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        )
    }

    if (showProviderDialog) {
        EnumDialog(
            onDismiss = { showProviderDialog = false },
            onSelect = {
                aiProvider = it
                if (it != "Custom" && it != "DeepL") {
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

    if (showDeeplApiKeyDialog) {
        TextFieldDialog(
            title = { Text("DeepL ${stringResource(R.string.ai_api_key)}") },
            icon = { Icon(painterResource(R.drawable.key), null) },
            initialTextFieldValue = TextFieldValue(text = deeplApiKey),
            onDone = {
                deeplApiKey = it
                showDeeplApiKeyDialog = false
            },
            onDismiss = { showDeeplApiKeyDialog = false }
        )
    }

    if (showDeeplFormalityDialog) {
        EnumDialog(
            onDismiss = { showDeeplFormalityDialog = false },
            onSelect = {
                deeplFormality = it
                showDeeplFormalityDialog = false
            },
            title = stringResource(R.string.ai_deepl_formality),
            current = deeplFormality,
            values = listOf("default", "more", "less"),
            valueText = {
                when (it) {
                    "default" -> stringResource(R.string.ai_deepl_formality_default)
                    "more" -> stringResource(R.string.ai_deepl_formality_more)
                    "less" -> stringResource(R.string.ai_deepl_formality_less)
                    else -> it
                }
            }
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

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top
                )
            )
        )
        
        Material3SettingsGroup(
            title = stringResource(R.string.ai_provider),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.explore_outlined),
                    title = { Text(stringResource(R.string.ai_provider)) },
                    description = { Text(aiProvider) },
                    onClick = { showProviderDialog = true },
                    trailingContent = {
                        IconButton(onClick = { showProviderHelpDialog = true }) {
                            Icon(
                                painterResource(R.drawable.info),
                                contentDescription = stringResource(R.string.ai_provider_help),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
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
            items = buildList {
                if (aiProvider == "DeepL") {
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.key),
                            title = { Text("DeepL ${stringResource(R.string.ai_api_key)}") },
                            description = { 
                                Text(
                                    if (deeplApiKey.isNotEmpty()) 
                                        "•".repeat(minOf(deeplApiKey.length, 8))
                                    else 
                                        stringResource(R.string.not_set)
                                )
                            },
                            onClick = { showDeeplApiKeyDialog = true }
                        )
                    )
                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.tune),
                            title = { Text(stringResource(R.string.ai_deepl_formality)) },
                            description = { 
                                Text(
                                    when (deeplFormality) {
                                        "default" -> stringResource(R.string.ai_deepl_formality_default)
                                        "more" -> stringResource(R.string.ai_deepl_formality_more)
                                        "less" -> stringResource(R.string.ai_deepl_formality_less)
                                        else -> deeplFormality
                                    }
                                )
                            },
                            onClick = { showDeeplFormalityDialog = true }
                        )
                    )
                } else {
                    add(
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
                        )
                    )
                    if (aiProvider != "Custom") {
                        add(
                            Material3SettingsItem(
                                icon = painterResource(R.drawable.discover_tune),
                                title = { Text(stringResource(R.string.ai_model)) },
                                description = { Text(openRouterModel.ifBlank { stringResource(R.string.not_set) }) },
                                onClick = { showModelDialog = true }
                            )
                        )
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.ai_translation_mode),
            items = buildList {
                if (aiProvider != "DeepL") {
                    add(
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
                            onClick = { showTranslateModeDialog = true },
                            trailingContent = {
                                IconButton(onClick = { showTranslateModeHelpDialog = true }) {
                                    Icon(
                                        painterResource(R.drawable.info),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        )
                    )
                }
                add(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.language),
                        title = { Text(stringResource(R.string.ai_target_language)) },
                        description = { Text(LanguageCodeToName[translateLanguage] ?: translateLanguage) },
                        onClick = { showLanguageDialog = true }
                    )
                )
            }
        )

        Spacer(modifier = Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.ai_lyrics_translation)) },
        navigationIcon = {
            IconButton(onClick = { navController.navigateUp() }) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null
                )
            }
        }
    )
}
