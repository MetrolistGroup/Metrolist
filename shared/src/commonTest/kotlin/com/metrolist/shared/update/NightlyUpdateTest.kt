package com.metrolist.shared.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NightlyUpdateTest {
    @Test
    fun nightlyBuildsFollowNightlyUnlessTheChannelIsSet() {
        assertTrue(followsNightlyUpdates("", "14.0.0+a0d7aaf"))
        assertFalse(followsNightlyUpdates("", "14.0.0"))
        assertTrue(followsNightlyUpdates(UPDATE_CHANNEL_NIGHTLY, "14.0.0"))
        assertFalse(followsNightlyUpdates(UPDATE_CHANNEL_STABLE, "14.0.0+a0d7aaf"))
    }

    @Test
    fun aChangedDigestIsAnUpdateUntilTheRunningCommitMatches() {
        val commit = "a0d7aafecbd24d689012e8dc599dd2ffdd6e700e"
        assertEquals(NightlyCheck(true, "sha256:old"), checkNightly("14.0.0+4bc01b7", commit, "sha256:new", "sha256:old"))
        assertEquals(NightlyCheck(false, "sha256:new"), checkNightly("14.0.0+a0d7aaf", commit, "sha256:new", "sha256:old"))
        assertEquals(NightlyCheck(false, "sha256:new"), checkNightly("14.0.0+4bc01b7", commit, "sha256:new", "sha256:new"))
        assertEquals(NightlyCheck(true, ""), checkNightly("14.0.0", commit, "sha256:new", ""))
        assertEquals(NightlyCheck(false, ""), checkNightly("14.0.0", commit, null, ""))
    }
}
