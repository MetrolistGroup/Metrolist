package com.metrolist.shared.db

import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.database_downgrade
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString

class DatabaseDowngradeException(
    val databaseVersion: Int,
    val supportedVersion: Int,
) : IllegalStateException(runBlocking { getString(Res.string.database_downgrade) })

fun checkDatabaseVersion(version: Int) {
    if (version > DATABASE_VERSION) throw DatabaseDowngradeException(version, DATABASE_VERSION)
}
