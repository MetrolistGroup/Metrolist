package com.metrolist.shared.auth

import com.metrolist.innertubex.models.AccountInfo
import com.metrolist.innertubex.models.YouTubeLocale
import com.metrolist.shared.api.parseAccountInfoResponse
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalInnerTube
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.state.SongLikeState
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthManagerTest {
    private val previousSession = GlobalInnerTube.instance.sessionSnapshot()
    private val previousAccountInfo = GlobalYouTubeRepository.instance.lastAccountInfo

    @BeforeTest
    fun setUp() {
        Settings.replaceSharedForTest(Settings(MapSettings()))
    }

    @AfterTest
    fun tearDown() {
        GlobalYouTubeRepository.instance.lastAccountInfo = previousAccountInfo
        GlobalInnerTube.instance.locale = previousSession.locale
        GlobalInnerTube.instance.replaceSession(
            previousSession.cookie,
            previousSession.visitorData,
            previousSession.dataSyncId,
            previousSession.authUser,
            previousSession.useLoginForBrowse,
        )
        Settings.replaceSharedForTest(null)
    }

    @Test
    fun logoutClearsCredentialsAndPendingPlaysBeforeWaitingForBrowserCookies() =
        runTest {
            val auth = AuthManager()
            auth.saveCredentials("SAPISID=old", "visitor", "old-sync", AccountInfo("Old", null, null, null))
            Settings.shared.queuedYouTubeHistoryJson = "[{\"old\":true}]"
            val clearing = CompletableDeferred<Unit>()
            val cleared = CompletableDeferred<Unit>()
            val logout =
                launch {
                    auth.clearAllCredentials {
                        clearing.complete(Unit)
                        cleared.await()
                    }
                }
            clearing.await()
            assertFalse(auth.isLoggedIn)
            assertFalse(GlobalYouTubeRepository.instance.isPlaybackReportingEnabled())
            assertEquals("[]", Settings.shared.queuedYouTubeHistoryJson)
            cleared.complete(Unit)
            logout.join()
        }

    @Test
    fun visitorDataFromStartupCannotOverwriteANewerLoginOrLogout() =
        runTest {
            for (signIn in listOf(false, true)) {
                Settings.replaceSharedForTest(Settings(MapSettings()))
                val auth = AuthManager()
                auth.saveCredentials("SAPISID=old", "", "old-sync", AccountInfo("Old", null, null, null), "0")
                val started = CompletableDeferred<Unit>()
                val visitorData = CompletableDeferred<String?>()
                val restore =
                    launch {
                        auth.restoreSession {
                            started.complete(Unit)
                            visitorData.await()
                        }
                    }
                started.await()
                if (signIn) {
                    auth.saveCredentials("SAPISID=new", "new-visitor", "new-sync", AccountInfo("New", null, null, null), "1")
                } else {
                    invalidateYouTubeSession(GlobalInnerTube.instance)
                }
                val expectedSession = GlobalInnerTube.instance.sessionSnapshot()
                val expectedVisitorData = Settings.shared.visitorData
                visitorData.complete("stale-visitor")
                restore.join()

                assertEquals(expectedSession, GlobalInnerTube.instance.sessionSnapshot())
                assertEquals(expectedVisitorData, Settings.shared.visitorData)
            }
        }

    @Test
    fun cancelledVisitorDataFetchDoesNotContinueSessionRestoration() =
        runTest {
            assertFailsWith<CancellationException> {
                AuthManager().restoreSession { throw CancellationException("Startup cancelled") }
            }
            assertEquals("", Settings.shared.visitorData)
        }

    @Test
    fun visitorDataFromStartupIsPersistedForTheUnchangedSession() =
        runTest {
            AuthManager().restoreSession { "fresh-visitor" }

            assertEquals("fresh-visitor", Settings.shared.visitorData)
            assertEquals("fresh-visitor", GlobalInnerTube.instance.visitorData)
        }

    @Test
    fun candidateSessionUsesTheResolvedContentLocaleWithoutTouchingTheActiveSession() {
        GlobalInnerTube.instance.locale = YouTubeLocale(gl = "CA", hl = "fr")
        GlobalInnerTube.instance.replaceSession("SAPISID=active", "active-visitor", "active-sync", "0", true)

        val candidate = AuthManager().candidateInnerTube("SAPISID=candidate", "candidate-visitor", "candidate-sync", "1")

        assertEquals(YouTubeLocale(gl = "CA", hl = "fr"), candidate.locale)
        assertEquals("SAPISID=candidate", candidate.cookie)
        assertEquals("candidate-visitor", candidate.visitorData)
        assertEquals("candidate-sync", candidate.dataSyncId)
        assertEquals("1", candidate.authUser)
        assertEquals("SAPISID=active", GlobalInnerTube.instance.cookie)
    }

    @Test
    fun staleAccountDiscoveryCannotOverwriteANewerProfile() =
        runTest {
            val auth = AuthManager()
            auth.saveCredentials(
                "SAPISID=shared",
                "old-visitor",
                "old-sync",
                AccountInfo("Old account", "old@example.invalid", "@old", "old-avatar"),
                "1",
            )
            val response = CompletableDeferred<AccountInfo?>()
            val fetch = launch(start = CoroutineStart.UNDISPATCHED) { auth.fetchAndSaveAccountInfo { response.await() } }

            auth.saveCredentials(
                "SAPISID=shared",
                "new-visitor",
                "new-sync",
                AccountInfo("New account", "new@example.invalid", "@new", "new-avatar"),
                "2",
            )
            response.complete(AccountInfo("Old response", "stale@example.invalid", "@stale", "stale-avatar"))
            fetch.join()

            assertEquals("New account", auth.accountName)
            assertEquals("new@example.invalid", auth.accountEmail)
            assertEquals("@new", auth.accountChannelHandle)
            assertEquals("new-avatar", auth.accountThumbnail)
        }

    @Test
    fun savingAnotherAccountClearsThePreviousAccountsLikes() {
        val auth = AuthManager()
        auth.saveCredentials(
            "SAPISID=account-a",
            "visitor-a",
            "account-a-sync",
            AccountInfo("Account A", null, null, null),
        )
        val song =
            SongItem(
                id = "liked",
                title = "Liked",
                thumbnail = null,
                artists = listOf(ArtistTiny(id = null, name = "Artist")),
                album = null,
            )
        SongLikeState.replaceLikesFromRemote(listOf(song))

        auth.saveCredentials(
            "SAPISID=account-b",
            "visitor-b",
            "account-b-sync",
            AccountInfo("Account B", null, null, null),
        )

        assertFalse(SongLikeState.isLiked(song.id))
        assertEquals("[]", Settings.shared.likedSongsJson)
    }

    @Test
    fun persistentCredentialResetClearsThePreviousAccountsLikes() {
        AuthManager().saveCredentials(
            "SAPISID=account",
            "visitor",
            "account-sync",
            AccountInfo("Account", null, null, null),
        )
        val song =
            SongItem(
                id = "liked",
                title = "Liked",
                thumbnail = null,
                artists = listOf(ArtistTiny(id = null, name = "Artist")),
                album = null,
            )
        SongLikeState.replaceLikesFromRemote(listOf(song))

        invalidateYouTubeSession(GlobalInnerTube.instance)

        assertFalse(SongLikeState.isLiked(song.id))
        assertEquals("[]", Settings.shared.likedSongsJson)
    }

    @Test
    fun rejectedCandidateLeavesThePersistedAccountAndRuntimeSessionUntouched() =
        runTest {
            val auth = AuthManager()
            auth.saveCredentials(
                "SAPISID=persisted",
                "persisted-visitor",
                "persisted-sync",
                AccountInfo("Persisted", null, null, null),
                "0",
            )
            assertFalse(
                auth.validateAndSaveCredentials(
                    "SAPISID=candidate",
                    "candidate-visitor",
                    "candidate-sync",
                    "1",
                ) { null },
            )

            assertFailsWith<IllegalStateException> {
                auth.validateAndSaveCredentials(
                    "SAPISID=candidate",
                    "candidate-visitor",
                    "candidate-sync",
                    "1",
                ) { throw IllegalStateException("Account lookup failed") }
            }
            assertFailsWith<CancellationException> {
                auth.validateAndSaveCredentials(
                    "SAPISID=candidate",
                    "candidate-visitor",
                    "candidate-sync",
                    "1",
                ) { throw CancellationException("Login dismissed") }
            }

            assertEquals("Persisted", auth.accountName)
            assertEquals("SAPISID=persisted", Settings.shared.innerTubeCookie)
            assertEquals("SAPISID=persisted", GlobalInnerTube.instance.cookie)
            assertEquals("persisted-visitor", GlobalInnerTube.instance.visitorData)
            assertEquals("persisted-sync", GlobalInnerTube.instance.dataSyncId)
            assertEquals("0", GlobalInnerTube.instance.authUser)
        }

    @Test
    fun literalUnknownCandidateCommitsAndMalformedRefreshCannotReplaceIt() =
        runTest {
            val auth = AuthManager()
            auth.saveCredentials(
                "SAPISID=persisted",
                "persisted-visitor",
                "persisted-sync",
                AccountInfo("Persisted", null, null, null),
                "0",
            )

            assertTrue(
                auth.validateAndSaveCredentials(
                    "SAPISID=candidate",
                    "candidate-visitor",
                    "candidate-sync",
                    "1",
                ) { AccountInfo("Unknown", "candidate@example.invalid", "@candidate", "candidate-avatar") },
            )

            assertEquals("Unknown", auth.accountName)
            assertEquals("candidate@example.invalid", auth.accountEmail)
            assertEquals(
                AccountInfo("Unknown", "candidate@example.invalid", "@candidate", "candidate-avatar"),
                GlobalYouTubeRepository.instance.lastAccountInfo,
            )
            assertEquals("SAPISID=candidate", Settings.shared.innerTubeCookie)
            assertEquals("SAPISID=candidate", GlobalInnerTube.instance.cookie)
            assertEquals("candidate-visitor", GlobalInnerTube.instance.visitorData)
            assertEquals("candidate-sync", GlobalInnerTube.instance.dataSyncId)
            assertEquals("1", GlobalInnerTube.instance.authUser)

            assertFalse(
                auth.fetchAndSaveAccountInfo {
                    parseAccountInfoResponse(
                        """{"activeAccountHeaderRenderer":{"email":{"runs":[{"text":"wrong@example.invalid"}]}}}""",
                    )
                },
            )
            assertEquals("Unknown", auth.accountName)
            assertEquals("candidate@example.invalid", auth.accountEmail)
            assertEquals("candidate-avatar", auth.accountThumbnail)
        }
}
