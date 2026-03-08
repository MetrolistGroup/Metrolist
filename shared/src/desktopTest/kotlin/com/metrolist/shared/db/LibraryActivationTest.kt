package com.metrolist.shared.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.LibraryViewModel
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class LibraryActivationTest {
    @Test
    fun localReadsStopWhileInactiveAndActivationReloadsCurrentLibrary() =
        runTest {
            Settings.replaceSharedForTest(
                Settings(MapSettings()).apply { lastLibraryRefreshEpoch = Clock.System.now().toEpochMilliseconds() },
            )
            val path = Files.createTempFile("library-activation", ".db")
            Files.delete(path)
            val reads = AtomicInteger()
            val driver = JdbcSQLiteDriver()
            val countingDriver =
                object : SQLiteDriver by driver {
                    override fun open(fileName: String): SQLiteConnection {
                        val connection = driver.open(fileName)
                        return object : SQLiteConnection by connection {
                            override fun prepare(sql: String): SQLiteStatement {
                                val statement = connection.prepare(sql)
                                if (sql != "SELECT * FROM song WHERE inLibrary IS NOT NULL ORDER BY inLibrary") return statement
                                return object : SQLiteStatement by statement {
                                    private var started = false

                                    override fun step(): Boolean {
                                        if (!started) {
                                            started = true
                                            reads.incrementAndGet()
                                        }
                                        return statement.step()
                                    }

                                    override fun reset() {
                                        statement.reset()
                                        started = false
                                    }
                                }
                            }
                        }
                    }
                }
            val database =
                getDesktopDatabaseBuilder(path.toFile())
                    .setDriver(countingDriver)
                    .setQueryCoroutineContext(StandardTestDispatcher(testScheduler))
                    .build()
            val job = Job()
            val downloads = MutableStateFlow(emptySet<String>())
            try {
                val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                val song = SongEntity("saved", "Original", inLibrary = now)
                database.dao().upsert(song)
                val model = LibraryViewModel(CoroutineScope(coroutineContext + job), database, downloads)
                delay(500)
                assertEquals(0, reads.get())
                assertEquals(0, downloads.subscriptionCount.value)

                model.setActive(true)
                model.uiState.first { it.items.filterIsInstance<SongItem>().any { item -> item.title == "Original" } }
                val activeReads = reads.get()
                assertTrue(activeReads >= 2, "Expected an observer query and a reload, got $activeReads reads")
                assertEquals(1, downloads.subscriptionCount.value)

                model.setActive(false)
                downloads.subscriptionCount.first { it == 0 }
                database.dao().upsert(song.copy(title = "Changed while hidden", totalPlayTime = 30_000))
                database.dao().upsert(AlbumEntity("album", title = "Saved while hidden", songCount = 0, duration = 0, bookmarkedAt = now))
                delay(500)
                assertEquals(activeReads, reads.get())
                assertEquals(
                    "Original",
                    model.uiState.value.items
                        .filterIsInstance<SongItem>()
                        .single()
                        .title,
                )

                model.setActive(true)
                model.uiState.first { state ->
                    state.items.filterIsInstance<SongItem>().any { it.title == "Changed while hidden" } &&
                        state.items.any { it is AlbumItem && it.id == "album" }
                }
                assertEquals(activeReads * 2, reads.get())
                model.setActive(true)
                delay(500)
                assertEquals(activeReads * 2, reads.get())

                database.dao().upsert(song.copy(title = "Changed while visible"))
                model.uiState.first { it.items.filterIsInstance<SongItem>().any { item -> item.title == "Changed while visible" } }
                database.dao().upsert(
                    AlbumEntity("visible-album", title = "Saved while visible", songCount = 0, duration = 0, bookmarkedAt = now),
                )
                model.uiState.first { it.items.any { item -> item is AlbumItem && item.id == "visible-album" } }

                database.dao().upsert(song.copy(title = "Pending"))
                model.setActive(false)
                downloads.subscriptionCount.first { it == 0 }
                val stoppedReads = reads.get()
                delay(500)
                assertEquals(stoppedReads, reads.get())
                model.setActive(true)
                model.uiState.first { it.items.filterIsInstance<SongItem>().any { item -> item.title == "Pending" } }
            } finally {
                job.cancelAndJoin()
                database.close()
                Files.deleteIfExists(path)
                Settings.replaceSharedForTest(null)
            }
        }
}
