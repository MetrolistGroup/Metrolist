package com.metrolist.shared.update

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseVersionTest {
    @Test
    fun recoveryOffersNewerReleaseCandidatesAndStableReleasesInOrder() {
        val versions = listOf("14.0.0-beta2", "14.0.0-rc6", "14.0.0-rc7", "14.0.0-rc10", "14.0.0", "14.0.1")
        versions.zipWithNext().forEach { (older, newer) ->
            assertTrue(requireNotNull(ReleaseVersion.parse(newer)) > requireNotNull(ReleaseVersion.parse(older)), "$newer > $older")
        }
        assertNull(ReleaseVersion.parse("not a release"))
    }
}
