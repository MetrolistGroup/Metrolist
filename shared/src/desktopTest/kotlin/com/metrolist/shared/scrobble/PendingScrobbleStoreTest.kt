package com.metrolist.shared.scrobble

import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.daos.PendingScrobbleDao
import com.metrolist.shared.db.getDesktopDatabaseBuilder
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingScrobbleStoreTest {
    private val directory = Files.createTempDirectory("pending-scrobbles").toFile()
    private val file = directory.resolve("library.db")
    private lateinit var database: MusicDatabase

    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        openDatabase()
    }

    private fun openDatabase() {
        database = getDesktopDatabaseBuilder(file).build()
        PendingScrobbleStore.daoForTest = database.pendingScrobbleDao()
    }

    @AfterTest
    fun tearDown() {
        PendingScrobbleStore.daoForTest = null
        database.close()
        Settings.replaceSharedForTest(null)
        directory.deleteRecursively()
    }

    @Test
    fun legacyQueueMigratesOnceAndRetryStateSurvivesRestart() =
        runBlocking {
            val plays = List(1_000) { pending("play-$it") }
            val legacy = Json.encodeToString(plays)
            Settings.shared.pendingScrobblesJson = legacy
            assertEquals(plays, PendingScrobbleStore.all())
            assertEquals("[]", Settings.shared.pendingScrobblesJson)
            val retry = plays.first().copy(attemptCount = 3, nextAttemptAtMs = 50_000)
            PendingScrobbleStore.upsert(retry)
            Settings.shared.pendingScrobblesJson = legacy
            database.close()
            openDatabase()
            assertEquals(retry, PendingScrobbleStore.get(retry.id))
            assertEquals(plays.size, PendingScrobbleStore.all().size)
            PendingScrobbleStore.remove(retry.id)
            database.close()
            openDatabase()
            assertNull(PendingScrobbleStore.get(retry.id))
            assertEquals(plays.drop(1), PendingScrobbleStore.all())
        }

    @Test
    fun acknowledgementsDoNotReadOrRewriteRemainingRows() =
        runBlocking {
            val plays = List(1_000) { pending("play-$it") }
            val dao = database.pendingScrobbleDao()
            PendingScrobbleStore.upsert(plays)
            PendingScrobbleStore.daoForTest =
                object : PendingScrobbleDao by dao {
                    override suspend fun all(): List<PendingScrobble> = error("Acknowledgements must not read the backlog")

                    override suspend fun upsert(scrobbles: List<PendingScrobble>): Unit =
                        error("Acknowledgements must not rewrite the backlog")
                }
            plays.dropLast(1).forEach { PendingScrobbleStore.remove(it.id) }
            assertEquals(listOf(plays.last()), dao.all())
        }

    @Test
    fun corruptLegacyEntriesAreQuarantinedAndStorageFailuresKeepLegacyQueue() =
        runBlocking {
            val play = pending("valid")
            val partial = "[${Json.encodeToString(play)},{\"broken\":true}]"
            Settings.shared.pendingScrobblesJson = partial
            assertEquals(listOf(play), PendingScrobbleStore.all())
            assertEquals(partial, Settings.shared.corruptPendingScrobblesJson)
            Settings.shared.pendingScrobblesJson = "broken json"
            assertEquals(listOf(play), PendingScrobbleStore.all())
            assertEquals("broken json", Settings.shared.corruptPendingScrobblesJson)
            val legacy = Json.encodeToString(listOf(pending("retry")))
            Settings.shared.pendingScrobblesJson = legacy
            val dao = database.pendingScrobbleDao()
            PendingScrobbleStore.daoForTest =
                object : PendingScrobbleDao by dao {
                    override suspend fun importLegacy(scrobbles: List<PendingScrobble>): Unit = error("Storage unavailable")
                }
            assertFailsWith<IllegalStateException> { PendingScrobbleStore.all() }
            assertEquals(legacy, Settings.shared.pendingScrobblesJson)
            PendingScrobbleStore.daoForTest = dao
            assertEquals(2, PendingScrobbleStore.all().size)
        }

    @Test
    fun clearingOneProviderKeepsOtherAccountsAndTheirRetryState() =
        runBlocking {
            val last = pending("last")
            val brainz = pending("brainz").copy(provider = ScrobbleProvider.LISTEN_BRAINZ, attemptCount = 2, nextAttemptAtMs = 1_000)
            PendingScrobbleStore.upsert(listOf(last, brainz))
            PendingScrobbleStore.clear(ScrobbleProvider.LAST_FM)
            assertEquals(listOf(brainz), PendingScrobbleStore.all())
            PendingScrobbleStore.clear(ScrobbleProvider.LISTEN_BRAINZ)
            assertTrue(PendingScrobbleStore.all().isEmpty())
        }

    private fun pending(id: String) = PendingScrobble(id, ScrobbleProvider.LAST_FM, "account", "Track", "Artist", timestamp = 100)
}
