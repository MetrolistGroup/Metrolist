package com.metrolist.shared.playlistimport

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
class ImportProviderTimeoutTest {
    private val deezerClient = DeezerPlaylistImporter.clientProvider
    private val tidalClient = TidalPlaylistImporter.clientProvider
    private val sources =
        listOf(
            "https://www.deezer.com/playlist/123",
            "https://music.apple.com/us/playlist/name/pl.123",
            "https://tidal.com/browse/playlist/12345678-abcd",
            "https://music.amazon.com/playlists/B012345678",
        )

    @Test
    fun deezerDeadlineIsReportedAsFailure() = checkDeadline(sources[0])

    @Test
    fun appleDeadlineIsReportedAsFailure() = checkDeadline(sources[1])

    @Test
    fun tidalDeadlineIsReportedAsFailure() = checkDeadline(sources[2])

    @Test
    fun amazonDeadlineIsReportedAsFailure() = checkDeadline(sources[3])

    private fun checkDeadline(source: String) =
        runTest {
            val client = stalledClient(StandardTestDispatcher(testScheduler))
            install(client)
            val id = PlaylistImportStatusManager.startImport("Playlist", "Provider")
            try {
                val error = UnifiedPlaylistImporter.parseFromInput(source).exceptionOrNull()
                assertEquals(ImportErrorKey.IncompleteSource, assertIs<ImportException>(error).errorKey)
                assertEquals(15_000L, testScheduler.currentTime)
                PlaylistImportStatusManager.fail(id, ImportProgressState.Failed("failed", error))
                assertEquals(false, PlaylistImportStatusManager.activeImport.value?.isRunning)
                assertIs<ImportProgressState.Failed>(PlaylistImportStatusManager.activeImport.value?.state)
            } finally {
                PlaylistImportStatusManager.clear(id)
                reset()
                client.close()
            }
        }

    @Test
    fun callerCancellationIsNotConvertedToProviderFailure() =
        runTest {
            val client = stalledClient(StandardTestDispatcher(testScheduler))
            install(client)
            try {
                for (source in sources) {
                    var returned = false
                    val request =
                        async {
                            UnifiedPlaylistImporter.parseFromInput(source)
                            returned = true
                        }
                    runCurrent()
                    request.cancelAndJoin()
                    assertTrue(request.isCancelled)
                    assertFalse(returned)
                }
            } finally {
                reset()
                client.close()
            }
        }

    @Test
    fun parentDeadlineStillCancelsTheRequest() =
        runTest {
            val client = stalledClient(StandardTestDispatcher(testScheduler))
            install(client)
            try {
                for (source in sources) {
                    val result =
                        withTimeoutOrNull(1_000) {
                            UnifiedPlaylistImporter.parseFromInput(source)
                            fail("Parent cancellation was swallowed")
                        }
                    assertNull(result)
                }
                assertEquals(4_000L, testScheduler.currentTime)
            } finally {
                reset()
                client.close()
            }
        }

    @Test
    fun requestDeadlinePreservesNullableResponsesAndOrdinaryFailures() =
        runTest {
            assertNull(withImportRequestTimeout<String?> { null })
            val failure = IllegalStateException("transport failed")
            val thrown = assertFailsWith<IllegalStateException> { withImportRequestTimeout { throw failure } }
            assertEquals(failure.message, thrown.message)
        }

    @Test
    fun nestedDeadlineIsNotMistakenForTheRequestDeadline() =
        runTest {
            assertFailsWith<TimeoutCancellationException> {
                withImportRequestTimeout { withTimeout(1_000) { awaitCancellation() } }
            }
            assertEquals(1_000L, testScheduler.currentTime)
        }

    private fun stalledClient(dispatcher: CoroutineDispatcher): HttpClient =
        HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    this.dispatcher = dispatcher
                    addHandler { awaitCancellation() }
                },
            ),
        )

    private fun install(client: HttpClient) {
        DeezerPlaylistImporter.clientProvider = { client }
        AppleMusicPlaylistImporter.resetClientForTests(client)
        TidalPlaylistImporter.clientProvider = { client }
        AmazonMusicPlaylistImporter.resetClientForTests(client)
    }

    private fun reset() {
        DeezerPlaylistImporter.clientProvider = deezerClient
        AppleMusicPlaylistImporter.resetClientForTests()
        TidalPlaylistImporter.clientProvider = tidalClient
        AmazonMusicPlaylistImporter.resetClientForTests()
    }
}
