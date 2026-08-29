package com.metrolist.music.lyrics

import com.metrolist.music.lyrics.LyricsUtils.isMacedonian
import com.metrolist.music.lyrics.LyricsUtils.romanize
import com.metrolist.music.lyrics.LyricsUtils.romanizeCyrillic
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Macedonian writes the stressed forms of "e" and "i" with a grave accent, so that
 * "everything" is spelled differently from "is" and "her" from "and". Both letters are
 * part of the alphabet and appear in ordinary lyrics.
 */
@RunWith(RobolectricTestRunner::class)
class MacedonianRomanizationTest {

    /** "Everything I have, both a house and a field" - the accent sits on the first word. */
    private val lineWithGraveE = "Сѐ што имам и куќа и нива"

    /** "her" - the grave separates the pronoun from the conjunction. */
    private val lineWithGraveI = "Ги виде ѝ рече"

    @Test
    fun `a line carrying the grave accented e is still recognised as Macedonian`() {
        assertTrue(
            "expected Macedonian detection for a line spelled with the grave accent",
            isMacedonian(lineWithGraveE),
        )
    }

    @Test
    fun `a line carrying the grave accented i is still recognised as Macedonian`() {
        assertTrue(
            "expected Macedonian detection for a line spelled with the grave accent",
            isMacedonian(lineWithGraveI),
        )
    }

    /**
     * Detection runs over the whole lyrics, so a single accented letter anywhere used to
     * make every language check fail and left the entire song unromanised.
     */
    @Test
    fun `lyrics containing a grave accent are romanised rather than dropped`() = runBlocking {
        val romanized = romanizeCyrillic(lineWithGraveE)

        assertNotNull("expected the line to be romanised", romanized)
        assertTrue(
            "expected latin output, got $romanized",
            romanized!!.none { it in 'Ѐ'..'ӿ' },
        )
    }

    @Test
    fun `the grave accented vowels romanise to their plain latin letters`() = runBlocking {
        assertEquals("Se shto imam i kukja i niva", romanizeCyrillic(lineWithGraveE, "Macedonian"))
    }

    /**
     * The reported song. Detection runs over the whole lyrics, so every line has to be
     * read with that one alphabet - a line that happens to use only letters shared with
     * Russian or Serbian must not be re-read as those languages.
     */
    private val macedonianSong = listOf(
        "Една цреша што мене ме чека",
        "Сѐ што имам и куќа и нива",
        "Но црешата да ја најдам жива",
        "Што е тоа во луѓето тажно",
    )

    @Test
    fun `every line of a song is read with the same alphabet`() = runBlocking {
        val lyrics = macedonianSong.joinToString(System.lineSeparator())
        val romanized = macedonianSong.map {
            romanize(lyrics, it, listOf("Macedonian"), romanizeCyrillicByLine = false)
        }

        romanized.forEachIndexed { index, value ->
            assertNotNull("line ${index + 1} was left unromanised", value)
        }

        // Macedonian reads ц as "c"; reading the line as Russian would give "ts".
        assertTrue("got ${romanized[0]}", romanized[0]!!.contains("cresha"))
        // Macedonian reads ж as "zh"; reading the line as Serbian would give "ž".
        assertTrue("got ${romanized[2]}", romanized[2]!!.contains("zhiva"))
        assertTrue("got ${romanized[3]}", romanized[3]!!.contains("tazhno"))
    }
}
