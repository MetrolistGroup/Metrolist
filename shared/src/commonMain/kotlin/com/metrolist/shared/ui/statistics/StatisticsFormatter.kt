package com.metrolist.shared.ui.statistics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLocale
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTube
import com.metrolist.shared.ui.theme.currentAppLocaleList
import kotlinx.datetime.LocalDate

internal expect class StatisticsFormatter(
    languageTag: String? = null,
) {
    fun number(value: Long): String

    fun date(value: LocalDate): String

    fun weekday(value: LocalDate): String

    fun month(value: LocalDate): String

    fun hour(value: Int): String
}

@Composable
internal fun rememberStatisticsFormatter(): StatisticsFormatter {
    val language = Settings.shared.appLanguage
    val languageTag =
        if (language.isBlank() || language.equals(GlobalYouTube.SYSTEM, ignoreCase = true)) {
            null
        } else {
            currentAppLocaleList()[0].toLanguageTag()
        }
    return remember(LocalLocale.current, languageTag) { StatisticsFormatter(languageTag) }
}
