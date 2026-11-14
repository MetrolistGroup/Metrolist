package com.metrolist.shared.backup

import androidx.sqlite.SQLiteConnection

internal fun validateBackupDatabase(connection: SQLiteConnection) {
    validateBackupDatabaseIntegrity(connection)
    connection.prepare("PRAGMA foreign_key_check").use {
        check(!it.step()) { "Backup database contains invalid relationships" }
    }
}

internal fun validateBackupDatabaseIntegrity(connection: SQLiteConnection) {
    connection.prepare("PRAGMA integrity_check").use {
        check(it.step() && it.getText(0) == "ok") { "Backup database failed integrity check" }
    }
}

fun checkpointBackupDatabase(connection: SQLiteConnection) {
    connection.prepare("PRAGMA wal_checkpoint(TRUNCATE)").use {
        check(it.step() && it.getLong(0) == 0L && it.getLong(1) == it.getLong(2)) {
            "Could not checkpoint backup database"
        }
    }
}
