package com.metrolist.shared.db

import com.metrolist.shared.db.entities.SongEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DatabaseTransactionTest {
    @Test
    fun daoCallsCommitAndRollbackTogether() =
        runBlocking {
            val path = Files.createTempFile("metrolist-transaction", ".db")
            Files.deleteIfExists(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                database.withDatabaseTransaction {
                    database.dao().upsert(SongEntity(id = "committed", title = "Committed"))
                }
                assertEquals(
                    "Committed",
                    database
                        .dao()
                        .getSongById("committed")
                        ?.song
                        ?.title,
                )

                assertFailsWith<IllegalStateException> {
                    database.withDatabaseTransaction {
                        database.dao().upsert(SongEntity(id = "rolled-back", title = "Rolled back"))
                        error("rollback")
                    }
                }
                assertNull(database.dao().getSongById("rolled-back"))

                assertFailsWith<CancellationException> {
                    withContext(Job()) {
                        database.withDatabaseTransaction {
                            database.dao().upsert(SongEntity(id = "cancelled", title = "Cancelled"))
                            currentCoroutineContext().cancel()
                            currentCoroutineContext().ensureActive()
                        }
                    }
                }
                assertNull(database.dao().getSongById("cancelled"))
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }
}
