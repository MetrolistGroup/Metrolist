package com.metrolist.innertube.models

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

data class YouTubeAccount(
    val name: String,
    val byline: String?,
    val channelHandle: String?,
    val thumbnailUrl: String?,
    val isSelected: Boolean,
    val signinUrl: String?,
    val pageId: String?,
    val dataSyncId: String?,
)

internal fun JsonElement.extractYouTubeAccounts(): List<YouTubeAccount> {
    val accounts = mutableListOf<YouTubeAccount>()
    collectAccountItems(accounts)
    return accounts.distinctBy { account ->
        account.pageId
            ?: account.dataSyncId
            ?: account.signinUrl
            ?: account.channelHandle
            ?: account.name
    }
}

private fun JsonElement.collectAccountItems(accounts: MutableList<YouTubeAccount>) {
    when (this) {
        is JsonObject -> {
            ((this["accountItem"] ?: this["accountItemRenderer"]) as? JsonObject)
                ?.toYouTubeAccount()
                ?.let(accounts::add)
            values.forEach { it.collectAccountItems(accounts) }
        }

        is JsonArray -> forEach { it.collectAccountItems(accounts) }
        else -> Unit
    }
}

private fun JsonObject.toYouTubeAccount(): YouTubeAccount? {
    val name = textFromRuns("accountName") ?: return null
    val endpoint = findObject("selectActiveIdentityEndpoint")
    return YouTubeAccount(
        name = name,
        byline = textFromRuns("accountByline"),
        channelHandle = textFromRuns("channelHandle"),
        thumbnailUrl =
            ((this["accountPhoto"] as? JsonObject)?.get("thumbnails") as? JsonArray)
                ?.lastOrNull()
                ?.let { it as? JsonObject }
                ?.get("url")
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull,
        isSelected = (this["isSelected"] as? JsonPrimitive)?.booleanOrNull ?: false,
        signinUrl = endpoint?.tokenValue("accountSigninToken", "signinUrl"),
        pageId = endpoint?.tokenValue("pageIdToken", "pageId"),
        dataSyncId = endpoint?.tokenValue("datasyncIdToken", "datasyncIdToken"),
    )
}

private fun JsonObject.textFromRuns(key: String): String? {
    val text = this[key] as? JsonObject ?: return null
    return ((text["runs"] as? JsonArray)
        ?.firstOrNull() as? JsonObject)
        ?.get("text")
        ?.let { it as? JsonPrimitive }
        ?.contentOrNull
        ?: (text["simpleText"] as? JsonPrimitive)?.contentOrNull
}

private fun JsonObject.tokenValue(
    tokenKey: String,
    valueKey: String,
): String? =
    (this["supportedTokens"] as? JsonArray)
        ?.firstNotNullOfOrNull { token ->
            (((token as? JsonObject)?.get(tokenKey) as? JsonObject)?.get(valueKey) as? JsonPrimitive)
                ?.contentOrNull
        }

private fun JsonElement.findObject(key: String): JsonObject? =
    when (this) {
        is JsonObject -> (this[key] as? JsonObject) ?: values.firstNotNullOfOrNull { it.findObject(key) }
        is JsonArray -> firstNotNullOfOrNull { it.findObject(key) }
        else -> null
    }
