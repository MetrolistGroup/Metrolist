package com.metrolist.shared.ui.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class LinkifyTest {
    @Test
    fun mixedCaseHttpSchemesAreLinkedWithoutLeakingTheSchemeIntoTheLabel() {
        for ((scheme, normalized) in listOf("HTTPS" to "https", "HtTp" to "http")) {
            assertEquals(
                listOf(LinkSegment("Read "), LinkSegment("example.org", "$normalized://example.org/CaseSensitive?Value=Q")),
                "Read $scheme://example.org/CaseSensitive?Value=Q".toLinkSegments(),
            )
        }
    }

    @Test
    fun protocolRelativeLinksStillDefaultToHttps() {
        assertEquals(
            listOf(LinkSegment("Read "), LinkSegment("example.org", "https://example.org/info")),
            "Read //example.org/info".toLinkSegments(),
        )
    }
}
