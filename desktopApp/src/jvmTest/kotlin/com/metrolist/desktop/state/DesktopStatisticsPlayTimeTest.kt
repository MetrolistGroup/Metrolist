package com.metrolist.desktop.state

import com.metrolist.desktop.state.playback.DesktopPlaybackHistory
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.getDatabaseBuilder
import com.metrolist.shared.db.historyStatisticsSnapshot
import com.metrolist.shared.db.recordHistoryPlay
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.PropertiesSettings
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopStatisticsPlayTimeTest {
    private var previousSettings: Settings? = null

    @BeforeTest
    fun setUp() {
        previousSettings = Settings.sharedOverrideForTest()
        Settings.replaceSharedForTest(Settings(PropertiesSettings(Properties())))
    }

    @AfterTest
    fun tearDown() = Settings.replaceSharedForTest(previousSettings)

    @Test
    fun finishedSessionsReachTheStatisticsWithTheirMeasuredListeningTime() =
        runBlocking {
            withDatabase { database ->
                var now = START
                val history =
                    DesktopPlaybackHistory({ now }) { song, playTimeMs ->
                        database.recordHistoryPlay(song, now, playTimeMs = playTimeMs)
                    }
                history.restart(first, true)
                now += 200_000
                history.finalizeSession(
                    nextSong = second,
                    isPlaying = true,
                    reportingEnabled = true,
                    minDurationMs = 30_000,
                    pauseHistory = false,
                )
                now += 120_000
                history.finalizeSession(reportingEnabled = true, minDurationMs = 30_000, pauseHistory = false, close = true)

                val snapshot = database.historyStatisticsSnapshot(START, Long.MAX_VALUE)
                assertEquals(2, snapshot.totals.playCount)
                assertEquals(320_000L, snapshot.totals.playTimeMs)
                assertEquals(200_000L, snapshot.songs.single { it.songId == first.id }.playTimeMs)
                assertEquals(120_000L, snapshot.songs.single { it.songId == second.id }.playTimeMs)
            }
        }

    @Test
    fun syncedPlaysAddListenCountsWithoutInventingListeningTime() =
        runBlocking {
            withDatabase { database ->
                var now = START
                val history =
                    DesktopPlaybackHistory({ now }) { song, playTimeMs ->
                        database.recordHistoryPlay(song, now, playTimeMs = playTimeMs)
                    }
                history.restart(first, true)
                now += 90_000
                history.finalizeSession(reportingEnabled = true, minDurationMs = 30_000, pauseHistory = false, close = true)

                // YouTube history only carries the day of a play, so a synced play has no measured
                // duration. A group whose summed time stays zero is shown without a duration.
                database.recordHistoryPlay(first, now, source = "YOUTUBE", token = "synced-first", remoteSynced = true)
                database.recordHistoryPlay(second, now, source = "YOUTUBE", token = "synced-second", remoteSynced = true)

                val snapshot = database.historyStatisticsSnapshot(START, Long.MAX_VALUE)
                assertEquals(3, snapshot.totals.playCount)
                assertEquals(90_000L, snapshot.totals.playTimeMs)
                val measured = snapshot.songs.single { it.songId == first.id }
                assertEquals(2, measured.playCount)
                assertEquals(90_000L, measured.playTimeMs)
                val synced = snapshot.songs.single { it.songId == second.id }
                assertEquals(1, synced.playCount)
                assertEquals(0L, synced.playTimeMs)
            }
        }

    private suspend fun <T> withDatabase(block: suspend (MusicDatabase) -> T): T {
        val root = Files.createTempDirectory("desktop-statistics-play-time").toFile()
        val database = getDatabaseBuilder(root.resolve("room.db")).build()
        try {
            return block(database)
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    private companion object {
        const val START = 1_765_000_000_000L

        val first =
            SongItem("first", "First", null, listOf(ArtistTiny("artist", "Artist")), AlbumTiny("album", "Album"))
        val second =
            SongItem("second", "Second", null, listOf(ArtistTiny("artist", "Artist")), AlbumTiny("album", "Album"))
    }
}
