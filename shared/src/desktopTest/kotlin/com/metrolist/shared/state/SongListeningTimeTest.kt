package com.metrolist.shared.state

import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.getDesktopDatabaseBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SongListeningTimeTest {
    @Test
    fun listeningTimesObservePlaybackUpdatesAndClearOnUnbind() =
        runBlocking {
            val path = Files.createTempFile("metrolist-listening-times", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val job = Job(coroutineContext[Job])
            try {
                suspend fun awaitTimes(expected: Map<String, Long>) {
                    withTimeout(5_000) {
                        while (SongMetadataState.listeningTimes != expected) delay(10)
                    }
                }
                val song = SongEntity("song", "Song", totalPlayTime = 60_000)
                database.dao().insert(song)
                SongMetadataState.bind(database, CoroutineScope(coroutineContext + job))
                awaitTimes(mapOf(song.id to 60_000L))
                database.dao().update(song.copy(totalPlayTime = 120_000))
                awaitTimes(mapOf(song.id to 120_000L))
                database.dao().delete(song)
                awaitTimes(emptyMap())
                job.cancelAndJoin()
                assertEquals(emptyMap(), SongMetadataState.listeningTimes)
            } finally {
                job.cancelAndJoin()
                database.close()
                Files.deleteIfExists(path)
            }
        }
}
