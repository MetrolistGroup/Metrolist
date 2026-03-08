package com.metrolist.desktop.ui.screens

import com.metrolist.innertubex.models.response.AccountsListResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebViewAccountSelectionTest {
    private val session = AuthData("SAPISID=session", "visitor", "current-channel", "2")

    @Test
    fun brandChannelWithoutSigninUrlStillGoesThroughValidation() =
        runTest {
            val validated = mutableListOf<AuthData>()
            assertTrue(
                selectWebViewAccount(session, account(pageId = "brand-channel"), { validated += it }, { error("Unexpected navigation") }),
            )
            assertEquals(listOf(session.copy(dataSyncId = "brand-channel")), validated)
        }

    @Test
    fun personalAccountSelectionUsesItsDataSyncIdInsteadOfThePreviousChannel() =
        runTest {
            val validated = mutableListOf<AuthData>()
            val personal = account().copy(dataSyncId = "||personal-account")
            assertTrue(selectWebViewAccount(session, personal, { validated += it }, { error("Unexpected navigation") }))
            assertEquals(listOf(session.copy(dataSyncId = "personal-account")), validated)
        }

    @Test
    fun selectedChannelUsesCapturedCredentialsWithoutWaitingForAnotherPageLoad() =
        runTest {
            val validated = mutableListOf<AuthData>()
            assertTrue(selectWebViewAccount(session, account(selected = true), { validated += it }, { error("Unexpected navigation") }))
            assertEquals(listOf(session), validated)
        }

    @Test
    fun selectedAccountWithMissingIdentityUsesReturnedIdentityOrReauthenticates() =
        runTest {
            val missing = session.copy(dataSyncId = "")
            val validated = mutableListOf<AuthData>()
            val urls = mutableListOf<String>()
            assertTrue(
                selectWebViewAccount(
                    missing,
                    account(selected = true).copy(dataSyncId = "||personal-account"),
                    { validated += it },
                    { error("Unexpected navigation") },
                ),
            )
            assertEquals(listOf(missing.copy(dataSyncId = "personal-account")), validated)
            assertTrue(
                selectWebViewAccount(
                    missing,
                    account(selected = true, signinUrl = "/signin?authuser=2"),
                    { error("Unresolved account must not be saved") },
                    urls::add,
                ),
            )
            assertEquals(listOf("https://music.youtube.com/signin?authuser=2"), urls)
            assertFalse(
                selectWebViewAccount(
                    missing,
                    account(selected = true),
                    { error("Unresolved account must not be saved") },
                    { error("Unexpected navigation") },
                ),
            )
        }

    @Test
    fun urlOnlyAccountNavigatesBeforeAnyCredentialValidation() =
        runTest {
            val urls = mutableListOf<String>()
            assertTrue(
                selectWebViewAccount(session, account(signinUrl = "/signin?authuser=3"), { error("Premature validation") }, urls::add),
            )
            assertEquals(listOf("https://music.youtube.com/signin?authuser=3"), urls)
        }

    @Test
    fun unavailableAccountDoesNotCompleteWithPreviousChannel() =
        runTest {
            assertFalse(selectWebViewAccount(session, account(), { error("Wrong channel") }, { error("Unexpected navigation") }))
        }

    private fun account(
        pageId: String? = null,
        selected: Boolean = false,
        signinUrl: String? = null,
    ) = AccountsListResponse.Account("Channel", null, null, null, selected, signinUrl, pageId, null)
}
