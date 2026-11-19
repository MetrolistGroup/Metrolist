package com.metrolist.shared.backup

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoBackupScheduleTest {
    @Test
    fun backupIsDueWhenNoPreviousSuccessExists() {
        assertTrue(isAutoBackupDue(0, 1, AutoBackupFrequency.DAILY))
    }

    @Test
    fun backupIsDueAtTheConfiguredInterval() {
        val lastSuccess = 10_000L
        assertFalse(isAutoBackupDue(lastSuccess, lastSuccess + AutoBackupFrequency.WEEKLY.intervalMillis - 1, AutoBackupFrequency.WEEKLY))
        assertTrue(isAutoBackupDue(lastSuccess, lastSuccess + AutoBackupFrequency.WEEKLY.intervalMillis, AutoBackupFrequency.WEEKLY))
    }

    @Test
    fun clockMovingBackwardsDoesNotTriggerBackup() {
        assertFalse(isAutoBackupDue(2_000, 1_000, AutoBackupFrequency.MONTHLY))
    }

    @Test
    fun unknownFrequencySettingIsRejected() {
        assertFailsWith<IllegalStateException> { AutoBackupFrequency.fromSetting("unknown") }
    }
}
