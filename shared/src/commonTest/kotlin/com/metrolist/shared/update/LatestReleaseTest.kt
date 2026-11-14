package com.metrolist.shared.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LatestReleaseTest {
    private fun response(tag: String) =
        """{"tag_name":"$tag","html_url":"https://github.com/MetrolistGroup/Metrolist/releases/tag/$tag","assets":[]}"""

    @Test
    fun reportsOnlyANewerParseableRelease() {
        assertEquals("14.0.1", newerLatestRelease(response("v14.0.1"), "14.0.0")?.version)
        assertEquals("14.0.0", newerLatestRelease(response("v14.0.0"), "14.0.0-rc8")?.version)
        assertNull(newerLatestRelease(response("v14.0.0"), "14.0.0"))
        assertNull(newerLatestRelease(response("v13.7.0"), "14.0.0"))
        assertNull(newerLatestRelease(response("nightly"), "14.0.0"))
    }
}
