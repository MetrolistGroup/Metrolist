package com.metrolist.shared.auth

import com.metrolist.innertubex.models.response.AccountsListResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AccountSelectionTest {
    @Test
    fun switchingNeverCompletesFromAnExistingBrowserSession() {
        assertEquals(AccountDiscoveryAction.BROWSE_ACCOUNTS, accountDiscoveryAction(0, isSwitchingChannel = true))
        assertEquals(AccountDiscoveryAction.BROWSE_ACCOUNTS, accountDiscoveryAction(1, isSwitchingChannel = true))
        assertEquals(AccountDiscoveryAction.SELECT_ACCOUNT, accountDiscoveryAction(2, isSwitchingChannel = true))
    }

    @Test
    fun ordinaryLoginStillCompletesWhenThereAreNoAlternativeChannels() {
        assertEquals(AccountDiscoveryAction.COMPLETE_LOGIN, accountDiscoveryAction(0, isSwitchingChannel = false))
        assertEquals(AccountDiscoveryAction.COMPLETE_LOGIN, accountDiscoveryAction(1, isSwitchingChannel = false))
        assertEquals(AccountDiscoveryAction.SELECT_ACCOUNT, accountDiscoveryAction(2, isSwitchingChannel = false))
    }

    @Test
    fun reselectingAnAccountRepairsMissingIdentityWithoutGuessingAnotherChannel() {
        val account = AccountsListResponse.Account("Channel", null, null, null, true, null, "page", "channel||account")
        for (missing in listOf("", " ", "||")) {
            assertEquals("channel", account.selectionDataSyncId(missing))
            assertEquals("account", account.copy(dataSyncId = "||account", pageId = null).selectionDataSyncId(missing))
            assertEquals("page", account.copy(dataSyncId = null).selectionDataSyncId(missing))
            assertNull(account.copy(dataSyncId = null, pageId = null).selectionDataSyncId(missing))
        }
        assertEquals("existing-channel", account.selectionDataSyncId("existing-channel||account"))
    }

    @Test
    fun tokenOnlyAccountsUseTheirOwnIdentity() {
        val account = AccountsListResponse.Account("Channel", null, null, null, false, null, "page", "channel||account")
        assertEquals("channel", account.selectionDataSyncId("previous"))
        assertEquals("page", account.copy(dataSyncId = "||").selectionDataSyncId("previous"))
        assertNull(account.copy(dataSyncId = null, pageId = null).selectionDataSyncId("previous"))
        assertEquals("previous", account.copy(isSelected = true).selectionDataSyncId("previous"))
    }
}
