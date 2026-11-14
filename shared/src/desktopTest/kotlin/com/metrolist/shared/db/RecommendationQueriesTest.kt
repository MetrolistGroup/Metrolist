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

class RecommendationQueriesTest {
    @Test
    fun quickPicksOnlyCountEligibleSourcesAndForgottenFavoritesIncludeZeroRecentPlays() =
        runBlocking {
            val path = Files.createTempFile("recommendation-queries", ".db")
            Files.delete(path)
            val db = getDesktopDatabaseBuilder(path.toFile()).build()
            try {
                val dao = db.dao()
                listOf("seed", "seed2").forEach { dao.insert(SongEntity(it, it, totalPlayTime = 100)) }
                repeat(8) { dao.insert(SongEntity("filler$it", "Filler", totalPlayTime = 50)) }
                listOf("outside", "shared", "popular", "forgotten", "never-recent", "recent").forEach { dao.insert(SongEntity(it, it)) }
                relate(path.toString(), "outside", "shared")
                relate(path.toString(), "seed", "shared")
                repeat(4) { relate(path.toString(), "outside", "shared") }
                relate(path.toString(), "seed", "popular")
                relate(path.toString(), "seed2", "popular")
                val now = 1_800_000_000_000L
                assertEquals(listOf("popular", "shared"), dao.quickPicks(now).first().map { it.id })
                val old = Instant.fromEpochMilliseconds(now - 40L * 86_400_000).toLocalDateTime(TimeZone.UTC)
                val today = Instant.fromEpochMilliseconds(now).toLocalDateTime(TimeZone.UTC)
                dao.insert(Event(songId = "forgotten", timestamp = old, playTime = 1000))
                dao.insert(Event(songId = "never-recent", timestamp = old, playTime = 1000))
                dao.insert(Event(songId = "recent", timestamp = old, playTime = 1000))
                dao.insert(Event(songId = "recent", timestamp = today, playTime = 500))
                dao.insert(Event(songId = "forgotten", timestamp = today, playTime = 9000, playTimeKnown = false))
                assertEquals(
                    setOf("forgotten", "never-recent"),
                    dao
                        .forgottenFavorites(now)
                        .first()
                        .map { it.id }
                        .toSet(),
                )
            } finally {
                db.close()
                Files.deleteIfExists(path)
            }
        }

    private fun relate(
        path: String,
        source: String,
        target: String,
    ) {
        val connection = JdbcSQLiteDriver().open(path)
        try {
            connection.prepare("INSERT INTO related_song_map(songId, relatedSongId) VALUES (?, ?)").use {
                it.bindText(1, source)
                it.bindText(2, target)
                it.step()
            }
        } finally {
            connection.close()
        }
    }
}
