package com.metrolist.android.locale

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.metrolist.android.BuildConfig
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.app_language_system
import com.metrolist.shared.ui.settings.SettingsChoice
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

@Composable
fun rememberAppLanguageChoices(): List<SettingsChoice> {
    val systemDefaultLabel = stringResource(Res.string.app_language_system)
    return remember(systemDefaultLabel) {
        listOf(SettingsChoice(AppLocale.SYSTEM, systemDefaultLabel)) +
            BuildConfig.BUNDLED_LANGUAGE_TAGS
                .split(',')
                .filter(String::isNotBlank)
                .map { tag ->
                    val locale = Locale.forLanguageTag(tag)
                    SettingsChoice(tag, locale.getDisplayName(locale).ifBlank { tag })
                }.sortedBy(SettingsChoice::label)
    }
}
