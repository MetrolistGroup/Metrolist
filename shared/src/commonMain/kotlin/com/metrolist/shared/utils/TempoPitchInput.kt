package com.metrolist.shared.utils

import kotlin.math.roundToInt

internal fun parseTempoPitchInput(
    input: String,
    range: ClosedFloatingPointRange<Float>,
    decimalSeparator: Char = localeDecimalSeparator(),
): Float? {
    val normalized =
        buildString {
            for (character in input.trim()) {
                append(
                    when {
                        character == decimalSeparator -> '.'
                        character == '+' || character == '-' -> character
                        character.digitToIntOrNull() != null -> character.digitToInt()
                        else -> return null
                    },
                )
            }
        }
    val value = normalized.toDoubleOrNull() ?: return null
    if (!value.isFinite() || value < range.start.toDouble() || value > range.endInclusive.toDouble()) return null
    return value.toFloat()
}

internal fun stepTempoPitchValue(
    value: Float,
    delta: Float,
    range: ClosedFloatingPointRange<Float>,
): Float = ((value + delta) * 100).roundToInt().div(100f).coerceIn(range)
