package com.metrolist.music.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeUtilsTest {

    @Test
    fun `lh3 googleusercontent is rewritten to the requested size`() {
        val url = "https://lh3.googleusercontent.com/abc=w120-h120-l90-rj"
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w544-h544-l90-rj",
            url.resize(544, 544),
        )
    }

    @Test
    fun `yt3 googleusercontent is rewritten too (YouTube migrated host - fixes the blurry player)`() {
        // YT moved album art to yt3.googleusercontent.com and serves a ~60px thumbnail; without
        // matching this host, resize() would no-op and the player upscales the 60px image → blur.
        val url = "https://yt3.googleusercontent.com/_zDuDZFnSKmuQwDX=w60-h60-l90-rj"
        assertEquals(
            "https://yt3.googleusercontent.com/_zDuDZFnSKmuQwDX=w544-h544-l90-rj",
            url.resize(544, 544),
        )
    }

    @Test
    fun `i_ytimg url is upgraded to maxresdefault when requesting large size`() {
        val url = "https://i.ytimg.com/vi/abc/hqdefault.jpg?sqp=-oaymwE"
        assertEquals(
            "https://i.ytimg.com/vi/abc/maxresdefault.jpg?sqp=-oaymwE",
            url.resize(544, 544),
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
}
