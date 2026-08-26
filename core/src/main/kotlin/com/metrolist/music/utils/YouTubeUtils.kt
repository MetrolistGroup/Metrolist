package com.metrolist.music.utils

import java.util.regex.Pattern

object YouTubeUtils {
    private val VIDEO_ID_PATTERN = Pattern.compile("(?<=v=)[a-zA-Z0-9_-]+(?=&|$)")
    private val VIDEO_ID_PATTERN_SHORT = Pattern.compile("(?<=youtu.be/)[a-zA-Z0-9_-]+")

    fun extractVideoId(url: String): String? {
        val matcher = VIDEO_ID_PATTERN.matcher(url)
        if (matcher.find()) return matcher.group()
        val matcherShort = VIDEO_ID_PATTERN_SHORT.matcher(url)
        if (matcherShort.find()) return matcherShort.group()
        return null
    }
}

/**
 * Resizes a YouTube thumbnail URL to the specified width and height.
 */
fun String.resize(width: Int, height: Int = width): String {
    return if (this.contains("googleusercontent.com") || this.contains("ggpht.com")) {
        val base = this.substringBeforeLast("=")
        "$base=w$width-h$height-p-l90-rj"
    } else {
        this
    }
}
