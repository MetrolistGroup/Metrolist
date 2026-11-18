package com.metrolist.shared

import com.metrolist.shared.utils.isCrashReportingAvailable

/** Sideloading changes bundle IDs and drops version suffixes, so neither identifies the release. */
fun iosSentryRelease(): String = "com.metrolist.ios@${BuildConfig.APP_VERSION}"

fun iosSentrySourceRevision(): String = BuildConfig.SOURCE_REVISION

fun iosCrashReportingAvailable(): Boolean = isCrashReportingAvailable()
