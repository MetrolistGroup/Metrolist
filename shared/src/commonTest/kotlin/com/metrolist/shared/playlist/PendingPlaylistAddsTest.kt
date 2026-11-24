package com.metrolist.shared.playlist

import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingPlaylistAddsTest {
    private var previousSettings: Settings? = null

    @BeforeTest
    fun setUp() {
        previousSettings = Settings.sharedOverrideForTest()
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() = Settings.replaceSharedForTest(previousSettings)

    @Test
    fun failedRemovalKeepsQueuedAddsAndASuccessfulOneCancelsThem() =
        runTest {
            val queuedAdd = PendingPlaylistAdds.enqueue(PLAYLIST, "queued", allowDuplicate = false)
            val copies = listOf(null to "queued", "item" to "listed")

            val failure =
                assertFailsWith<IllegalStateException> {
                    PendingPlaylistAdds.removeFromPlaylist(PLAYLIST, copies) { error("offline") }
                }
            assertEquals("offline", failure.message)
            assertEquals(listOf(queuedAdd), stored().map { it.id })

            val removed = mutableListOf<List<Pair<String, String>>>()
            PendingPlaylistAdds.removeFromPlaylist(PLAYLIST, copies) { removed += it }
            assertEquals(listOf(listOf("item" to "listed")), removed)
            assertTrue(stored().isEmpty())
        }

    @Test
    fun removalCancelsAnUnsentDuplicateFirstAndLowersTheTargetsLeft() =
        runTest {
            store(add(1, expectedCount = 3), add(2))

            PendingPlaylistAdds.removeFromPlaylist(PLAYLIST, listOf(null to SONG, "item" to SONG)) {}

            assertEquals(listOf(add(1, expectedCount = 2)), stored())
        }

    @Test
    fun copyWithoutItemIdBeyondTheQueuedAddsFailsBeforeYouTubeIsCalled() =
        runTest {
            store(add(1))
            var calledYouTube = false

            val failure =
                assertFailsWith<IllegalStateException> {
                    PendingPlaylistAdds.removeFromPlaylist(PLAYLIST, listOf(null to SONG, null to SONG)) { calledYouTube = true }
                }

            assertEquals("Missing playlist item identifier", failure.message)
            assertFalse(calledYouTube)
            assertEquals(listOf(add(1)), stored())
        }

    private fun add(
        id: Long,
        expectedCount: Int? = null,
    ) = PendingPlaylistAdd(id, "account", PLAYLIST, SONG, allowDuplicate = true, expectedCount = expectedCount)

    private fun store(vararg entries: PendingPlaylistAdd) {
        Settings.shared.pendingPlaylistAddsJson = Json.encodeToString(entries.toList())
    }

    private fun stored() = Json.decodeFromString<List<PendingPlaylistAdd>>(Settings.shared.pendingPlaylistAddsJson)

    private companion object {
        const val PLAYLIST = "LPplaylist"
        const val SONG = "song"
    }
}
