package com.metrolist.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupPresentationTest {
    private fun home() =
        StartupPresentation(100L).apply {
            contentLaidOut = true
            homeRequired = true
            homeLaidOut = true
        }

    @Test
    fun cachedHomeAndAvatarReleaseWithoutWaitingForNetwork() {
        val presentation = home().apply { avatarReady = false }
        assertTrue(presentation.keepSplash(200, true, false, false))
        presentation.homeCacheReady = true
        assertTrue(presentation.keepSplash(200, true, false, false))
        presentation.avatarReady = true
        assertFalse(presentation.keepSplash(200, true, false, false))
    }

    @Test
    fun missingCacheReleasesToLaidOutSkeleton() {
        val presentation = home().apply { homeCacheReady = true }
        assertFalse(presentation.keepSplash(200, true, false, false))
    }

    @Test
    fun deadlineReleasesSlowCacheAndAvatarToSkeleton() {
        val presentation = home().apply { avatarReady = false }
        assertTrue(presentation.keepSplash(1_299, true, false, false))
        assertFalse(presentation.keepSplash(1_300, true, false, false))
    }

    @Test
    fun deadlineNeverExposesAnUninitializedOrUnlaidOutWindow() {
        val presentation = home()
        assertTrue(presentation.keepSplash(5_000, false, false, false))
        presentation.homeLaidOut = false
        assertTrue(presentation.keepSplash(5_000, true, false, false))
        presentation.homeLaidOut = true
        presentation.contentLaidOut = false
        assertTrue(presentation.keepSplash(5_000, true, false, false))
    }

    @Test
    fun recoveryAndRestoreBypassEveryPresentationWait() {
        val presentation = StartupPresentation(100)
        assertFalse(presentation.keepSplash(100, false, true, false))
        assertFalse(presentation.keepSplash(100, false, false, true))
    }

    @Test
    fun onboardingAndOtherStartupTabsDoNotWaitForHome() {
        val presentation = StartupPresentation(100)
        assertTrue(presentation.keepSplash(200, true, false, false))
        presentation.contentLaidOut = true
        assertFalse(presentation.keepSplash(200, true, false, false))
    }
}
