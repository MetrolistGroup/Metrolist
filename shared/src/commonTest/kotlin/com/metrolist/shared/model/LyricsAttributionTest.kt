package com.metrolist.shared.model

import com.metrolist.shared.api.lyrics.MlxParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LyricsAttributionTest {
    @Test
    fun directUnisonAndLyprioUnisonLabelsHaveTheSameLinks() {
        for (provider in listOf("Unison", "Lyprio/UNISON", "lyprio/unison")) {
            assertEquals("https://unison.betterlyrics.org/song/Ugr4KKK_SqY", lyricsProviderUrl(provider, "Ugr4KKK_SqY"))
            assertEquals("https://unison.betterlyrics.org/curator/curator_1", lyricsCuratorUrl(provider, "curator_1"))
        }
    }

    @Test
    fun lyprioMlxSourceMetadataLinksBothLiveAndOfflineUnisonAttribution() {
        for (subprovider in listOf("UNISON", "UNISON_OFFLINE")) {
            val document = attributionDocument("Unison", subprovider)
            assertEquals("boidu", document.meta.lyricsCurator)
            for (provider in listOf("Lyprio", lyricsProviderLabel("Lyprio", document))) {
                assertEquals(
                    "https://unison.betterlyrics.org/song/Ugr4KKK_SqY",
                    lyricsProviderUrl(provider, document.meta.sourceId, document.meta.lyricSource),
                )
                assertEquals(
                    "https://unison.betterlyrics.org/curator/curator_1",
                    lyricsCuratorUrl(provider, document.meta.lyricsCuratorId, document.meta.lyricSource),
                )
            }
        }
    }

    @Test
    fun otherLyprioSourcesDoNotGetUnisonLinks() {
        val document = attributionDocument("Other source", "OTHER")
        for (provider in listOf("Lyprio", lyricsProviderLabel("Lyprio", document), null)) {
            assertNull(lyricsProviderUrl(provider, document.meta.sourceId, document.meta.lyricSource))
            assertNull(lyricsCuratorUrl(provider, document.meta.lyricsCuratorId, document.meta.lyricSource))
            assertNull(lyricsProviderUrl(provider, document.meta.sourceId))
            assertNull(lyricsCuratorUrl(provider, document.meta.lyricsCuratorId))
        }
    }

    @Test
    fun unisonLinksStillRejectMissingAndInvalidIds() {
        for (provider in listOf("Unison", "Lyprio/UNISON", "Lyprio")) {
            for (id in listOf(null, "", "../curator", "id?query")) {
                assertNull(lyricsProviderUrl(provider, id, "Unison"))
                assertNull(lyricsCuratorUrl(provider, id, "Unison"))
            }
            assertNull(lyricsProviderUrl(provider, "short", "Unison"))
        }
    }

    private fun attributionDocument(
        source: String,
        subprovider: String,
    ): MlxDocument =
        MlxParser.parse(
            """
            {
              "version": 2,
              "provider": "LYPRIO",
              "subprovider": "$subprovider",
              "source": {
                "name": "$source",
                "id": "Ugr4KKK_SqY",
                "lyricsCurator": "boidu",
                "lyricsCuratorId": "curator_1"
              },
              "lines": [{"startMs": 1000, "text": "Hello"}]
            }
            """.trimIndent(),
        )
}
