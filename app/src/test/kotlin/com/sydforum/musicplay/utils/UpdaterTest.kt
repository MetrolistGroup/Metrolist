package com.sydforum.musicplay.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdaterTest {
    @Test
    fun parsesKmpReleaseArtifact() {
        val response =
            """
            {
              "tag_name": "v1.2.3",
              "body": null,
              "published_at": "2026-09-05T12:00:00Z",
              "assets": [{
                "name": "MusicPlayer.apk",
                "browser_download_url": "https://example.com/MusicPlayer.apk",
                "size": 42
              }]
            }
            """.trimIndent()
        val release = checkNotNull(Updater.parseKmpRelease(response))

        assertEquals("1.2.3", release.versionName)
        assertEquals("", release.description)
        assertEquals("https://example.com/MusicPlayer.apk", release.assets.single().downloadUrl)
        assertNull(Updater.parseKmpRelease(response.replace("MusicPlayer.apk", "MusicPlayer-with-Google-Cast.apk")))
    }
}
