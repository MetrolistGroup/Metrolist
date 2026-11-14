package com.metrolist.shared.db

import com.metrolist.shared.db.entities.SpeedDialItem
import com.metrolist.shared.model.BuiltinPlaylist
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.canonicalHomeItemId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SpeedDialDaoTest {
    @Test
    fun builtinPinsSurviveReopenAndResolveWithoutCollectionRows() =
        runBlocking {
            val path = Files.createTempFile("metrolist-builtin-pins", ".db")
            Files.deleteIfExists(path)
            var database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                BuiltinPlaylist.entries.forEach { builtin ->
                    database.speedDialDao().pin(SpeedDialItem.fromYTItem(builtin.playlist("Stored title")))
                }
                val before = database.speedDialDao().getAllOnce()
                assertEquals(BuiltinPlaylist.entries.map { it.id }, before.map { it.id })
                database.close()
                database = getDesktopDatabaseBuilder(path.toFile()).build()

                assertEquals(before, database.speedDialDao().getAllOnce())
                database.speedDialDao().getAllOnce().forEach { pin ->
                    val builtin = BuiltinPlaylist.fromId(pin.id)!!
                    val localizedTitle = getString(builtin.title)
                    val item = builtin.resolve(pin.toYTItem() as PlaylistItem, localizedTitle)
                    assertEquals(builtin.id, item.id)
                    assertEquals(localizedTitle, item.title)
                    assertNotEquals(pin.title, item.title)
                    assertEquals(builtin.icon, item.thumbnail)
                    assertEquals(builtin.collectionType == null, item.isLocal)
                    if (builtin.collectionType == null) {
                        assertEquals(builtin.playlist(localizedTitle).copy(thumbnail = null, songCount = "0"), emptyHistoryPlaylist(pin.id))
                    }
                }
                val requested = mutableListOf<String>()
                database.refreshSpeedDialArtwork { id ->
                    requested += id
                    null
                }
                assertEquals(emptyList(), requested)

                before.forEach { database.speedDialDao().unpin(it.id) }
                assertEquals(emptyList(), database.speedDialDao().getAllOnce())
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun builtinAliasesReuseAndUnpinExistingPins() =
        withDatabase { database ->
            val dao = database.speedDialDao()
            val old = SpeedDialItem.fromYTItem(PlaylistItem("VLLM", "Account title", "old.jpg"))
            dao.pin(old)
            dao.pin(SpeedDialItem.fromYTItem(BuiltinPlaylist.LIKED.playlist("Current title")))

            val pins = dao.getAllOnce()
            assertEquals(1, pins.size)
            assertEquals(old.id, pins.single().id)
            assertEquals(old.createDate, pins.single().createDate)
            assertEquals("Current title", pins.single().title)
            assertEquals(BuiltinPlaylist.LIKED.id, canonicalHomeItemId(pins.single().toYTItem()))

            dao.unpin(BuiltinPlaylist.LIKED.id)
            assertEquals(emptyList(), dao.getAllOnce())
        }

    @Test
    fun refreshRepairsMissingAndExpiredArtworkWithoutChangingPins() =
        withDatabase { database ->
            val dao = database.speedDialDao()
            val pins =
                listOf(
                    SpeedDialItem("missing", title = "Missing", type = "PLAYLIST"),
                    SpeedDialItem("expired", title = "Expired", type = "PLAYLIST", thumbnailUrl = "expired.jpg"),
                    SpeedDialItem("offline", title = "Offline", type = "PLAYLIST", thumbnailUrl = "saved.jpg"),
                    SpeedDialItem("removed", title = "Removed", type = "PLAYLIST"),
                    SpeedDialItem("local", title = "Local", type = "LOCAL_PLAYLIST"),
                )
            pins.forEach { dao.pin(it) }
            val before = dao.getAllOnce()
            val requested = mutableListOf<String>()
            database.refreshSpeedDialArtwork { id ->
                requested += id
                when (id) {
                    "local" -> {
                        error("Local playlists must not be fetched")
                    }

                    "offline" -> {
                        error("Offline")
                    }

                    "removed" -> {
                        dao.unpin(id)
                        PlaylistItem(id, id, "removed.jpg")
                    }

                    else -> {
                        PlaylistItem(id, id, "$id-fresh.jpg")
                    }
                }
            }
            val restored = dao.getAllOnce()
            assertEquals(listOf("missing", "expired", "offline", "removed"), requested)
            assertEquals(listOf("missing", "expired", "offline", "local"), restored.map { it.id })
            assertEquals(listOf("missing-fresh.jpg", "expired-fresh.jpg", "saved.jpg", null), restored.map { it.thumbnailUrl })
            assertEquals(before.take(3).map { it.createDate }, restored.take(3).map { it.createDate })
            assertEquals("missing-fresh.jpg", (restored.first().toYTItem() as PlaylistItem).thumbnail)
        }

    @Test
    fun mutationsKeepAContiguousDeterministicOrder() =
        withDatabase { database ->
            val dao = database.speedDialDao()
            dao.pin(item("a", "A"))
            dao.pin(item("b", "B"))
            dao.pin(item("c", "C"))
            dao.moveEarlier("c")
            assertOrder(dao.getAllOnce(), "a", "c", "b")

            dao.moveLater("a")
            assertOrder(dao.getAllOnce(), "c", "a", "b")

            dao.unpin("a")
            assertOrder(dao.getAllOnce(), "c", "b")

            dao.pin(item("c", "Updated"))
            assertOrder(dao.getAllOnce(), "c", "b")
            assertEquals("Updated", dao.getAllOnce().first().title)

            dao.deleteAll()
            assertEquals(emptyList(), dao.getAllOnce())
        }

    @Test
    fun pinNormalizesDuplicateLegacyIndexesInDeterministicOrder() =
        withDatabase { database ->
            val dao = database.speedDialDao()
            dao.insert(item("b", "B").copy(index = 0, createDate = 2))
            dao.insert(item("a", "A").copy(index = 0, createDate = 1))

            dao.pin(item("c", "C"))

            assertOrder(dao.getAllOnce(), "a", "b", "c")
        }

    private fun assertOrder(
        items: List<SpeedDialItem>,
        vararg ids: String,
    ) {
        assertEquals(ids.toList(), items.map { it.id })
        assertEquals(items.indices.toList(), items.map { it.index })
    }

    private fun item(
        id: String,
        title: String,
    ) = SpeedDialItem(id = id, title = title, type = "SONG", createDate = id.first().code.toLong())

    private fun withDatabase(block: suspend kotlinx.coroutines.CoroutineScope.(MusicDatabase) -> Unit) =
        runBlocking {
            val path = Files.createTempFile("metrolist-speed-dial", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                block(database)
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }
}
