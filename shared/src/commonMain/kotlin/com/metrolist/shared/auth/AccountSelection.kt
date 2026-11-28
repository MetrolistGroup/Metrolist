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

/** Page id of a brand-account `DATASYNC_ID` (`<page>||<user>`); null for a personal `<user>||` or `||<user>` value. */
fun delegatedSessionIdFromDataSyncId(rawDataSyncId: String): String? {
    val raw = rawDataSyncId.trim()
    if ("||" !in raw) return null
    val page = raw.substringBefore("||").trim()
    return page.takeIf { it.isNotEmpty() && raw.substringAfter("||").isNotBlank() }
}

/**
 * Page id when [dataSyncId] is a brand account in [accounts], empty when it is a personal account,
 * or null when [accounts] does not list it.
 */
fun delegatedSessionIdFor(
    dataSyncId: String,
    accounts: List<AccountsListResponse.Account>,
): String? {
    val identity = sanitizeDataSyncId(dataSyncId).takeIf(String::isNotBlank) ?: return null
    val account = accounts.firstOrNull { it.selectionDataSyncId("") == identity } ?: return null
    val token = account.dataSyncId?.trim().orEmpty()
    return if ("||" in token) {
        delegatedSessionIdFromDataSyncId(token).orEmpty()
    } else {
        account.pageId?.trim().orEmpty()
    }
}

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
