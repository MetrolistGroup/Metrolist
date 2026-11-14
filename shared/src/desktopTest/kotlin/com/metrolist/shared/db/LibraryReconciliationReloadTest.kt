package com.metrolist.shared.db

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.viewmodels.LibraryViewModel
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryReconciliationReloadTest {
    @Test
    fun firstAbsentScanStaysHiddenAcrossReloadsAndAllowsANewLocalSave() =
        runTest {
            Settings.replaceSharedForTest(
                Settings(MapSettings()).apply {
                    lastRemoteLibraryItemKeysJson = "[\"album:tracked\"]"
                },
            )
            val path = Files.createTempFile("library-reload", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            """{"contents":{"sectionListRenderer":{"contents":[]}}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                ) { install(ContentNegotiation) { json() } }
            val job = Job()
            try {
                val bookmark = (Clock.System.now() - 1.days).toLocalDateTime(TimeZone.UTC)
                val tracked = AlbumEntity("tracked", title = "Tracked", songCount = 0, duration = 0, bookmarkedAt = bookmark)
                database.dao().upsert(tracked)
                database.dao().upsert(tracked.copy(id = "local", title = "Untracked"))
                val tube = InnerTube(client, retryDelay = {}).apply { cookie = "SAPISID=session" }
                val model = LibraryViewModel(CoroutineScope(coroutineContext + job), database, repository = YouTubeRepository(tube))
                model.setActive(true)
                model.uiState.first { state -> state.items.any { it.id == "local" } && state.items.none { it.id == "tracked" } }
                assertNotNull(
                    database
                        .dao()
                        .album("tracked")
                        .first()
                        ?.album
                        ?.bookmarkedAt,
                )
                assertTrue(Settings.shared.pendingRemoteLibraryRemovalKeysJson.contains("album:tracked"))

                model.setActive(false)
                runCurrent()
                model.setActive(true)
                delay(500)
                assertFalse(
                    model.uiState.value.items
                        .any { it.id == "tracked" },
                )
                assertTrue(
                    model.uiState.value.items
                        .any { it.id == "local" },
                )
                assertNotNull(
                    database
                        .dao()
                        .album("tracked")
                        .first()
                        ?.album
                        ?.bookmarkedAt,
                )

                database.dao().upsert(tracked.copy(bookmarkedAt = (Clock.System.now() + 1.seconds).toLocalDateTime(TimeZone.UTC)))
                model.uiState.first { state -> state.items.any { it.id == "tracked" } }
            } finally {
                job.cancelAndJoin()
                database.close()
                client.close()
                Files.deleteIfExists(path)
                Settings.replaceSharedForTest(null)
            }
        }
}
