package com.metrolist.shared.settings

import com.metrolist.shared.backup.withLegacyArtistNameAliases
import com.metrolist.shared.constants.ArtistNameAliasesJsonKey
import com.metrolist.shared.model.ArtistTiny
import com.russhwolf.settings.MapSettings
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ArtistNameAliasesTest {
    @Test
    fun displayNamesReactToEditsWithoutChangingCanonicalMetadata() {
        val settings = Settings(MapSettings())
        Settings.replaceSharedForTest(settings)
        try {
            val artist = ArtistTiny("id", "Original")
            assertEquals("Original", artist.displayName)
            ArtistNameAliases.set("id", "channel", "Original", "Alias")
            assertEquals("Alias", artist.displayName)
            assertEquals("Original", artist.name)
            ArtistNameAliases.clear("id", "channel", "Original")
            assertEquals("Original", artist.displayName)
        } finally {
            Settings.replaceSharedForTest(null)
        }
    }

    @Test
    fun clearingImportedCyclesRemovesEveryConnectedAlias() {
        val aliases = mapOf("id" to "A", "channel" to "B", "name:A" to "B", "name:B" to "A", "other" to "Other")
        assertEquals(mapOf("other" to "Other"), ArtistNameAliases.cleared(aliases, "id", "channel", "Original"))
    }

    @Test
    fun importsLegacyIdsNamesAndUnicodeWithoutNormalizing() {
        val aliases = ArtistNameAliases.deserialize("""{"UC1":"Björk","name:Original":"別名","UC2":""}""")
        assertEquals("Björk", ArtistNameAliases.resolve(aliases, "UC1", "Original"))
        assertEquals("別名", ArtistNameAliases.resolve(aliases, null, "Original"))
        assertEquals("original", ArtistNameAliases.resolve(aliases, null, "original"))
        assertEquals("", ArtistNameAliases.resolve(aliases, "UC2", "Original"))
    }

    @Test
    fun upgradeIgnoresNonStringPreferencesLikeLegacyInitialization() {
        val values = mapOf("UC1" to "Alias", "name:Original" to "Alias", "number" to 5, "flag" to false, "missing" to null)
        assertEquals(mapOf("UC1" to "Alias", "name:Original" to "Alias"), ArtistNameAliases.fromLegacyPreferences(values))
    }

    @Test
    fun repeatedRenameUpdatesArtistChannelAndEarlierNameKeys() {
        val first = ArtistNameAliases.renamed(emptyMap(), "artist", "channel", "Original", "First")
        val second = ArtistNameAliases.renamed(first, "artist", "channel", "First", "Second")
        listOf("artist", "channel", null).forEach { id ->
            assertEquals("Second", ArtistNameAliases.resolve(second, id, "Original"))
        }
        assertEquals("Second", ArtistNameAliases.resolve(second, null, "First"))
    }

    @Test
    fun resolvesImportedChainsAndCyclesWithLegacyPrecedence() {
        val chain = mapOf("id" to "A", "name:Original" to "Wrong", "name:A" to "B", "name:B" to "C")
        assertEquals("C", ArtistNameAliases.resolve(chain, "id", "Original"))
        assertEquals("C", ArtistNameAliases.resolve(chain + ("name:C" to "A"), "id", "Original"))
        assertEquals("A", ArtistNameAliases.resolve(mapOf("id" to "A", "name:A" to "A"), "id", "Original"))
    }

    @Test
    fun clearingRemovesRelatedChainsButPreservesOtherArtists() {
        val first = ArtistNameAliases.renamed(mapOf("other" to "Other alias"), "artist", "channel", "Original", "First")
        val second = ArtistNameAliases.renamed(first, "artist", "channel", "First", "Second")
        val cleared = ArtistNameAliases.cleared(second, "artist", "channel", "Original")
        assertEquals(mapOf("other" to "Other alias"), cleared)
        assertEquals("Original", ArtistNameAliases.resolve(cleared, "artist", "Original"))
    }

    @Test
    fun legacySidecarImportsThroughPortableSettingsAndKmpBackupRoundTrips() {
        val settings = Settings(MapSettings())
        settings.artistNameAliasesJson = """{"stale":"Stale"}"""
        settings.importBackupSettings(null.withLegacyArtistNameAliases("""{"UC1":"Alias","name:Original":"Alias"}"""))
        val backup = settings.exportBackupSettings()
        val restored = Settings(MapSettings())
        restored.importBackupSettings(backup)
        assertEquals(settings.artistNameAliasesJson, restored.artistNameAliasesJson)
        assertEquals("Alias", ArtistNameAliases.resolve(ArtistNameAliases.deserialize(restored.artistNameAliasesJson), null, "Original"))
        assertFalse(ArtistNameAliases.deserialize(restored.artistNameAliasesJson).containsKey("stale"))
    }

    @Test
    fun sidecarReplacesPortableAliasEntryWithoutDuplicatingIt() {
        val original = PortableSettings(entries = listOf(PortableSetting(ArtistNameAliasesJsonKey, SettingType.STRING, "{}")))
        val merged = original.withLegacyArtistNameAliases("""{"id":"Alias"}""")
        assertEquals(1, merged.entries.size)
        assertEquals("Alias", ArtistNameAliases.deserialize(merged.entries.single().value)["id"])
    }

    @Test
    fun malformedImportDoesNotReplaceExistingAliases() {
        val settings = Settings(MapSettings())
        settings.artistNameAliasesJson = """{"id":"Keep"}"""
        listOf("[]", "{", """{"id":null}""", """{"id":{}}""").forEach { malformed ->
            assertFailsWith<SerializationException> { null.withLegacyArtistNameAliases(malformed) }
            assertFailsWith<SerializationException> {
                settings.importBackupSettings(
                    PortableSettings(entries = listOf(PortableSetting(ArtistNameAliasesJsonKey, SettingType.STRING, malformed))),
                )
            }
            assertEquals("""{"id":"Keep"}""", settings.artistNameAliasesJson)
        }
    }

    @Test
    fun olderBackupClearsAliasesAndRollbackRestoresThem() {
        val settings = Settings(MapSettings())
        settings.artistNameAliasesJson = """{"id":"Keep"}"""
        val snapshot = settings.exportBackupSettings()
        settings.importBackupSettings(PortableSettings(entries = emptyList()))
        assertEquals(emptyMap(), ArtistNameAliases.deserialize(settings.artistNameAliasesJson))
        settings.restoreBackupSettingsSnapshot(snapshot)
        assertEquals("""{"id":"Keep"}""", settings.artistNameAliasesJson)
    }
}
