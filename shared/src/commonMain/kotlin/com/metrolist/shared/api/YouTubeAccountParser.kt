package com.metrolist.shared.api

import com.metrolist.innertubex.models.AccountInfo
import com.metrolist.innertubex.models.response.AccountsListResponse
import com.metrolist.shared.ui.sanitizeDataSyncId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private val accountResponseJson = Json { ignoreUnknownKeys = true }

internal fun parseAccountsListResponse(rawBody: String): List<AccountsListResponse.Account> {
    val root = accountResponseJson.parseToJsonElement(rawBody).jsonObject
    return root.parseAccountsListFallback().distinctBy { account ->
        account.pageId?.takeIf(String::isNotBlank)
            ?: account.dataSyncId?.let(::sanitizeDataSyncId)?.takeIf(String::isNotBlank)
            ?: account.signinUrl?.takeIf(String::isNotBlank)
            ?: account.channelHandle?.takeIf(String::isNotBlank)
            ?: account.name
    }
}

internal fun parseAccountInfoResponse(rawBody: String): AccountInfo? =
    accountResponseJson.parseToJsonElement(rawBody).jsonObject.parseAccountInfoFallback()

internal fun JsonObject.parseAccountInfoFallback(): AccountInfo? {
    val header = findObject("activeAccountHeaderRenderer") ?: return null
    val name = header.textFromRuns("accountName")?.takeIf(String::isNotBlank) ?: return null
    return AccountInfo(
        name = name,
        email = header.textFromRuns("email"),
        channelHandle = header.textFromRuns("channelHandle"),
        thumbnailUrl = header.thumbnailUrl(),
    )
}

private fun JsonElement.findObject(key: String): JsonObject? =
    when (this) {
        is JsonObject -> {
            (this[key] as? JsonObject) ?: values.firstNotNullOfOrNull { it.findObject(key) }
        }

        is JsonArray -> {
            firstNotNullOfOrNull { it.findObject(key) }
        }

        else -> {
            null
        }
    }

internal fun JsonObject.parseAccountsListFallback(): List<AccountsListResponse.Account> {
    val accounts = mutableListOf<AccountsListResponse.Account>()
    collectAccountItems(this, accounts)
    return accounts
}

private fun collectAccountItems(
    element: JsonElement,
    accounts: MutableList<AccountsListResponse.Account>,
) {
    when (element) {
        is JsonObject -> {
            val renderer = element["accountItem"] as? JsonObject ?: element["accountItemRenderer"] as? JsonObject
            renderer?.toAccount()?.let(accounts::add)
            element.values.forEach { collectAccountItems(it, accounts) }
        }

        is JsonArray -> {
            element.forEach { collectAccountItems(it, accounts) }
        }

        else -> {}
    }
}

private fun JsonObject.toAccount(): AccountsListResponse.Account? {
    val name = textFromRuns("accountName") ?: textFromRuns("channelHandle") ?: textFromRuns("accountByline") ?: return null
    val endpoint = findSelectActiveIdentityEndpoint(this)
    return AccountsListResponse.Account(
        name = name,
        byline = textFromRuns("accountByline"),
        channelHandle = textFromRuns("channelHandle"),
        thumbnailUrl = thumbnailUrl(),
        isSelected = (this["isSelected"] as? JsonPrimitive)?.contentOrNull?.toBoolean() ?: false,
        signinUrl = endpoint?.tokenValue("accountSigninToken", "signinUrl"),
        pageId = endpoint?.tokenValue("pageIdToken", "pageId"),
        dataSyncId = endpoint?.tokenValue("datasyncIdToken", "datasyncIdToken"),
    )
}

private fun JsonObject.textFromRuns(key: String): String? {
    val text = this[key] as? JsonObject ?: return null
    return (text["runs"] as? JsonArray)
        ?.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }
        ?.joinToString("")
        ?.takeIf(String::isNotBlank)
        ?: (text["simpleText"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
}

private fun JsonObject.thumbnailUrl(): String? =
    ((this["accountPhoto"] as? JsonObject)?.get("thumbnails") as? JsonArray)
        ?.lastOrNull()
        ?.let { ((it as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull }

private fun JsonObject.tokenValue(
    tokenKey: String,
    valueKey: String,
): String? =
    (this["supportedTokens"] as? JsonArray)
        ?.firstNotNullOfOrNull { token ->
            (((token as? JsonObject)?.get(tokenKey) as? JsonObject)?.get(valueKey) as? JsonPrimitive)?.contentOrNull
        }

private fun findSelectActiveIdentityEndpoint(element: JsonElement): JsonObject? =
    when (element) {
        is JsonObject -> {
            element["selectActiveIdentityEndpoint"] as? JsonObject
                ?: element.values.firstNotNullOfOrNull(::findSelectActiveIdentityEndpoint)
        }

        is JsonArray -> {
            element.firstNotNullOfOrNull(::findSelectActiveIdentityEndpoint)
        }

        else -> {
            null
        }
    }
