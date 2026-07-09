/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.content.res.Configuration
import com.metrolist.music.R
import java.util.Locale

fun getArtistSeparator(context: Context): String = " ${context.getString(R.string.and)} "

fun <T> List<T>.joinToArtistString(
    conjunction: String,
    transform: (T) -> String,
): String {
    val strings = map(transform).filter { it.isNotBlank() }
    return when (strings.size) {
        0 -> ""
        1 -> strings[0]
        2 -> "${strings[0]}$conjunction${strings[1]}"
        else -> strings.dropLast(1).joinToString(", ") + "$conjunction${strings.last()}"
    }
}

fun reportException(throwable: Throwable) {
    throwable.printStackTrace()
}

@Suppress("DEPRECATION")
fun setAppLocale(context: Context, locale: Locale) {
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    context.resources.updateConfiguration(config, context.resources.displayMetrics)
}
