package com.metrolist.shared.state

import com.metrolist.shared.api.SongVote
import com.metrolist.shared.backup.shouldMigrateDatabaseLikes
import com.metrolist.shared.constants.DatabaseLikesRecoveryVersionKey
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import kotlinx.serialization.json.Json

internal const val DATABASE_LIKES_RECOVERY_VERSION = 1

private fun Settings.hasAccountLikes(): Boolean =
    credentialsPending || innerTubeCookie.isNotBlank() || dataSyncId.isNotBlank() ||
        (likedSongsOrderAccount.isNotBlank() && !likedSongsOrderAccount.startsWith("signed-out|"))

internal fun Settings.recoverDatabaseLikes(
    databaseLikesOldestFirst: List<SongItem>,
    currentSongs: List<SongItem>? = null,
    currentRecency: List<String>? = null,
): Boolean {
    if (databaseLikesRecoveryVersion >= DATABASE_LIKES_RECOVERY_VERSION) return false
    if (hasAccountLikes()) {
        databaseLikesRecoveryVersion = DATABASE_LIKES_RECOVERY_VERSION
        flush()
        return false
    }

    // Signed-out v14 votes remain pending, including deliberate unlikes of stale Room rows.
    val votes = Json.decodeFromString<Map<String, SongVote>>(pendingSongVotesJson)
    val excluded = votes.filterValues { it != SongVote.LIKE }.keys + dislikedSongIds.split(",")
    val existing = currentSongs ?: Json.decodeFromString<List<SongItem>>(likedSongsJson)
    val ids = existing.mapTo(hashSetOf(), SongItem::id)
    val missing = databaseLikesOldestFirst.asReversed().filter { it.id !in excluded && ids.add(it.id) }
    val recovered = existing + missing
    val previousRecency = currentRecency ?: Json.decodeFromString<List<String>>(likedSongsRecencyJson)
    val recency = (previousRecency.filter { it in ids } + recovered.map(SongItem::id)).distinct()
    likedSongsJson = Json.encodeToString(recovered)
    likedSongIds = recovered.joinToString(",", transform = SongItem::id)
    likedSongsRecencyJson = Json.encodeToString(recency)
    // Persist the payload before the marker so an interrupted write can be retried.
    flush()
    databaseLikesRecoveryVersion = DATABASE_LIKES_RECOVERY_VERSION
    flush()
    return true
}

fun Settings.restoreDatabaseLikes(
    databaseLikesOldestFirst: List<SongItem>,
    restoredSettingKeys: Set<String>?,
) {
    if (DatabaseLikesRecoveryVersionKey !in restoredSettingKeys.orEmpty()) databaseLikesRecoveryVersion = 0
    if (shouldMigrateDatabaseLikes(restoredSettingKeys)) {
        if (!hasAccountLikes()) {
            recoverDatabaseLikes(databaseLikesOldestFirst, emptyList(), emptyList())
            return
        }
        likedSongsJson = Json.encodeToString(databaseLikesOldestFirst)
        likedSongIds = databaseLikesOldestFirst.joinToString(",", transform = SongItem::id)
    }
    recoverDatabaseLikes(databaseLikesOldestFirst)
}
