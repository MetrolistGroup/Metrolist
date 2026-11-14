@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)
@file:Suppress("FunctionName", "ktlint:standard:no-wildcard-imports")

package com.metrolist.shared.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.metrolist.shared.api.lyrics.AiModelCatalog
import com.metrolist.shared.api.lyrics.AiModelInfo
import com.metrolist.shared.api.lyrics.AiProviderPresets
import com.metrolist.shared.api.lyrics.LyricsTranslationClient
import com.metrolist.shared.api.lyrics.LyricsTranslationFailure
import com.metrolist.shared.api.lyrics.lyricsTranslationFailure
import com.metrolist.shared.generated.resources.*
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.canonicalAppLanguageTag
import com.metrolist.shared.ui.components.lyrics.messageResource
import com.metrolist.shared.ui.menu.ExpressiveSelectionMenu
import com.metrolist.shared.ui.menu.trackPressPosition
import com.metrolist.shared.utils.getSystemLanguage
import com.metrolist.shared.utils.tryOpenUrl
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform

internal fun lyricsSetupLanguage(
    configured: String,
    appLanguage: String,
    systemLanguage: String,
): String {
    val requested =
        configured.takeUnless { it.isBlank() || it == "device" || it.equals("SYSTEM", ignoreCase = true) }
            ?: appLanguage.takeUnless { it.isBlank() || it.equals("SYSTEM", ignoreCase = true) }
            ?: systemLanguage
    return canonicalAppLanguageTag(requested.trim().replace('_', '-'))
}

internal suspend fun completeLyricsSetup(
    settings: Settings,
    client: LyricsTranslationClient,
    model: String,
    language: String,
): Result<Unit> {
    val key = settings.lyricsOpenRouterOAuthKey
    require(key.isNotBlank())
    require(model.endsWith(":free") && language.isNotBlank())
    val verification = client.verifyOpenRouterTranslation(key, model, language)
    currentCoroutineContext().ensureActive()
    return verification.mapCatching { verifiedModel ->
        check(settings.lyricsOpenRouterOAuthKey == key) { "OpenRouter credentials changed during verification" }
        val preset = checkNotNull(AiProviderPresets.find("OpenRouter"))
        settings.setLyricsAiApiKey(settings.lyricsAiProvider, settings.lyricsAiApiKeyFor(settings.lyricsAiProvider))
        settings.lyricsAiProvider = preset.id
        settings.lyricsAiBaseUrl = preset.endpoint
        settings.lyricsAiModel = verifiedModel
        settings.lyricsAiDetectorModel = ""
        settings.lyricsAiTargetLanguage = language
        settings.lyricsShowTranslations = true
        settings.lyricsAiTranslationEnabled = true
    }
}

