package com.metrolist.shared.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SongItemSerializationTest {
    @Test
    fun olderPersistedQueueItemsUseMetadataDefaults() {
        val item =
            Json.decodeFromString<SongItem>(
                """{"id":"video","title":"Track","thumbnail":null,"artists":[],"album":null}""",
            )

        assertNull(item.year)
        assertNull(item.trackNumber)
        assertNull(item.totalTrackCount)
        assertNull(item.genre)
        assertNull(item.queueSourceTitle)
        assertNull(item.artworkExplicitness)
    }

    @Test
    fun legacyExplicitQueueItemsRemainKnownExplicit() {
        val item =
            Json.decodeFromString<SongItem>(
                """{"id":"video","title":"Track","thumbnail":null,"artists":[],"album":null,"isExplicit":true}""",
            )

        assertEquals(true, item.artworkExplicitness)
    }

    @Test
    fun knownCleanQueueItemsRetainExplicitness() {
        val item = SongItem(id = "video", title = "Track", thumbnail = null, artists = emptyList(), album = null, explicitness = false)
        val restored = Json.decodeFromString<SongItem>(Json.encodeToString(item))

        assertEquals(false, restored.artworkExplicitness)
    }
}
