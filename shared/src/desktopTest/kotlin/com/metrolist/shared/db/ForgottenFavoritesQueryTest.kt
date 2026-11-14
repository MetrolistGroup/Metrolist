package com.metrolist.shared.db

import com.metrolist.shared.db.entities.Event
import com.metrolist.shared.db.entities.SongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class ForgottenFavoritesQueryTest {
    private val now = 1_800_000_000_000L
    private val cutoff = now - 30L * 86_400_000

    @Test
    fun preservesCutoffKnownPlayTimesAndAscendingOldPlayTimeOrder() =
        withDatabase { database ->
            listOf(
                "larger-old",
                "tie-b",
                "tie-a",
                "small-old",
                "no-recent",
                "unknown-recent",
                "unknown-old",
                "mixed-old",
                "only-recent",
                "boundary-only",
                "boundary-with-recent",
                "at-threshold",
                "above-threshold",
                "sum-recent",
                "zero-old",
                "no-events",
                "future-recent",
            ).forEach { database.dao().insert(SongEntity(it, it)) }
            database.play("larger-old", -2, 500)
            database.play("larger-old", -1, 500)
            database.play("larger-old", 1, 199)
            database.play("tie-b", -1, 300)
            database.play("tie-b", 0, 10_000)
            database.play("tie-a", -2, 100)
            database.play("tie-a", -1, 200)
            database.play("tie-a", 1, 59)
            database.play("small-old", -1, 50)
            database.play("small-old", 1, 9)
            database.play("no-recent", -1, 400)
            database.play("unknown-recent", -1, 500)
            database.play("unknown-recent", 1, 10_000, known = false)
            database.play("unknown-old", -1, 10_000, known = false)
            database.play("unknown-old", 1, 0)
            database.play("mixed-old", -2, 100)
            database.play("mixed-old", -1, 10_000, known = false)
            database.play("mixed-old", 1, 21)
            database.play("only-recent", 1, 1)
            database.play("boundary-only", 0, 10_000)
            database.play("boundary-with-recent", 0, 10_000)
            database.play("boundary-with-recent", 1, 1)
            database.play("at-threshold", -1, 100)
            database.play("at-threshold", 1, 20)
            database.play("above-threshold", -1, 100)
            database.play("above-threshold", 1, 21)
            database.play("sum-recent", -1, 100)
            database.play("sum-recent", 1, 9)
            database.play("sum-recent", 2, 11)
            database.play("zero-old", -1, 0)
            database.play("future-recent", -1, 100)
            database.play("future-recent", now - cutoff + 1, 100)

            assertEquals(
                listOf("small-old", "tie-a", "tie-b", "no-recent", "unknown-recent", "larger-old"),
                database
                    .dao()
                    .forgottenFavorites(now)
                    .first()
                    .map { it.id },
            )
        }

    @Test
    fun limitsToOneHundredSongsAfterFilteringAndOrdering() =
        withDatabase { database ->
            database.withDatabaseTransaction {
                for (index in 105 downTo 1) {
                    val id = "song-${index.toString().padStart(3, '0')}"
                    database.dao().insert(SongEntity(id, id))
                    database.play(id, -1, index * 100L)
                    if (index <= 5) database.play(id, 1, index * 100L)
                }
                database.dao().insert(SongEntity("tie-at-limit", "Tie at limit"))
                database.play("tie-at-limit", -1, 10_500)
            }

            assertEquals(
                (6..105).map { "song-${it.toString().padStart(3, '0')}" },
                database
                    .dao()
                    .forgottenFavorites(now)
                    .first()
                    .map { it.id },
            )
        }

    private suspend fun MusicDatabase.play(
        songId: String,
        offsetFromCutoff: Long,
        playTime: Long,
        known: Boolean = true,
    ) {
        dao().insert(
            Event(
                songId = songId,
                timestamp = Instant.fromEpochMilliseconds(cutoff + offsetFromCutoff).toLocalDateTime(TimeZone.UTC),
                playTime = playTime,
                playTimeKnown = known,
            ),
        )
    }

    private fun withDatabase(block: suspend (MusicDatabase) -> Unit) =
        runBlocking {
            val path = Files.createTempFile("forgotten-favorites", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                block(database)
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }
}
