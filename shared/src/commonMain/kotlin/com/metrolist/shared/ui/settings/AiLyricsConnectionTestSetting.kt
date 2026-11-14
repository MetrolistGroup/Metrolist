@file:Suppress("ktlint:standard:no-wildcard-imports")

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.shared.api.lyrics.AiLyricsProbeResult
import com.metrolist.shared.api.lyrics.AiProviderPreset
import com.metrolist.shared.api.lyrics.LyricsTranslationClient
import com.metrolist.shared.generated.resources.*
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform

@Composable
internal fun AiLyricsConnectionTestSetting(
    preset: AiProviderPreset,
    provider: String,
    oauthConnected: Boolean,
) {
    key(provider) {
        ProviderConnectionTest(provider, oauthConnected)
    }
}

@Composable
private fun ProviderConnectionTest(
    provider: String,
    oauthConnected: Boolean,
) {
    val settings = Settings.shared
    val client = remember { KoinPlatform.getKoin().get<LyricsTranslationClient>() }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AiLyricsProbeResult?>(null) }
    var error by remember { mutableStateOf(false) }
    val missingCredentials = stringResource(Res.string.ai_test_missing_credentials)
    val (apiKey, baseUrl) = settings.lyricsAiCredentials(provider)
    val canRun =
        when (provider) {
            "DeepL" -> settings.lyricsDeepLApiKey.isNotBlank()
            "DeepL (keyless)", "Google Translate" -> true
            else -> oauthConnected || apiKey.isNotBlank()
        }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Text(
            stringResource(Res.string.ai_test_connection_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = {
                if (!canRun) {
                    error = true
                    result = null
                    return@TextButton
                }
                busy = true
                error = false
                result = null
                scope.launch {
                    val probe =
                        client.probeLyricsAiConnection(
                            provider = provider,
                            targetLanguage = settings.lyricsAiTargetLanguage,
                            translationMode = settings.lyricsAiTranslationMode,
                            activationMode = settings.lyricsAiActivationMode,
                            apiKey = apiKey,
                            baseUrl = baseUrl,
                            translationModel = settings.lyricsAiModel,
                            detectorModel = settings.lyricsAiDetectorModel,
                            systemPrompt = settings.lyricsAiSystemPrompt,
                            deepLApiKey = settings.lyricsDeepLApiKey,
                            deepLFormality = settings.lyricsDeepLFormality,
                        )
                    busy = false
                    probe.fold(
                        onSuccess = {
                            result = it
                            error = false
                        },
                        onFailure = {
                            result = null
                            error = true
                        },
                    )
                }
            },
            enabled = !busy,
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(stringResource(if (busy) Res.string.ai_test_connection_running else Res.string.ai_test_connection))
        }
        when {
            busy -> {
                Text(stringResource(Res.string.ai_test_connection_running), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            error && result == null -> {
                Text(
                    if (canRun) stringResource(Res.string.ai_test_connection_failed) else missingCredentials,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            result != null -> {
                AiLyricsProbeSummary(result!!)
            }
        }
    }
}

@Composable
private fun AiLyricsProbeSummary(result: AiLyricsProbeResult) {
    Column(Modifier.fillMaxWidth()) {
        result.detectionNeedsTranslation?.let { needsTranslation ->
            Text(
                stringResource(
                    Res.string.ai_test_detection_result,
                    stringResource(if (needsTranslation) Res.string.yes else Res.string.no),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        result.translatedSample?.takeIf { it.isNotBlank() }?.let {
            Text(stringResource(Res.string.ai_test_translation_result, it), style = MaterialTheme.typography.bodyMedium)
        }
        if (result.detectionNeedsTranslation == null && result.translatedSample.isNullOrBlank()) {
            Text(stringResource(Res.string.ai_test_connection_success), color = MaterialTheme.colorScheme.primary)
        }
    }
}
