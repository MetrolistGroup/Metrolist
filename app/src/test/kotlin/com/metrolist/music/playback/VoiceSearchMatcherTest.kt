package com.metrolist.music.playback

import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test


class VoiceSearchMatcherTest {
    private fun song(title: String, vararg artists: String): Song {
        val songEntity = SongEntity(
            id = title.replace(" ", "_").lowercase(),
            title = title,
            duration = 200,
            thumbnailUrl = null,
        )
        val artistEntities = artists.map { name ->
            ArtistEntity(id = name.lowercase(), name = name)
        }
        return Song(
            song = songEntity,
            artists = artistEntities,
            album = null,
        )
    }

    // Jaro-Winkler and Tokenization
    @Test
    fun `jaro-winkler and tokenizer edge cases`() {
        assertEquals("jw: identical strings", 1.0, VoiceSearchMatcher.jaroWinkler("hello", "hello"), 0.0)
        assertEquals("jw: empty vs non-empty", 0.0, VoiceSearchMatcher.jaroWinkler("", "hello"), 0.0)
        assertEquals("jw: non-empty vs empty", 0.0, VoiceSearchMatcher.jaroWinkler("hello", ""), 0.0)
        assertEquals("jw: both empty", 1.0, VoiceSearchMatcher.jaroWinkler("", ""), 0.0)
        assertTrue("jw: similar strings", VoiceSearchMatcher.jaroWinkler("martha", "marhta") > 0.9)
        assertEquals("jw: dissimilar strings", 0.0, VoiceSearchMatcher.jaroWinkler("abc", "xyz"), 0.0)
        assertTrue(
            "jw: boost threshold blocks low jaro",
            VoiceSearchMatcher.jaroWinkler("MARGHERITA", "MARMMELLATA") < 0.7,
        )
        assertEquals(
            "tokenize: strips punctuation",
            setOf("don", "t", "stop", "me", "now"),
            VoiceSearchMatcher.tokenize("Don't Stop Me Now!"),
        )
    }

    // Ranking
    @Test
    fun `exact match scores 1 and is order-independent`() {
        val candidates = listOf(
            song("Bohemian Rhapsody", "Queen"),
            song("Bohemian Like You", "Dandy Warhols"),
        )
        val ranked = VoiceSearchMatcher.rankAll("Bohemian Rhapsody", candidates)
        assertEquals("exact: best title", "Bohemian Rhapsody", ranked.first().song.title)
        assertEquals("exact: best score", 1.0, ranked.first().score, 0.0)

        val reversed = VoiceSearchMatcher.rankAll(
            "Pressure Under",
            listOf(song("Under Pressure", "Queen")),
            )
        assertEquals("exact: reversed order score", 1.0, reversed.first().score, 0.0)
    }
    @Test
    fun `artist tokens are stripped before scoring`() {
        val single = VoiceSearchMatcher.rankAll(
            "The Weeknd Blinding Lights",
            listOf(
                song("Blinding Lights", "The Weeknd"),
                song("Lights", "Ellie Goulding"),
            ),
        )
        assertEquals("artist-strip: best", "Blinding Lights", single.first().song.title)
        assertEquals("artist-strip: score", 0.95, single.first().score, 0.001)

        val multi = VoiceSearchMatcher.rankAll(
            "Queen David Bowie Under Pressure",
            listOf(song("Under Pressure", "Queen", "David Bowie")),
        )
        assertEquals("artist-strip multi: score", 0.95, multi.first().score, 0.001)

        val artistOnly = VoiceSearchMatcher.rankAll(
            "Queen",
            listOf(
                song("Song A", "Queen"),
                song("Song B", "Beatles"),
            ),
        )
        assertEquals("artist-only: score", 0.5, artistOnly.first().score, 0.001)
    }
    @Test
    fun `fuzzy matching and generic-query guard`() {
        val fuzzyBlocked = VoiceSearchMatcher.rankAll("Go", listOf(song("Go Away", "A")))
        assertEquals("generic: fuzzy blocked", 0.0, fuzzyBlocked.first().score, 0.0)
        val exactGeneric = VoiceSearchMatcher.rankAll("Go", listOf(song("Go", "A")))
        assertEquals("generic: exact still allowed", 1.0, exactGeneric.first().score, 0.0)

        val typo = VoiceSearchMatcher.rankAll(
            "Bohemian Rapsody",
            listOf(song("Bohemian Rhapsody", "Queen")),
        )
        assertTrue("fuzzy typo: above threshold", typo.first().score >= VoiceSearchMatcher.STRONG_MATCH_THRESHOLD)

        val punct = VoiceSearchMatcher.rankAll(
            "Dont Stop Me Now",
            listOf(song("Don't Stop Me Now!", "Some artist")),
        )
        assertTrue("punctuation: above threshold", punct.first().score >= VoiceSearchMatcher.STRONG_MATCH_THRESHOLD)
    }
    @Test
    fun `edge cases - no match, empty inputs, best not first`() {
        assertNull(
            "no-match: findBest returns null",
            VoiceSearchMatcher.findBest(
                "Bohemian Rhapsody",
                listOf(song("Completely Different", "Nobody"))
            ),
        )

        assertTrue("empty: no candidates", VoiceSearchMatcher.rankAll("Hello", emptyList()).isEmpty())
        assertTrue("empty: blank query", VoiceSearchMatcher.rankAll("", listOf(song("S", "A"))).isEmpty())

        val best = VoiceSearchMatcher.findBest(
            "Bohemian Rhapsody",
            listOf(
                song("Unrelated Song", "Nobody"),
                song("Bohemian Rhapsody", "Queen"),
                song("Another Song", "Someone"),
            ),
        )
        assertEquals("not-first: correct winner", "Bohemian Rhapsody", best?.title)
    }
}