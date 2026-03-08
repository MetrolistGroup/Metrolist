package com.metrolist.shared.utils

import android.icu.text.DateTimePatternGenerator
import java.util.Locale

internal actual fun historyMonthPattern(locale: Locale): String = DateTimePatternGenerator.getInstance(locale).getBestPattern("yMMMM")
