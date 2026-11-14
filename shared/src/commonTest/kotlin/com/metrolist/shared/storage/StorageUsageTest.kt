package com.metrolist.shared.storage

import kotlin.test.Test
import kotlin.test.assertEquals

class StorageUsageTest {
    @Test
    fun legacyUnlimitedSongCacheMapsToTheLargestLimit() {
        assertEquals(SongCacheLimitsMb.lastIndex, songCacheLimitIndex(-1))
        assertEquals(SongCacheLimitsMb.indexOf(1024), songCacheLimitIndex(1024))
        assertEquals(16_384, SongCacheLimitsMb.last())
        assertEquals((16_384L + 512 + 256) * 1024 * 1024, cacheAllocationBytes(-1, 512, 256))
    }
}
