package com.metrolist.shared.settings

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosSettingsPolicyTest {
    @Test
    fun appLanguageUsesSelectedOrPreferredSystemLocale() {
        assertEquals("fr-CA", resolveAppLanguageTag("fr-ca", listOf("ja")))
        assertEquals("ja-JP", resolveAppLanguageTag("SYSTEM", listOf("ja-JP", "en")))
        assertEquals("de-DE", resolveAppLanguageTag("SYSTEM", listOf("de-DE")))
        assertEquals("ja-JP", resolveAppLanguageTag(" system ", listOf("ja-JP")))
        assertEquals("de-DE", resolveAppLanguageTag("", listOf("de-DE")))
        assertEquals("sw-TZ", resolveAppLanguageTag("SYSTEM", listOf("sw-TZ")))
        assertEquals("hi-Latn", resolveAppLanguageTag("hin", listOf("ja")))
        assertEquals("hi-Latn", resolveAppLanguageTag("SYSTEM", listOf("hin")))
    }

    @Test
    fun localeRegionQualifierUsesOnlyExplicitRegionSubtags() {
        assertEquals("", localeRegionQualifier("es"))
        assertEquals("US", localeRegionQualifier("es-US"))
        assertEquals("TW", localeRegionQualifier("zh-Hant-TW"))
        assertEquals("", localeRegionQualifier("es-u-ca-gregory"))
    }

    @Test
    fun diagnosticMessagesRedactCredentialsAndUrls() {
        assertEquals(
            "Request <redacted-url> Bearer <redacted> token=<redacted>",
            sanitizeIosDiagnosticMessage("Request https://example.test/?key=value Bearer abc123 token=secret"),
        )
    }

    @Test
    fun previousScreenOnPreferenceMigratesWithoutOverwritingNewMode() {
        val backing = MapSettings()
        backing.putBoolean("keepScreenOn", true)
        assertEquals(KeepScreenOnMode.PLAYER_AND_LYRICS, Settings(backing).keepScreenOnMode)
        assertFalse(backing.hasKey("keepScreenOn"))
        assertEquals(KeepScreenOnMode.NEVER, Settings(MapSettings()).keepScreenOnMode)
        backing.putInt("keepScreenOnMode", KeepScreenOnMode.LYRICS.ordinal)
        backing.putBoolean("keepScreenOn", true)
        assertEquals(KeepScreenOnMode.LYRICS, Settings(backing).keepScreenOnMode)
    }

    @Test
    fun appLanguageAndImportedFontSettingsPersist() {
        val backing = MapSettings()
        val settings = Settings(backing)
        settings.appLanguage = "ja"
        settings.iosCustomFontFile = "app_font.otf"
        settings.iosCustomFontName = "Book Serif"
        settings.iosCustomFontRevision = 1L

        val restored = Settings(backing)
        assertEquals("ja", restored.appLanguage)
        assertEquals("app_font.otf", restored.iosCustomFontFile)
        assertEquals("Book Serif", restored.iosCustomFontName)
        assertEquals(1L, restored.iosCustomFontRevision)
        assertFalse(settings.exportBackupSettings().entries.any { it.key.startsWith("iosCustomFont") })
    }

    @Test
    fun boundedFileCopyReadsAtMostOneBytePastItsLimit() {
        assertEquals(IOS_FILE_COPY_CHUNK_BYTES, nextIosFileCopyChunkSize(0, Long.MAX_VALUE))
        assertEquals(2, nextIosFileCopyChunkSize(9, 10))
        assertEquals(1, nextIosFileCopyChunkSize(10, 10))
        assertFailsWith<IllegalArgumentException> { nextIosFileCopyChunkSize(11, 10) }
    }

    @Test
    fun fontImportValidatesTypeSizeAndSafeFileName() {
        val ttfHeader = byteArrayOf(0, 1, 0, 0)
        val otfHeader = "OTTO".encodeToByteArray()

        assertTrue(isValidFontImport("custom.ttf", ttfHeader.size.toLong(), ttfHeader))
        assertTrue(isValidFontImport("custom.otf", otfHeader.size.toLong(), otfHeader))
        assertFalse(isValidFontImport("../custom.ttf", ttfHeader.size.toLong(), ttfHeader))
        assertFalse(isValidFontImport("custom.ttc", ttfHeader.size.toLong(), ttfHeader))
        assertFalse(isValidFontImport("custom.ttf", 0, ttfHeader))
        assertFalse(isValidFontImport("custom.ttf", 20L * 1024 * 1024 + 1, ttfHeader))
        assertFalse(isValidFontImport("custom.ttf", ttfHeader.size.toLong(), byteArrayOf(1, 2, 3, 4)))
    }
}
