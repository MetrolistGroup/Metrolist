package com.metrolist.android.update

import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AppUpdatePrereleaseRegressionTest {
    @Before
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
        Settings.shared.updateCacheSource = CACHE_SOURCE
    }

    @After
    fun tearDown() {
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun automaticUpdateIgnoresGitHubPrerelease() =
        runTest {
            assertNull(checkCachedRelease("v999.0.0-rc.1", prerelease = true))
        }

    @Test
    fun automaticUpdateIgnoresPrereleaseTagMisclassifiedAsStable() =
        runTest {
            assertNull(checkCachedRelease("v999.0.0-rc.1", prerelease = false))
        }

    @Test
    fun automaticUpdateIgnoresStableTagMarkedAsPrerelease() =
        runTest {
            assertNull(checkCachedRelease("v999.0.0", prerelease = true))
        }

    @Test
    fun automaticUpdateStillAcceptsPublishedStableRelease() =
        runTest {
            assertEquals("999.0.0", checkCachedRelease("v999.0.0", prerelease = false)?.version)
        }

    private suspend fun checkCachedRelease(
        tag: String,
        prerelease: Boolean,
    ): GitHubRelease? {
        Settings.shared.cachedAvailableUpdateJson =
            """{"tag_name":"$tag","name":"Candidate","draft":false,"prerelease":$prerelease,""" +
            """"html_url":"https://example.invalid/$tag","assets":[]}"""
        return AppUpdateManager.checkAtStartup(RuntimeEnvironment.getApplication(), networkEnabled = false)
    }

    private companion object {
        const val CACHE_SOURCE = "github-releases-metrolist-v1"
    }
}
