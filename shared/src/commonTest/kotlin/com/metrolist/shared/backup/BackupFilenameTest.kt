package com.metrolist.shared.backup

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class BackupFilenameTest {
    @Test
    fun timestampPadsEveryFieldAndOmitsFractionalSeconds() {
        assertEquals(
            "metrolist-backup-20260203-040506",
            defaultBackupFilename("metrolist-backup", Instant.parse("2026-02-03T04:05:06.789Z"), TimeZone.UTC),
        )
    }

    @Test
    fun timestampUsesLocalDateAcrossTheYearBoundary() {
        assertEquals(
            "metrolist-backup-20270101-000203",
            defaultBackupFilename("metrolist-backup", Instant.parse("2026-12-31T23:02:03Z"), TimeZone.of("Europe/Warsaw")),
        )
    }
}
