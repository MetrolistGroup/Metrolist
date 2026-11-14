package com.metrolist.shared.utils

private val playbackCountLabel = Regex("[\\s\u00a0\u202f]+(?:views?|plays?)\\s*$", RegexOption.IGNORE_CASE)

fun String.withoutPlaybackCountLabel(): String = replace(playbackCountLabel, "").trim()