@Composable
internal fun AiTranslationSetup(
    languageChoices: List<SettingsChoice>,
    onDone: () -> Unit,
) {
    val settings = Settings.shared
    val preset = remember { checkNotNull(AiProviderPresets.find("OpenRouter")) }
    val catalog = remember { KoinPlatform.getKoin().get<AiModelCatalog>() }
    val client = remember { KoinPlatform.getKoin().get<LyricsTranslationClient>() }
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var model by rememberSaveable { mutableStateOf(AiProviderPresets.FREE_LYRICS_AI_MODEL) }
    var language by rememberSaveable {
        mutableStateOf(lyricsSetupLanguage(settings.lyricsAiTargetLanguage, settings.appLanguage, getSystemLanguage()))
    }
    var reload by remember { mutableIntStateOf(0) }
    var verifying by remember { mutableStateOf(false) }
    var failure by remember(model, language, settings.lyricsOpenRouterOAuthKey) { mutableStateOf<LyricsTranslationFailure?>(null) }
    val models by produceState<List<AiModelInfo>?>(null, reload) {
        value = catalog.models(preset, "", preset.endpoint).getOrDefault(AiProviderPresets.openRouterOfflineModels)
    }
    LaunchedEffect(models) {
        models?.let { available ->
            if (!verifying && available.none { it.id == model && it.free }) {
                model = AiModelCatalog.defaultModel(preset, available, freeOnly = true).orEmpty()
            }
        }
    }
    val titles = listOf(Res.string.ai_setup_account, Res.string.ai_setup_sign_in, Res.string.ai_setup_configure)
    var browserFailed by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .padding(bottom = LocalSettingsPageBottomPadding.current),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(Res.string.ai_setup_step, step + 1, 3), style = MaterialTheme.typography.labelLarge)
        AnimatedContent(targetState = step, label = "aiTranslationSetupStep") { current ->
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(titles[current]), style = MaterialTheme.typography.headlineSmall)
                when (current) {
                    0 -> {
                        Text(stringResource(Res.string.ai_setup_account_description))
                        Text(stringResource(Res.string.ai_setup_limits, 20, 50, 10, 1000))
                        Button(
                            onClick = { tryOpenUrl("https://openrouter.ai/sign-up") { browserFailed = !it } },
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(stringResource(Res.string.ai_setup_create_account)) }
                        TextButton(
                            onClick = { tryOpenUrl("https://openrouter.ai/docs/api_reference/limits") { browserFailed = !it } },
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(stringResource(Res.string.ai_setup_view_limits)) }
                        if (browserFailed) Text(stringResource(Res.string.ai_setup_browser_failed), color = MaterialTheme.colorScheme.error)
                    }

                    1 -> {
                        OpenRouterAuthSetting()
                    }

                    2 -> {
                        Text(stringResource(Res.string.ai_setup_model_description))
                        AiModelPicker(
                            title = stringResource(Res.string.ai_translation_model),
                            preset = preset,
                            model = model,
                            apiKey = "",
                            baseUrl = preset.endpoint,
                            allowBlank = false,
                            freeOnly = true,
                            enabled = !verifying,
                        ) {
                            model = it
                            reload++
                        }
                        if (models != null && model.isBlank()) {
                            Text(stringResource(Res.string.ai_model_none_found), color = MaterialTheme.colorScheme.error)
                        }
                        SetupLanguagePicker(language, languageChoices, enabled = !verifying) { language = it }
                        Text(stringResource(Res.string.ai_setup_verify_description))
                        AnimatedVisibility(verifying) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                LoadingIndicator()
                                Text(stringResource(Res.string.ai_setup_verifying))
                            }
                        }
                        failure?.let { reason ->
                            Text(stringResource(reason.messageResource()), color = MaterialTheme.colorScheme.error)
                            Text(stringResource(reason.setupHintResource()))
                            when (reason) {
                                LyricsTranslationFailure.DATA_POLICY -> {
                                    TextButton(
                                        onClick = { tryOpenUrl("https://openrouter.ai/settings/privacy") { browserFailed = !it } },
                                        shapes = ButtonDefaults.shapes(),
                                    ) { Text(stringResource(Res.string.ai_setup_privacy_settings)) }
                                }

                                LyricsTranslationFailure.AUTHENTICATION -> {
                                    TextButton(
                                        onClick = {
                                            settings.lyricsOpenRouterOAuthKey = ""
                                            step = 1
                                        },
                                        shapes = ButtonDefaults.shapes(),
                                    ) { Text(stringResource(Res.string.ai_setup_sign_in_again)) }
                                }

                                LyricsTranslationFailure.CREDIT_LIMIT -> {
                                    TextButton(
                                        onClick = { tryOpenUrl("https://openrouter.ai/settings/keys") { browserFailed = !it } },
                                        shapes = ButtonDefaults.shapes(),
                                    ) { Text(stringResource(Res.string.ai_setup_key_limits)) }
                                }

                                else -> {
                                    Unit
                                }
                            }
                        }
                    }
                }
            }
        }
        if (step != 0 && browserFailed) Text(stringResource(Res.string.ai_setup_browser_failed), color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { if (step > 0) step-- else onDone() }, enabled = !verifying, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(if (step > 0) Res.string.back else Res.string.cancel))
            }
            Button(
                onClick = {
                    if (step < 2) {
                        step++
                    } else {
                        verifying = true
                        failure = null
                        scope.launch {
                            try {
                                completeLyricsSetup(settings, client, model, language).fold(
                                    onSuccess = { onDone() },
                                    onFailure = { failure = it.lyricsTranslationFailure() },
                                )
                            } finally {
                                verifying = false
                            }
                        }
                    }
                },
                enabled =
                    !verifying &&
                        when (step) {
                            1 -> {
                                settings.lyricsOpenRouterOAuthKey.isNotBlank()
                            }

                            2 -> {
                                settings.lyricsOpenRouterOAuthKey.isNotBlank() && language.isNotBlank() &&
                                    models?.any { it.id == model && it.free && it.id.endsWith(":free") } == true
                            }

                            else -> {
                                true
                            }
                        },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(
                    stringResource(
                        if (step == 2 &&
                            failure != null
                        ) {
                            Res.string.retry
                        } else if (step == 2) {
                            Res.string.done
                        } else {
                            Res.string.next
                        },
                    ),
                )
            }
        }
    }
}

private fun LyricsTranslationFailure.setupHintResource(): StringResource =
    when (this) {
        LyricsTranslationFailure.DATA_POLICY -> Res.string.ai_setup_privacy_hint
        LyricsTranslationFailure.AUTHENTICATION -> Res.string.ai_setup_key_hint
        LyricsTranslationFailure.CREDIT_LIMIT -> Res.string.ai_setup_credit_hint
        LyricsTranslationFailure.RATE_LIMIT -> Res.string.ai_setup_rate_hint
        LyricsTranslationFailure.FREE_DAILY_LIMIT -> Res.string.ai_setup_daily_hint
        LyricsTranslationFailure.FREE_MINUTE_LIMIT -> Res.string.ai_setup_minute_hint
        LyricsTranslationFailure.MODEL_BUSY -> Res.string.ai_setup_busy_hint
        LyricsTranslationFailure.MODEL_UNAVAILABLE -> Res.string.ai_setup_model_hint
        LyricsTranslationFailure.NETWORK, LyricsTranslationFailure.TIMEOUT -> Res.string.ai_setup_network_hint
        else -> Res.string.ai_setup_response_hint
    }

@Composable
private fun SetupLanguagePicker(
    language: String,
    choices: List<SettingsChoice>,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var offset by remember { mutableStateOf(IntOffset.Zero) }
    val available =
        choices.filterNot { it.id == "device" || it.id == "SYSTEM" }.let { languages ->
            if (languages.any { it.id == language } ||
                language.isBlank()
            ) {
                languages
            } else {
                listOf(SettingsChoice(language, language)) + languages
            }
        }
    Box(Modifier.trackPressPosition { offset = it.round() }) {
        AiPickerRow(
            stringResource(Res.string.ai_target_language),
            available
                .firstOrNull {
                    it.id == language
                }?.label
                .orEmpty(),
            enabled = enabled,
        ) {
            open =
                true
        }
        ExpressiveSelectionMenu(
            expanded = open,
            onDismissRequest = { open = false },
            anchorOffset = offset,
            options = available,
            selectedOption = available.firstOrNull { it.id == language },
            optionLabel = { it.label },
            onOptionSelected = { onSelected(it.id) },
        )
    }
}
