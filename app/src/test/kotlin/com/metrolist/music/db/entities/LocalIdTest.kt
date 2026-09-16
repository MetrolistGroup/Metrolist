package com.metrolist.music.db.entities

import org.junit.Assert.assertTrue
import org.junit.Test

class LocalIdTest {
    @Test
    fun generatesPrefixedLetterIds() {
        assertTrue(generateLocalId("LP").matches(Regex("LP[A-Za-z]{8}")))
    }
}
