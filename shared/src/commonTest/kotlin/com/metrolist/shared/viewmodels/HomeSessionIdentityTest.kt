package com.metrolist.shared.viewmodels

import com.metrolist.innertubex.InnerTube
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class HomeSessionIdentityTest {
    @Test
    fun suppressedVisitorBootstrapKeepsBothHomeKeysStable() {
        val initial = InnerTube.SessionSnapshot(regionOverrideActive = true)
        val published = initial.copy(visitorData = "visitor", generation = 1)
        assertEquals(initial.homeSessionIdentity(), published.homeSessionIdentity())
        assertEquals(initial.homeCacheSessionKey(), published.homeCacheSessionKey())
        assertNotEquals(initial.homeSessionIdentity(), initial.copy(regionOverrideActive = false).homeSessionIdentity())
        val unsuppressed = initial.copy(regionOverrideActive = false)
        assertNotEquals(unsuppressed.homeCacheSessionKey(), unsuppressed.copy(visitorData = "visitor").homeCacheSessionKey())
    }

    @Test
    fun persistedHomeKeySeparatesAccountsAndBrowseModesWithoutStoringCookies() {
        val session = InnerTube.SessionSnapshot(cookie = "account-one", dataSyncId = "channel-one")
        assertNotEquals(session.homeCacheSessionKey(), session.copy(cookie = "account-two").homeCacheSessionKey())
        assertNotEquals(session.homeCacheSessionKey(), session.copy(dataSyncId = "channel-two").homeCacheSessionKey())
        assertNotEquals(session.homeCacheSessionKey(), session.copy(useLoginForBrowse = true).homeCacheSessionKey())
    }

    @Test
    fun browseIdentityChangesTriggerAHomeReload() {
        val initial = InnerTube.SessionSnapshot(cookie = "cookie", visitorData = "visitor")

        assertNotEquals(initial.homeSessionIdentity(), initial.copy(dataSyncId = "sync").homeSessionIdentity())
        assertNotEquals(initial.homeSessionIdentity(), initial.copy(authUser = "1").homeSessionIdentity())
        assertNotEquals(initial.homeSessionIdentity(), initial.copy(useLoginForBrowse = true).homeSessionIdentity())
    }
}
