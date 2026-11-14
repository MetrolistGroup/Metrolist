@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.metrolist.shared.ui.components.lyrics

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.metrolist.shared.api.lyrics.LyricsTranslationFailure
import com.metrolist.shared.generated.resources.*
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

internal fun LyricsTranslationFailure.messageResource(): StringResource =
    when (this) {
        LyricsTranslationFailure.DATA_POLICY -> Res.string.ai_translation_failed_privacy
        LyricsTranslationFailure.AUTHENTICATION -> Res.string.ai_translation_failed_key
        LyricsTranslationFailure.CREDIT_LIMIT -> Res.string.ai_translation_failed_credit
        LyricsTranslationFailure.RATE_LIMIT -> Res.string.ai_translation_failed_rate
        LyricsTranslationFailure.FREE_DAILY_LIMIT -> Res.string.ai_translation_failed_daily
        LyricsTranslationFailure.FREE_MINUTE_LIMIT -> Res.string.ai_translation_failed_minute
        LyricsTranslationFailure.MODEL_BUSY -> Res.string.ai_translation_failed_busy
        LyricsTranslationFailure.MODEL_UNAVAILABLE -> Res.string.ai_translation_failed_model
        LyricsTranslationFailure.NETWORK, LyricsTranslationFailure.TIMEOUT -> Res.string.ai_translation_failed_network
        LyricsTranslationFailure.EMPTY_RESPONSE -> Res.string.ai_translation_failed_empty
        LyricsTranslationFailure.INVALID_RESPONSE -> Res.string.ai_translation_failed_invalid
        LyricsTranslationFailure.INCOMPLETE_RESPONSE -> Res.string.ai_translation_failed_incomplete
        LyricsTranslationFailure.REQUEST_FAILED -> Res.string.ai_translation_failed
    }

@Composable
fun LyricsTranslationSnackbar(
    snackbarHostState: SnackbarHostState,
    failures: Flow<LyricsTranslationFailure>,
) {
    LaunchedEffect(snackbarHostState, failures) {
        failures.collect {
            snackbarHostState.showSnackbar(getString(it.messageResource()), duration = SnackbarDuration.Short)
        }
    }
}
