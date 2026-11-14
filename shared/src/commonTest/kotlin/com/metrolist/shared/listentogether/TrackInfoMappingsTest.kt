package com.metrolist.shared.listentogether

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackInfoMappingsTest {
    @Test
    fun acceptsOnlyTrustedHttpsArtworkOrigins() {
        assertTrue(isAllowedRemoteArtworkUrl("https://i.ytimg.com/vi/id/default.jpg"))
        assertFalse(isAllowedRemoteArtworkUrl("http://i.ytimg.com/vi/id/default.jpg"))
        assertFalse(isAllowedRemoteArtworkUrl("https://user@example.com/image.jpg"))
        assertFalse(isAllowedRemoteArtworkUrl("https://ytimg.com.evil.example/image.jpg"))
        assertFalse(isAllowedRemoteArtworkUrl("https://127.0.0.1/image.jpg"))
    }
}
