package com.metrolist.android.widget

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.CachedAudioEntity
import com.metrolist.shared.db.entities.KIND_CACHE
import com.metrolist.shared.db.entities.KIND_DOWNLOAD
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.recordHistoryPlay
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.BuiltinPlaylist
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class WidgetPlaylistTargetTest {
    @Test
    fun everyBuiltinPinUsesItsCollectionAndCanonicalDeduplicationKey() {
        for (builtin in BuiltinPlaylist.entries) {
            val target = widgetPlaylistTarget(builtin.id, builtin.collectionType == null)
            assertEquals(builtin.id, target.key)
            assertEquals(builtin, widgetBuiltinPlaylist(target.type, target.id))
            assertNotEquals(PlaylistWidgetReceiver.TARGET_TYPE_ONLINE, target.type)
            builtin.collectionType?.let { collection ->
                assertEquals(target, widgetPlaylistTarget(collection, false))
            }
        }
    }

    @Test
    fun aliasesAndAutomaticPicksShareTheFreshPinsDestination() {
        for (alias in listOf("LM", "VLLM", "LM:account", PlaylistEntity.LIKED_PLAYLIST_ID)) {
            assertEquals(widgetPlaylistTarget(BuiltinPlaylist.LIKED.id, false), widgetPlaylistTarget(alias, false))
        }
        assertEquals(BuiltinPlaylist.LIKED, widgetBuiltinPlaylist(PlaylistWidgetReceiver.TARGET_TYPE_LIKED, "liked"))
        assertEquals(BuiltinPlaylist.DOWNLOADED, widgetBuiltinPlaylist(PlaylistWidgetReceiver.TARGET_TYPE_DOWNLOADED, "downloaded"))
        assertEquals(BuiltinPlaylist.TOP, widgetBuiltinPlaylist(PlaylistWidgetReceiver.TARGET_TYPE_TOP, "100"))
        for (builtin in listOf(BuiltinPlaylist.WEEKLY, BuiltinPlaylist.MONTHLY)) {
            assertEquals(PlaylistWidgetReceiver.TARGET_TYPE_LOCAL, widgetPlaylistTarget(builtin.id, true).type)
        }
    }

    @Test
    fun ordinaryRemoteAndLocalPlaylistsRetainTheirTargets() {
        assertEquals(
            WidgetPlaylistTarget("online:PLremote", PlaylistWidgetReceiver.TARGET_TYPE_ONLINE, "PLremote"),
            widgetPlaylistTarget("PLremote", false),
        )
        assertEquals(
            WidgetPlaylistTarget("local:local-id", PlaylistWidgetReceiver.TARGET_TYPE_LOCAL, "local-id"),
            widgetPlaylistTarget("local-id", true),
        )
        assertNull(widgetBuiltinPlaylist(PlaylistWidgetReceiver.TARGET_TYPE_ONLINE, "PLremote"))
        assertNull(widgetBuiltinPlaylist(PlaylistWidgetReceiver.TARGET_TYPE_LOCAL, "local-id"))
    }

    @Test
    fun storedAndHistoryCollectionsLoadWithoutPlaylistRows() =
        runBlocking {
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val database =
                Room
                    .inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MusicDatabase::class.java)
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            try {
                database.dao().insert(SongEntity(id = "download", title = "Downloaded", isDownloaded = true))
                for ((id, kind) in listOf("cache" to KIND_CACHE, "download" to KIND_DOWNLOAD)) {
                    database.dao().upsert(
                        CachedAudioEntity(
                            songId = id,
                            title = id,
                            artist = "First, Second",
                            album = "Album",
                            thumbnailUrl = "artwork",
                            filePath = "/cache/$id",
                            fileSize = 100,
                            cachedAt = 1,
                            lastAccessedAt = 1,
                            cacheLocation = "INTERNAL",
                            kind = kind,
                        ),
                    )
                }
                val cached = database.widgetBuiltinSongs(BuiltinPlaylist.CACHED).single()
                assertEquals("cache", cached.id)
                assertEquals(listOf("First", "Second"), cached.artists.map { it.name })
                assertEquals("Album", cached.album?.name)
                assertEquals("artwork", cached.thumbnail)
                assertEquals(listOf("download"), database.widgetBuiltinSongs(BuiltinPlaylist.DOWNLOADED).map { it.id })

                database.recordHistoryPlay(SongItem("history", "Track", null, listOf(ArtistTiny(null, "Artist")), null), 1000)
                assertEquals(listOf("history"), database.widgetBuiltinSongs(BuiltinPlaylist.HISTORY).map { it.id })
            } finally {
                database.close()
                Settings.replaceSharedForTest(null)
            }
        }
}
