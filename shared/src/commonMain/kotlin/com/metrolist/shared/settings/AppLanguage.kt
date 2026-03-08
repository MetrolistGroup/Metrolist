package com.metrolist.shared.settings

const val HINGLISH_LANGUAGE_TAG = "hi-Latn"

fun canonicalAppLanguageTag(tag: String): String = if (tag.equals("hin", ignoreCase = true)) HINGLISH_LANGUAGE_TAG else tag
