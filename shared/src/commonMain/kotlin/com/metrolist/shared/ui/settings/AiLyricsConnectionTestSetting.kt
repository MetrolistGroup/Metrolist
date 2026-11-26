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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.shared.api.lyrics.AiLyricsProbeResult
import com.metrolist.shared.api.lyrics.AiProviderPresets
import com.metrolist.shared.api.lyrics.LyricsTranslationClient
import com.metrolist.shared.api.lyrics.lyricsTranslationTargetLanguage
import com.metrolist.shared.generated.resources.*
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform

@Stable
internal class AiLyricsConnectionTestState(
    private val scope: CoroutineScope,
    private val client: LyricsTranslationClient,
) {
    var busy by mutableStateOf(false)
        private set
    var result by mutableStateOf<AiLyricsProbeResult?>(null)
        private set
    var error by mutableStateOf(false)
        private set

    private var probeJob: Job? = null
    private var runToken = 0

    fun failMissingCredentials() {
        runToken++
        probeJob?.cancel()
        probeJob = null
        busy = false
        error = true
        result = null
    }

    fun runProbe(
        provider: String,
        apiKey: String,
        baseUrl: String,
        settings: Settings,
    ) {
        val token = ++runToken
        probeJob?.cancel()
        busy = true
        error = false
        result = null
        probeJob =
            scope.launch {
                try {
                    val probe =
                        client.probeLyricsAiConnection(
                            provider = provider,
                            targetLanguage = lyricsTranslationTargetLanguage(),
                            translationMode = settings.lyricsAiTranslationMode,
                            activationMode = settings.lyricsAiActivationMode,
                            apiKey = apiKey,
                            baseUrl = baseUrl,
                            translationModel = settings.lyricsAiModel,
                            systemPrompt = settings.lyricsAiSystemPrompt,
                            deepLApiKey = settings.lyricsDeepLApiKey,
                            deepLFormality = settings.lyricsDeepLFormality,
                        )
                    if (runToken != token) return@launch
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
                } finally {
                    if (runToken == token) {
                        busy = false
                        probeJob = null
                    }
                }
            }
    }

    fun cancel() {
        runToken++
        probeJob?.cancel()
        probeJob = null
        busy = false
    }
}

@Composable
internal fun rememberAiLyricsConnectionTestState(provider: String): AiLyricsConnectionTestState {
    val scope = rememberCoroutineScope()
    val client = remember { KoinPlatform.getKoin().get<LyricsTranslationClient>() }
    val state = remember(provider, scope, client) { AiLyricsConnectionTestState(scope, client) }
    DisposableEffect(state) {
        onDispose { state.cancel() }
    }
    return state
}

@Composable
internal fun AiLyricsConnectionTestSetting(
    provider: String,
    oauthConnected: Boolean,
    state: AiLyricsConnectionTestState,
) {
    val settings = Settings.shared
    val missingCredentials = stringResource(Res.string.ai_test_missing_credentials)
    val (apiKey, baseUrl) = settings.lyricsAiCredentials(provider)
    val canRun =
        when (provider) {
            "DeepL" -> settings.lyricsDeepLApiKey.isNotBlank()
            "DeepL (keyless)", "Google Translate" -> true
            else -> oauthConnected || apiKey.isNotBlank() || AiProviderPresets.find(provider)?.keyless == true
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
                    state.failMissingCredentials()
                    return@TextButton
                }
                state.runProbe(provider, apiKey, baseUrl, settings)
            },
            enabled = !state.busy,
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(stringResource(if (state.busy) Res.string.ai_test_connection_running else Res.string.ai_test_connection))
        }
        when {
            state.busy -> {
                Text(stringResource(Res.string.ai_test_connection_running), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            state.error && state.result == null -> {
                Text(
                    if (canRun) stringResource(Res.string.ai_test_connection_failed) else missingCredentials,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            state.result != null -> {
                AiLyricsProbeSummary(state.result!!)
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
