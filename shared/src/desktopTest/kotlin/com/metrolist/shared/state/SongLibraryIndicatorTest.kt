package com.metrolist.shared.state

import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.getDesktopDatabaseBuilder
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SongLibraryIndicatorTest {
    @Test
    fun savedSongsObserveLibraryChangesWithoutRemoteTokens() =
        runBlocking<Unit> {
            val path = Files.createTempFile("metrolist-library-indicator", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val job = Job(coroutineContext[Job])
            try {
                val dao = database.dao()
                val saved = SongEntity("saved", "Saved", inLibrary = LocalDateTime(2026, 11, 16, 12, 0))
                dao.insert(saved)
                dao.insert(SongEntity("liked", "Liked", liked = true))
                val remote = SongItem("saved", "Saved", null, emptyList(), null)
                val staleToken = remote.copy(libraryRemoveToken = "stale")
                SongMetadataState.bind(database, CoroutineScope(coroutineContext + job))
                withTimeout(5_000) {
                    SongMetadataState.revision.first { SongMetadataState.isInLibrary(remote) }
                }
                assertTrue(SongMetadataState.isInLibrary(remote))
                assertFalse(SongMetadataState.isInLibrary(remote.copy(id = "liked")))
                assertFalse(SongMetadataState.isInLibrary(remote.copy(id = "unknown")))
                assertTrue(SongMetadataState.isInLibrary(staleToken.copy(id = "remote-only")))
                dao.inLibrary(saved.id, null)
                withTimeout(5_000) {
                    SongMetadataState.revision.first { !SongMetadataState.isInLibrary(remote) }
                }
                // A cached row without inLibrary keeps the badge from a valid remote token.
                assertTrue(SongMetadataState.isInLibrary(staleToken))
                dao.inLibrary(saved.id, saved.inLibrary)
                withTimeout(5_000) {
                    SongMetadataState.revision.first { SongMetadataState.isInLibrary(remote) }
                }
            } finally {
                job.cancelAndJoin()
                database.close()
                Files.deleteIfExists(path)
            }
        }
}
