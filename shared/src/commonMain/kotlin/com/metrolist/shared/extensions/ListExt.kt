/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.extensions

fun <T> List<T>.reversed(reversed: Boolean) = if (reversed) asReversed() else this
