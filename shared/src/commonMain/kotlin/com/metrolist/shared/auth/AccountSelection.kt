package com.metrolist.shared.auth

import com.metrolist.innertubex.models.response.AccountsListResponse
import com.metrolist.shared.ui.sanitizeDataSyncId

fun AccountsListResponse.Account.selectionDataSyncId(currentDataSyncId: String): String? =
    currentDataSyncId
        .takeIf { isSelected }
        ?.let(::sanitizeDataSyncId)
        ?.takeIf(String::isNotBlank)
        ?: dataSyncId?.let(::sanitizeDataSyncId)?.takeIf(String::isNotBlank)
        ?: pageId?.takeIf(String::isNotBlank)

fun AccountsListResponse.Account.isSelectableForLogin(currentDataSyncId: String): Boolean =
    selectionDataSyncId(currentDataSyncId) != null || !signinUrl.isNullOrBlank()

fun singleAccountDataSyncId(
    currentDataSyncId: String,
    accounts: List<AccountsListResponse.Account>,
): String =
    sanitizeDataSyncId(currentDataSyncId).takeIf(String::isNotBlank)
        ?: accounts.singleOrNull()?.selectionDataSyncId(currentDataSyncId)
        ?: currentDataSyncId

enum class AccountDiscoveryAction {
    SELECT_ACCOUNT,
    BROWSE_ACCOUNTS,
    COMPLETE_LOGIN,
}

fun accountDiscoveryAction(
    accountCount: Int,
    isSwitchingChannel: Boolean,
): AccountDiscoveryAction =
    when {
        accountCount > 1 -> AccountDiscoveryAction.SELECT_ACCOUNT
        isSwitchingChannel -> AccountDiscoveryAction.BROWSE_ACCOUNTS
        else -> AccountDiscoveryAction.COMPLETE_LOGIN
    }
