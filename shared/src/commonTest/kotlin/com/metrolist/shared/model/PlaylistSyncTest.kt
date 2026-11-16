package com.metrolist.shared.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistSyncTest {
    @Test
    fun syncIncludesAlbumPlaylistsAndExcludesDynamicOrLocalCollections() {
        for (id in listOf("PLremote", "VLPLremote", "OLAK5uy_album", "VLOLAK5uy_album")) {
            assertTrue(id.isSyncablePlaylistId(), id)
        }
        for (id in listOf(
            "",
            "VL",
            "RDmix",
            "VLRDmix",
            "MPREb_album",
            "LM",
            "VLLM",
            "SE",
            "FEmusic_library",
            "LPlocal",
            "auto:downloaded",
        )) {
            assertFalse(id.isSyncablePlaylistId(), id)
        }
    }
}
