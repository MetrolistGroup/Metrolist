package com.metrolist.shared.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashReportingTest {
    @Test
    fun plainReleaseVersionsAllowReporting() {
        assertTrue(isCrashReportingAvailable("14.0.0"))
        assertTrue(isCrashReportingAvailable("14.1.2"))
    }

    @Test
    fun buildMetadataDisablesReporting() {
        assertFalse(isCrashReportingAvailable("14.0.0+24897bc"))
        assertFalse(isCrashReportingAvailable("14.1.2+build.123"))
    }
}
