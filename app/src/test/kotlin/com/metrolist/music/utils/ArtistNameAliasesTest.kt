/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistNameAliasesTest {
    @Test
    fun `artist id alias takes precedence over name alias`() {
        val aliases =
            mapOf(
                "artist-id" to "ID alias",
                "name:Original name" to "Name alias",
            )

        assertEquals(
            "ID alias",
            ArtistNameAliases.resolve(aliases, "artist-id", "Original name"),
        )
    }

    @Test
    fun `name alias applies when artist id is missing`() {
        val aliases = mapOf("name:Original name" to "Renamed artist")

        assertEquals(
            "Renamed artist",
            ArtistNameAliases.resolve(aliases, null, "Original name"),
        )
    }

    @Test
    fun `original name is retained when no alias exists`() {
        assertEquals(
            "Original name",
            ArtistNameAliases.resolve(emptyMap(), "artist-id", "Original name"),
        )
    }

    @Test
    fun `chained renames resolve to the latest name`() {
        val aliases =
            mapOf(
                "artist-id" to "First rename",
                "name:First rename" to "Second rename",
            )

        assertEquals(
            "Second rename",
            ArtistNameAliases.resolve(aliases, "artist-id", "Original name"),
        )
    }
}
