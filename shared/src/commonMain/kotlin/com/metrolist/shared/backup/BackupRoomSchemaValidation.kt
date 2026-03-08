package com.metrolist.shared.backup

import androidx.room3.util.TableInfo
import androidx.room3.util.ViewInfo
import androidx.sqlite.SQLiteConnection

internal suspend fun validateBackupRoomSchema(
    expected: SQLiteConnection,
    actual: SQLiteConnection,
) {
    val expectedObjects = expected.roomSchemaObjects()
    val actualObjects = actual.roomSchemaObjects()
    check(expectedObjects.keys == actualObjects.keys) { "Backup database schema does not match Room schema" }

    expectedObjects.forEach { (objectKey, sql) ->
        val (type, name) = objectKey
        when (type) {
            "table" -> {
                check(TableInfo.read(expected, name) == TableInfo.read(actual, name)) {
                    "Backup database schema does not match Room schema for $name"
                }
            }

            "view" -> {
                check(ViewInfo.read(expected, name) == ViewInfo.read(actual, name)) {
                    "Backup database schema does not match Room schema for $name"
                }
            }

            "trigger" -> {
                check(sql == actualObjects[objectKey]) {
                    "Backup database schema does not match Room schema for $name"
                }
            }
        }
    }
}

private fun SQLiteConnection.roomSchemaObjects(): Map<Pair<String, String>, String?> =
    prepare(
        "SELECT type, name, sql FROM sqlite_master " +
            "WHERE type IN ('table', 'view', 'index', 'trigger') AND name NOT GLOB 'sqlite_*' AND name != 'android_metadata'",
    ).use { statement ->
        buildMap {
            while (statement.step()) {
                val key = statement.getText(0) to statement.getText(1)
                put(key, if (statement.isNull(2)) null else statement.getText(2))
            }
        }
    }
