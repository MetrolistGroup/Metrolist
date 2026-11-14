package com.metrolist.shared.ui.mobile

import com.metrolist.shared.model.BuiltinPlaylist
import com.metrolist.shared.model.PlaylistItem
import kotlin.test.Test
import kotlin.test.assertEquals

class BuiltinPlaylistRouteTest {
    @Test
    fun pinsOpenLibraryDestinationsWithoutAccountOrCollectionData() {
        BuiltinPlaylist.entries.forEach { builtin ->
            val playlist = builtin.playlist("Title")
            val expected =
                when (builtin) {
                    BuiltinPlaylist.HISTORY -> MobileRoute.History
                    BuiltinPlaylist.WEEKLY, BuiltinPlaylist.MONTHLY -> MobileRoute.SongCollection("local", playlist.title, playlist.id)
                    else -> MobileRoute.SongCollection(checkNotNull(builtin.collectionType), playlist.title)
                }
            assertEquals(expected, routeForMobileItem(playlist))
        }
        assertEquals(MobileRoute.SongCollection("liked", "Title"), routeForMobileItem(PlaylistItem("liked", "Title", null)))
        assertEquals(MobileRoute.SongCollection("liked", "Title"), routeForMobileItem(PlaylistItem("VLLM", "Title", null)))
        assertEquals(MobileRoute.SongCollection("SE", "Title"), routeForMobileItem(PlaylistItem("VLSE", "Title", null)))
        assertEquals(MobileRoute.SongCollection("podcasts", "Title"), routeForMobileItem(PlaylistItem("VLRDPN", "Title", null)))
    }
}
