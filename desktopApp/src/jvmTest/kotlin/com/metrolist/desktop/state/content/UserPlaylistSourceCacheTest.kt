package com.metrolist.desktop.state.content

import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.model.PlaylistItem
import kotlin.test.Test
import kotlin.test.assertEquals

class UserPlaylistSourceCacheTest {
    @Test
    fun remoteRefreshKeepsTheLatestLocalSnapshot() {
        val cache = UserPlaylistSourceCache()
        val initialLocal = PlaylistEntity(id = "initial", name = "Initial")
        val latestLocal = PlaylistEntity(id = "latest", name = "Latest")
        val remote = PlaylistItem(id = "remote", title = "Remote", thumbnail = null)

        cache.updateLocal(listOf(initialLocal))
        cache.updateLocal(listOf(latestLocal))
        cache.updateRemote(listOf(remote), "account")

        assertEquals(listOf(latestLocal) to listOf(remote), cache.snapshot("account"))
        assertEquals(listOf(latestLocal) to emptyList(), cache.snapshot("another-account"))
    }
}
