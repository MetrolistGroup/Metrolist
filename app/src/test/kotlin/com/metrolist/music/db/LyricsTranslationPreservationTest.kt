/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.entities.LyricsEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Overwriting cached lyrics must keep AI translations: plain upsert() replaces the
 * whole row and would silently wipe them. NOT_FOUND markers must never become rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LyricsTranslationPreservationTest {
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

    @Test
    fun `updateLyricsText keeps translations while replacing text and provider`() =
        runBlocking {
            withContext(Dispatchers.IO) {
                database.dao.upsert(
                    LyricsEntity(
                        id = "song",
                        lyrics = "[00:01.00] old",
                        provider = "LrcLib",
                        translatedLyrics = "eski çeviri",
                        translationLanguage = "tr",
                        translationMode = "Literal",
                    )
                )
            }

            val updated = withContext(Dispatchers.IO) {
                database.dao.updateLyricsText("song", "[00:01.00] new", "BetterLyrics")
            }
            assertEquals(1, updated)

            val row = withContext(Dispatchers.IO) {
                database.dao.lyrics("song").first()!!
            }
            assertEquals("[00:01.00] new", row.lyrics)
            assertEquals("BetterLyrics", row.provider)
            assertEquals("eski çeviri", row.translatedLyrics)
            assertEquals("tr", row.translationLanguage)
            assertEquals("Literal", row.translationMode)
        }

    @Test
    fun `plain upsert wipes translations (documents why update path exists)`() =
        runBlocking {
            withContext(Dispatchers.IO) {
                database.dao.upsert(
                    LyricsEntity(
                        id = "song",
                        lyrics = "[00:01.00] old",
                        provider = "LrcLib",
                        translatedLyrics = "eski çeviri",
                        translationLanguage = "tr",
                        translationMode = "Literal",
                    )
                )
                database.dao.upsert(LyricsEntity("song", "[00:01.00] new", "BetterLyrics"))
            }

            val row = withContext(Dispatchers.IO) {
                database.dao.lyrics("song").first()!!
            }
            assertEquals("", row.translatedLyrics)
        }

    @Test
    fun `updateLyricsText on absent row affects nothing`() =
        runBlocking {
            val updated = withContext(Dispatchers.IO) {
                database.dao.updateLyricsText("ghost", "[00:01.00] x", "LrcLib")
            }
            assertEquals(0, updated)
            val row = withContext(Dispatchers.IO) {
                database.dao.lyrics("ghost").first()
            }
            assertEquals(null, row)
        }

    @Test
    fun `replaceLyrics keeps translations for identical text`() =
        runBlocking {
            withContext(Dispatchers.IO) {
                database.dao.upsert(
                    LyricsEntity(
                        id = "song",
                        lyrics = "[00:01.00] old",
                        provider = "LrcLib",
                        translatedLyrics = "eski çeviri",
                        translationLanguage = "tr",
                        translationMode = "Literal",
                    )
                )
                database.dao.replaceLyrics("song", "[00:01.00] old", "BetterLyrics")
            }

            val row = withContext(Dispatchers.IO) {
                database.dao.lyrics("song").first()!!
            }
            assertEquals("[00:01.00] old", row.lyrics)
            assertEquals("BetterLyrics", row.provider)
            assertEquals("eski çeviri", row.translatedLyrics)
        }

    @Test
    fun `replaceLyrics drops translations for changed text`() =
        runBlocking {
            withContext(Dispatchers.IO) {
                database.dao.upsert(
                    LyricsEntity(
                        id = "song",
                        lyrics = "[00:01.00] old",
                        provider = "LrcLib",
                        translatedLyrics = "eski çeviri",
                        translationLanguage = "tr",
                        translationMode = "Literal",
                    )
                )
                database.dao.replaceLyrics("song", "[00:01.00] new words", "BetterLyrics")
            }

            val row = withContext(Dispatchers.IO) {
                database.dao.lyrics("song").first()!!
            }
            assertEquals("[00:01.00] new words", row.lyrics)
            assertEquals("BetterLyrics", row.provider)
            assertEquals("", row.translatedLyrics)
            assertEquals("", row.translationLanguage)
            assertEquals("", row.translationMode)
        }

    @Test
    fun `replaceLyrics inserts absent rows`() =
        runBlocking {
            withContext(Dispatchers.IO) {
                database.dao.replaceLyrics("ghost", "[00:01.00] x", "LrcLib")
            }

            val row = withContext(Dispatchers.IO) {
                database.dao.lyrics("ghost").first()!!
            }
            assertEquals("[00:01.00] x", row.lyrics)
            assertEquals("LrcLib", row.provider)
        }

    @Test
    fun `attachTranslation attaches when text matches`() =
        runBlocking {
            withContext(Dispatchers.IO) {
                database.dao.upsert(
                    LyricsEntity(
                        id = "song",
                        lyrics = "[00:01.00] old",
                        provider = "LrcLib",
                    )
                )
            }

            val updated = withContext(Dispatchers.IO) {
                database.dao.attachTranslationIfTextMatches(
                    "song",
                    "[00:01.00] old",
                    "eski çeviri",
                    "tr",
                    "Literal",
                )
            }
            assertEquals(1, updated)

            val row = withContext(Dispatchers.IO) {
                database.dao.lyrics("song").first()!!
            }
            assertEquals("[00:01.00] old", row.lyrics)
            assertEquals("LrcLib", row.provider)
            assertEquals("eski çeviri", row.translatedLyrics)
            assertEquals("tr", row.translationLanguage)
            assertEquals("Literal", row.translationMode)
        }

    @Test
    fun `attachTranslation skips when text changed`() =
        runBlocking {
            withContext(Dispatchers.IO) {
                database.dao.upsert(
                    LyricsEntity(
                        id = "song",
                        lyrics = "[00:01.00] old",
                        provider = "LrcLib",
                    )
                )
                database.dao.replaceLyrics("song", "[00:01.00] new words", "BetterLyrics")
            }

            val updated = withContext(Dispatchers.IO) {
                database.dao.attachTranslationIfTextMatches(
                    "song",
                    "[00:01.00] old",
                    "eski çeviri",
                    "tr",
                    "Literal",
                )
            }
            assertEquals(0, updated)

            val row = withContext(Dispatchers.IO) {
                database.dao.lyrics("song").first()!!
            }
            assertEquals("[00:01.00] new words", row.lyrics)
            assertEquals("", row.translatedLyrics)
            assertEquals("", row.translationLanguage)
            assertEquals("", row.translationMode)
        }

    @Test
    fun `attachTranslation on absent row affects nothing`() =
        runBlocking {
            val updated = withContext(Dispatchers.IO) {
                database.dao.attachTranslationIfTextMatches(
                    "ghost",
                    "[00:01.00] x",
                    "çeviri",
                    "tr",
                    "Literal",
                )
            }
            assertEquals(0, updated)
            val row = withContext(Dispatchers.IO) {
                database.dao.lyrics("ghost").first()
            }
            assertEquals(null, row)
        }

    @Test
    fun `clearTranslationsIfTextMatches clears on match, skips on mismatch`() =
        runBlocking {
            withContext(Dispatchers.IO) {
                database.dao.upsert(
                    LyricsEntity(
                        id = "song",
                        lyrics = "[00:01.00] old",
                        provider = "LrcLib",
                        translatedLyrics = "eski çeviri",
                        translationLanguage = "tr",
                        translationMode = "Literal",
                    )
                )
            }

            val cleared = withContext(Dispatchers.IO) {
                database.dao.clearTranslationsIfTextMatches("song", "[00:01.00] old")
            }
            assertEquals(1, cleared)

            var row = withContext(Dispatchers.IO) {
                database.dao.lyrics("song").first()!!
            }
            assertEquals("", row.translatedLyrics)
            assertEquals("", row.translationLanguage)
            assertEquals("", row.translationMode)
            assertEquals("[00:01.00] old", row.lyrics)

            withContext(Dispatchers.IO) {
                database.dao.upsert(
                    LyricsEntity(
                        id = "song",
                        lyrics = "[00:01.00] old",
                        provider = "LrcLib",
                        translatedLyrics = "eski çeviri",
                        translationLanguage = "tr",
                        translationMode = "Literal",
                    )
                )
            }

            val skipped = withContext(Dispatchers.IO) {
                database.dao.clearTranslationsIfTextMatches("song", "[00:01.00] other words")
            }
            assertEquals(0, skipped)

            row = withContext(Dispatchers.IO) {
                database.dao.lyrics("song").first()!!
            }
            assertEquals("eski çeviri", row.translatedLyrics)
            assertEquals("tr", row.translationLanguage)
            assertEquals("Literal", row.translationMode)
        }
}
