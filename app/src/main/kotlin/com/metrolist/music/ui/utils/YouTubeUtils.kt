/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:Suppress("LocalVariableName")

package com.metrolist.music.ui.utils

fun String.resize(
    width: Int? = null,
    height: Int? = null,
): String {
    if (width == null && height == null) return this
    // Match BOTH lh3 and yt3 googleusercontent: YouTube migrated music/album art from
    // lh3.googleusercontent.com to yt3.googleusercontent.com. Both serve the same =wW-hH resize
    // params; matching only lh3 silently no-ops on the new host, so the player upscales the raw
    // ~60px thumbnail (blurry). Verified live: a yt3 URL + =w544-h544 returns a sharp full-size image.
    "https://(?:lh3|yt3)\\.googleusercontent\\.com/.*=w(\\d+)-h(\\d+).*".toRegex()
        .matchEntire(this)?.groupValues?.let { group ->
        val (W, H) = group.drop(1).map { it.toInt() }
        var w = width
        var h = height
        if (w != null && h == null) h = (w / W) * H
        if (w == null && h != null) w = (h / H) * W
        return "${split("=w")[0]}=w$w-h$h-p-l90-rj"
    }
    if (this matches "https://yt3\\.ggpht\\.com/.*=s(\\d+)".toRegex()) {
        return "$this-s${width ?: height}"
    }
    return this
}
