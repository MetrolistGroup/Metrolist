package com.metrolist.music.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeUtilsTest {

    @Test
    fun `lh3 googleusercontent is rewritten to the requested size`() {
        val url = "https://lh3.googleusercontent.com/abc=w120-h120-l90-rj"
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w544-h544-p-l90-rj",
            url.resize(544, 544),
        )
    }

    @Test
    fun `yt3 googleusercontent is rewritten too (YouTube migrated host - fixes the blurry player)`() {
        // YT moved album art to yt3.googleusercontent.com and serves a ~60px thumbnail; without
        // matching this host, resize() would no-op and the player upscales the 60px image → blur.
        val url = "https://yt3.googleusercontent.com/_zDuDZFnSKmuQwDX=w60-h60-l90-rj"
        assertEquals(
            "https://yt3.googleusercontent.com/_zDuDZFnSKmuQwDX=w544-h544-p-l90-rj",
            url.resize(544, 544),
        )
    }

    @Test
    fun `i_ytimg url keeps the thumbnail variant provided by YouTube`() {
        // maxresdefault is optional and returns 404 for videos that do not publish one.
        val url = "https://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg?sqp=-oaymwE"
        assertEquals(url, url.resize(1080, 1080))
    }

    @Test
    fun `single dimension preserves googleusercontent aspect ratio`() {
        val url = "https://lh3.googleusercontent.com/abc=w120-h60-l90-rj"
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w300-h150-p-l90-rj",
            url.resize(width = 300),
        )
    }

    @Test
    fun `null dimensions return the url unchanged`() {
        val url = "https://yt3.googleusercontent.com/abc=w60-h60-l90-rj"
        assertEquals(url, url.resize())
    }

    @Test
    fun `ggpht avatar uses w-h-p format when both dimensions requested`() {
        val url = "https://yt3.ggpht.com/abc=s88"
        assertEquals(
            "https://yt3.ggpht.com/abc=w544-h544-p-l90-rj",
            url.resize(544, 544),
        )
    }

    @Test
    fun `googleusercontent resize preserves query parameters`() {
        val url = "https://lh3.googleusercontent.com/abc=w120-h120-l90-rj?token=a=b"
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w544-h544-p-l90-rj?token=a=b",
            url.resize(544, 544),
        )
    }

    @Test
    fun `ggpht resize preserves query parameters`() {
        val url = "https://yt3.ggpht.com/abc=s88?token=a=b"
        assertEquals(
            "https://yt3.ggpht.com/abc=w544-h544-p-l90-rj?token=a=b",
            url.resize(544, 544),
        )
    }

    @Test
    fun `ggpht query without resize suffix remains unchanged`() {
        val url = "https://yt3.ggpht.com/abc?token=a=b"
        assertEquals(url, url.resize(544, 544))
    }
}
