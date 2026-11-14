package com.metrolist.shared.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupFormatTest {
    @Test
    fun realisticDatabaseSizesFitWhileUncompressedLimitsStillApply() {
        for (size in listOf(1L, 7L, 256L, 512L)) {
            requireBackupSize(size * 1024 * 1024, BackupArchiveLimits.DATABASE_BYTES)
        }
        for (limit in listOf(BackupArchiveLimits.DATABASE_BYTES, BackupArchiveLimits.TOTAL_UNCOMPRESSED_BYTES)) {
            assertEquals(
                BackupValidationFailure.TOO_LARGE,
                assertFailsWith<BackupValidationException> { requireBackupSize(limit + 1, limit) }.reason,
            )
        }
    }

    @Test
    fun validationFailuresKeepDistinctReasonsForLocalizedRestoreMessages() {
        assertEquals(
            BackupValidationFailure.UNSUPPORTED_FORMAT,
            assertFailsWith<BackupValidationException> { BackupManifest(999, BackupPlatform.IOS).requireSupportedFormat() }.reason,
        )
        assertEquals(
            BackupValidationFailure.UNSUPPORTED_DATABASE,
            assertFailsWith<BackupValidationException> { requireSupportedBackupDatabaseVersion(999, 51) }.reason,
        )
        assertEquals(
            BackupValidationFailure.TOO_LARGE,
            assertFailsWith<BackupValidationException> { requireBackupSize(101, 100) }.reason,
        )
        requireBackupSize(100, 100)
        requireSupportedBackupDatabaseVersion(1, 51)
        requireSupportedBackupDatabaseVersion(51, 51)
    }

    @Test
    fun currentAndOlderFormatsAreSupported() {
        BackupManifest(1, BackupPlatform.ANDROID).requireSupportedFormat()
        BackupManifest(BackupManifest.CURRENT_FORMAT_VERSION, BackupPlatform.DESKTOP).requireSupportedFormat()
        BackupManifest(BackupManifest.CURRENT_FORMAT_VERSION, BackupPlatform.IOS).requireSupportedFormat()
    }

    @Test
    fun backupPathNormalizationRemovesDotSegmentsAndRejectsRelativePaths() {
        assertEquals(
            "/Application Support/metrolist/metrolist.db",
            normalizeAbsoluteBackupPath("/Application Support/metrolist/../metrolist/metrolist.db"),
        )
        assertEquals("/foo", normalizeAbsoluteBackupPath("/../../foo"))
        assertFailsWith<IllegalArgumentException> { normalizeAbsoluteBackupPath("relative/path") }
    }

    @Test
    fun databaseCompatibilityUsesVersionAndRoomIdentity() {
        assertTrue(shouldConvertRestoredDatabase(44, "old", 45, "current"))
        assertTrue(shouldConvertRestoredDatabase(45, null, 45, "current"))
        assertTrue(shouldConvertRestoredDatabase(45, "current", 45, null))
        assertTrue(shouldConvertRestoredDatabase(45, "legacy", 45, "current"))
        assertFalse(shouldConvertRestoredDatabase(45, "current", 45, "current"))
    }

    @Test
    fun databaseLikesMigrateOnlyWhenBackupPredatesPortableLikes() {
        assertTrue(shouldMigrateDatabaseLikes(null))
        assertTrue(shouldMigrateDatabaseLikes(setOf("themeMode")))
        assertFalse(shouldMigrateDatabaseLikes(setOf(LIKED_SONGS_SETTING_KEY)))
    }

    @Test
    fun newerDatabaseVersionIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            shouldConvertRestoredDatabase(46, "future", 45, "current")
        }
    }

    @Test
    fun legacySpeedDialIndexAvoidsWindowFunctionsAndEscapesTheTable() {
        val expression = legacySpeedDialIndexExpression("speed_\"dial")

        assertFalse(expression.contains("OVER", ignoreCase = true))
        assertTrue(expression.contains("legacy.\"speed_\"\"dial\""))
        assertTrue(legacyTableSourceExpression("speed_\"dial", needsSpeedDialIndex = true).endsWith(" AS legacy_speed_dial"))
    }
}
