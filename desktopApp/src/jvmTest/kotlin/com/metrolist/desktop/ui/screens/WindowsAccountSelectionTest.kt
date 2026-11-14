package com.metrolist.desktop.ui.screens

import com.metrolist.innertubex.models.response.AccountsListResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowsAccountSelectionTest {
    private val session = AuthData("SAPISID=session", "visitor", "current-channel", "2")

    @Test
    fun switchingChannelPreservesTheGoogleSessionAndUsesTheSelectedIdentity() {
        val account = account(dataSyncId = "brand-channel||account-id")
        assertEquals(session.copy(dataSyncId = "brand-channel"), session.forAccount(account))
        assertEquals(session.copy(dataSyncId = "page-id"), session.forAccount(account(dataSyncId = null, pageId = "page-id")))
        assertEquals(session.copy(dataSyncId = "account-id"), session.forAccount(account(dataSyncId = "||account-id", pageId = "")))
    }

    @Test
    fun currentAccountIsUnchangedAndMissingIdentityCannotSilentlySelectIt() {
        assertEquals(session, session.forAccount(account(isSelected = true)))
        assertNull(session.forAccount(account()))
        assertNull(session.forAccount(account(dataSyncId = "||", pageId = "")))
    }

    private fun account(
        dataSyncId: String? = null,
        pageId: String? = null,
        isSelected: Boolean = false,
    ) = AccountsListResponse.Account(
        name = "Channel",
        byline = null,
        channelHandle = null,
        thumbnailUrl = null,
        isSelected = isSelected,
        signinUrl = null,
        pageId = pageId,
        dataSyncId = dataSyncId,
    )
}
