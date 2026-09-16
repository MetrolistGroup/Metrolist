/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.SongEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HistoryDatabaseDeduplicationTest {
    private lateinit var database: InternalDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.dao.insert(SongEntity(id = SONG_ID, title = "Song"))
        database.dao.insert(SongEntity(id = OTHER_SONG_ID, title = "Other song"))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `history hydrates only the latest play per song in each displayed period`() = runBlocking {
        insertEvent(id = 1, songId = SONG_ID, timestamp = TODAY.atTime(9, 0))
        insertEvent(id = 2, songId = SONG_ID, timestamp = TODAY.atTime(10, 0))
        insertEvent(id = 3, songId = SONG_ID, timestamp = TODAY.minusDays(1).atTime(9, 0))
        insertEvent(id = 4, songId = SONG_ID, timestamp = TODAY.minusDays(1).atTime(10, 0))
        insertEvent(id = 5, songId = SONG_ID, timestamp = THIS_MONDAY.atTime(9, 0))
        insertEvent(id = 6, songId = SONG_ID, timestamp = LAST_MONDAY.plusDays(1).atTime(9, 0))
        insertEvent(id = 7, songId = SONG_ID, timestamp = TODAY.minusMonths(1).withDayOfMonth(1).atTime(9, 0))
        insertEvent(id = 8, songId = SONG_ID, timestamp = TODAY.minusMonths(1).withDayOfMonth(20).atTime(9, 0))
        insertEvent(id = 9, songId = OTHER_SONG_ID, timestamp = TODAY.atTime(11, 0))

        val events = historyEvents()

        assertEquals(listOf(9L, 8L, 6L, 5L, 4L, 2L), events.map { it.event.id })
    }

    @Test
    fun `repeated plays do not create repeated song relation graphs`() = runBlocking {
        repeat(2_000) { index ->
            insertEvent(
                id = index.toLong() + 1,
                songId = SONG_ID,
                timestamp = TODAY.atTime(12, 0).plusSeconds(index.toLong()),
            )
        }

        val events = historyEvents()

        assertEquals(1, events.size)
        assertEquals(2_000L, events.single().event.id)
    }

    @Test
    fun `latest event lookup stays bounded to the newest play`() = runBlocking {
        insertEvent(id = 1, songId = SONG_ID, timestamp = TODAY.atTime(9, 0))
        insertEvent(id = 2, songId = OTHER_SONG_ID, timestamp = TODAY.atTime(10, 0))

        val event = database.dao.latestEvent().first()

        assertEquals(2L, event?.event?.id)
    }

    private suspend fun historyEvents() =
        database.dao.historyEvents(
            tomorrowStart = TODAY.plusDays(1).atStartOfDay(),
            todayStart = TODAY.atStartOfDay(),
            yesterdayStart = TODAY.minusDays(1).atStartOfDay(),
            thisMondayStart = THIS_MONDAY.atStartOfDay(),
            lastMondayStart = LAST_MONDAY.atStartOfDay(),
        ).first()

    private fun insertEvent(id: Long, songId: String, timestamp: LocalDateTime) {
        database.dao.insert(
            Event(
                id = id,
                songId = songId,
                timestamp = timestamp,
                playTime = 30_000,
            ),
        )
    }

    private companion object {
        const val SONG_ID = "song"
        const val OTHER_SONG_ID = "other-song"
        val TODAY: LocalDate = LocalDate.of(2026, 8, 26)
        val THIS_MONDAY: LocalDate = TODAY.with(DayOfWeek.MONDAY)
        val LAST_MONDAY: LocalDate = THIS_MONDAY.minusDays(7)
    }
}
