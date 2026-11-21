package com.metrolist.shared.update

const val NIGHTLY_TAG = "nightly"
const val UPDATE_CHANNEL_STABLE = "stable"
const val UPDATE_CHANNEL_NIGHTLY = "nightly"

/** The short commit in a nightly version such as `14.0.0+a0d7aaf`. */
fun nightlyBuildCommit(version: String): String? =
    version.substringAfter('+', "").takeIf { commit -> commit.length >= 7 && commit.all { it in '0'..'9' || it in 'a'..'f' } }

/** An unset channel follows the kind of build that is running. */
fun followsNightlyUpdates(
    channel: String,
    currentVersion: String,
): Boolean =
    when (channel) {
        UPDATE_CHANNEL_NIGHTLY -> true
        UPDATE_CHANNEL_STABLE -> false
        else -> nightlyBuildCommit(currentVersion) != null
    }

data class NightlyCheck(
    val available: Boolean,
    val installedDigest: String,
)

/**
 * The nightly tag moves on every build, so the package digest tells whether the published package changed since the
 * one this install last confirmed. A nightly built from the running commit confirms its digest as installed.
 */
fun checkNightly(
    currentVersion: String,
    targetCommit: String?,
    assetDigest: String?,
    installedDigest: String,
): NightlyCheck {
    val commit = nightlyBuildCommit(currentVersion)
    return when {
        assetDigest.isNullOrBlank() || assetDigest == installedDigest -> NightlyCheck(false, installedDigest)
        commit != null && targetCommit?.startsWith(commit) == true -> NightlyCheck(false, assetDigest)
        else -> NightlyCheck(true, installedDigest)
    }
}
