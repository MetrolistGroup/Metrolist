package com.metrolist.shared.youtube.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class OriginalAudioTrackPolicyTest {
    @Test
    fun originalAndDubbedMetadataWorksForDirectAndSabrStreams() {
        val cases =
            listOf(
                Triple("https://example.com/audio?xtags=acont%3Doriginal%3Alang%3Den-US", null, true),
                Triple("https://example.com/audio?xtags=acont%3Ddubbed%3Alang%3Des", null, false),
                Triple("https://example.com/audio?xtags=ChEKBWFjb250EghvcmlnaW5hbAoNCgRsYW5nEgVlbi1VUw", null, true),
                Triple("https://example.com/audio", "Cg8KBWFjb250EgZkdWJiZWQKDQoEbGFuZxIFZXMtRVM", false),
                Triple("https://example.com/audio", "acont=dubbed:lang=es", false),
                Triple("https://example.com/audio", null, true),
            )
        for ((url, tags, expected) in cases) {
            assertEquals(expected, usesOriginalAudioTrack(url, tags), "url=$url, tags=$tags")
        }
    }
}
