/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopUpdateManagerTest {
    @Test
    fun releaseListSupportsPrereleaseOnlyRepositoriesAndStableInstallerSelection() {
        val candidates = """[
            {"tag_name":"nightly","draft":false,"prerelease":true,"assets":[]},
            {"tag_name":"v14.0.0-rc7","draft":false,"prerelease":true,"assets":[]},
            {"tag_name":"v14.0.0-rc10","draft":false,"prerelease":true,"assets":[]},
            {"tag_name":"v14.0.0","draft":true,"prerelease":false,"assets":[]},
            {"tag_name":"v13.7.0","draft":false,"prerelease":false,"assets":[]}
        ]"""
        assertEquals("14.0.0-rc10", DesktopUpdateManager.findUpdate(candidates, "14.0.0-rc6")?.version)
        assertNull(DesktopUpdateManager.findUpdate(candidates, "14.0.0-rc10"))
        assertNull(DesktopUpdateManager.findUpdate(candidates, "14.0.0-rc6", includePrereleases = false))
        assertEquals("13.7.0", DesktopUpdateManager.findUpdate(candidates, "13.6.0", includePrereleases = false)?.version)
    }

    @Test
    fun detectsRecoveryUpdatesBetweenReleaseCandidates() {
        assertTrue(isNewerDesktopVersion("14.0.0-rc10", "14.0.0-rc6"))
        assertTrue(isNewerDesktopVersion("14.0.0", "14.0.0-rc6"))
        assertFalse(isNewerDesktopVersion("14.0.0-rc6", "14.0.0"))
        assertFalse(isNewerDesktopVersion("14.0.0-rc6", "14.0.0-rc6"))
    }

    @Test
    fun portableAndDevelopmentProcessesCannotUpdateRegisteredInstallation() {
        val directory = Files.createTempDirectory("installed-desktop")
        try {
            val installed = Files.createDirectories(directory.resolve("installed/app")).resolve("Metrolist.exe")
            val portable = Files.createDirectories(directory.resolve("portable/app")).resolve("Metrolist.exe")
            Files.createFile(installed)
            Files.createFile(portable)
            val root = installed.parent.parent
            assertTrue(isRunningRegisteredDesktop(root, installed.toString()))
            assertFalse(isRunningRegisteredDesktop(root, portable.toString()))
            assertFalse(isRunningRegisteredDesktop(root, null))
            assertFalse(isRunningRegisteredDesktop(root, directory.resolve("java.exe").toString()))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun parsesRequiredGitHubDigest() {
        val digest = "00ff" + "11".repeat(30)
        assertContentEquals(byteArrayOf(0, -1) + ByteArray(30) { 0x11 }, parseGitHubSHA256("sha256:$digest"))
        assertFailsWith<IllegalArgumentException> { parseGitHubSHA256(null) }
        assertFailsWith<IllegalArgumentException> { parseGitHubSHA256("md5:${"0".repeat(32)}") }
    }

    @Test
    fun releaseHistoryIncludesNotesAndDatesAndExcludesDraftsAndUnversionedTags() {
        val releases =
            DesktopUpdateManager.parseReleases(
                """[
            {"tag_name":"nightly","draft":false,"prerelease":true,"assets":[]},
            {"tag_name":"v14.0.0-rc8","draft":false,"prerelease":true,"assets":[],"body":"## Fixes","published_at":"2026-11-01T12:00:00Z"},
            {"tag_name":"v14.0.0","draft":true,"prerelease":false,"assets":[]},
            {"tag_name":"v13.7.0","draft":false,"prerelease":false,"assets":[]},
            {"tag_name":"v14.0.0-rc10","draft":false,"prerelease":true,"assets":[]}
        ]""",
            )
        assertEquals(listOf("14.0.0-rc10", "14.0.0-rc8", "13.7.0"), releases.map { it.version })
        assertEquals("## Fixes", releases[1].body)
        assertEquals("2026-11-01T12:00:00Z", releases[1].publishedAt)
        assertEquals("https://github.com/MetrolistGroup/Metrolist/releases/tag/v14.0.0-rc8", releases[1].url)
        assertFailsWith<Exception> { DesktopUpdateManager.parseReleases("not json") }
        assertFailsWith<Exception> {
            DesktopUpdateManager.parseReleases("""[{"tag_name":"v14.0.0","prerelease":false,"assets":[]}]""")
        }
    }

    @Test
    fun onlyRegisteredStableReleasesUseTheInstaller() {
        val stable = GitHubDesktopRelease("v14.0.0", draft = false, prerelease = false, assets = emptyList())
        assertTrue(stable.canInstall(registeredInstallation = true))
        assertFalse(stable.canInstall(registeredInstallation = false))
        assertFalse(stable.copy(prerelease = true).canInstall(true))
        assertFalse(stable.copy(draft = true).canInstall(true))
        assertFalse(stable.copy(tagName = "v14.0.0-rc10").canInstall(true))
        assertFalse(stable.copy(tagName = "nightly").canInstall(true))
    }

    @Test
    fun automaticChecksAreHourlyAndRecoverFromClockChanges() {
        val hour = 3_600_000L
        assertTrue(desktopUpdateCheckIsDue(0L, hour))
        assertFalse(desktopUpdateCheckIsDue(hour, hour + 1L))
        assertTrue(desktopUpdateCheckIsDue(hour, hour * 2))
        assertTrue(desktopUpdateCheckIsDue(hour * 2, hour))
    }

    @Test
    fun installerDownloadReportsProgressAndRequiresTheExactSizeAndDigest() =
        runBlocking {
            val bytes = ByteArray(20_000) { it.toByte() }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val output = ByteArrayOutputStream()
            val progress = mutableListOf<Int>()
            copyVerifiedUpdate(ByteArrayInputStream(bytes), output, bytes.size.toLong(), digest, progress::add)
            assertContentEquals(bytes, output.toByteArray())
            assertEquals(0, progress.first())
            assertEquals(100, progress.last())
            assertTrue(progress.zipWithNext().all { (left, right) -> left <= right })
            assertTrue(progress.any { it in 1..99 })
            for ((size, hash) in listOf(bytes.size - 1L to digest, bytes.size + 1L to digest, bytes.size.toLong() to ByteArray(32))) {
                assertFailsWith<IllegalStateException> {
                    copyVerifiedUpdate(ByteArrayInputStream(bytes), ByteArrayOutputStream(), size, hash) { }
                }
            }
        }

    @Test
    fun cancelledInstallerDownloadStopsBeforeWritingMoreBytes() =
        runBlocking {
            val output = ByteArrayOutputStream()
            val job =
                launch {
                    val context = currentCoroutineContext()
                    assertFailsWith<CancellationException> {
                        copyVerifiedUpdate(ByteArrayInputStream(ByteArray(20_000)), output, 20_000, ByteArray(32)) { progress ->
                            if (progress > 0) context.cancel()
                        }
                    }
                }
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(DEFAULT_BUFFER_SIZE, output.size())
        }
}
