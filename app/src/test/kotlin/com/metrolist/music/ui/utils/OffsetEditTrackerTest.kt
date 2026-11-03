package com.metrolist.music.ui.utils

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OffsetEditTrackerTest {
    private lateinit var database: InternalDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun insertSong(id: String) {
        database.dao.insert(SongEntity(id = id, title = id))
    }

    private fun offsetOf(id: String): Int =
        database.dao.getSongByIdBlocking(id)!!.song.lyricsOffset

    @Test
    fun `dismiss before switch effect writes only to the original song`() =
        runBlocking(Dispatchers.IO) {
            insertSong("A")
            insertSong("B")

            val tracker = OffsetEditTracker("A")
            tracker.record(120)

            // Dismiss before the song-reset effect runs: the pair still owns A.
            tracker.takePending()?.let { persistOffsetEdit(database.dao, it) }

            assertEquals(120, offsetOf("A"))
            assertEquals(0, offsetOf("B"))
        }

    @Test
    fun `latest value is flushed to the original song on switch`() =
        runBlocking(Dispatchers.IO) {
            insertSong("A")
            insertSong("B")

            val tracker = OffsetEditTracker("A")
            tracker.record(200)
            // Same-frame drag: the mutation site records the newest value.
            tracker.record(300)

            tracker.switchSong("B")?.let { persistOffsetEdit(database.dao, it) }
            tracker.takePending()?.let { persistOffsetEdit(database.dao, it) }

            assertEquals(300, offsetOf("A"))
            assertEquals(0, offsetOf("B"))
        }

    @Test
    fun `song reset then dismissal keeps the new song untouched`() =
        runBlocking(Dispatchers.IO) {
            insertSong("A")
            insertSong("B")

            val tracker = OffsetEditTracker("A")
            tracker.record(120)

            // Song-reset effect: flush A, then switch the tracker to B.
            tracker.switchSong("B")?.let { persistOffsetEdit(database.dao, it) }
            tracker.takePending()?.let { persistOffsetEdit(database.dao, it) }

            assertEquals(120, offsetOf("A"))
            assertEquals(0, offsetOf("B"))
        }

    @Test
    fun `recording after a song switch belongs to the new song`() {
        val tracker = OffsetEditTracker("A")
        tracker.switchSong("B")
        tracker.record(50)

        assertEquals(PendingOffsetEdit("B", 50), tracker.pending)
    }

    @Test
    fun `debounce persists the captured pair only once`() {
        val tracker = OffsetEditTracker("A")
        tracker.record(120)
        val edit = tracker.pending!!

        assertEquals(edit, tracker.takeIfCurrent(edit))
        assertNull(tracker.takePending())
    }

    @Test
    fun `song switch makes the old pair stale for the debounce`() {
        val tracker = OffsetEditTracker("A")
        tracker.record(120)
        val edit = tracker.pending!!

        tracker.switchSong("B")

        assertNull(tracker.takeIfCurrent(edit))
    }
}
