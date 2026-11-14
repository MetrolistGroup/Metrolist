package com.metrolist.shared.storage

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioCacheBudgetTest {
    @Test
    fun completedTemporaryAudioDoesNotReserveItsDiskSpaceAgain() {
        assertEquals(48L, audioCacheBudget(128L, 0L, 32L, 16L, completedTemporaryBytes = 32L))
        assertEquals(128L, audioCacheBudget(128L, 100L, 32L, 16L, completedTemporaryBytes = 32L))
        assertEquals(32L, audioCacheBudget(32L, 0L, 32L, 16L, completedTemporaryBytes = 32L))
    }

    @Test
    fun diskPressureReclaimsCacheEvenWhenTheConfiguredLimitIsUnlimited() {
        assertEquals(110L, audioCacheBudget(-1L, 100L, 30L, 20L))
        assertEquals(90L, audioCacheBudget(-1L, 100L, 10L, 20L))
        assertEquals(80L, audioCacheBudget(80L, 100L, 30L, 20L))
        assertEquals(0L, audioCacheBudget(0L, 100L, 30L, 20L))
    }

    @Test
    fun captureAndFinalCopyBothNeedDiskSpace() {
        assertEquals(70L, audioCacheBudget(200L, 100L, 30L, 20L, 40L))
        assertEquals(0L, audioCacheBudget(-1L, 0L, 10L, 20L, 40L))
    }
}
