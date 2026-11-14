/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

val MIGRATION_64_65 =
    object : Migration(64, 65) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_64_65_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_63_64 =
    object : Migration(63, 64) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_63_64_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_62_63 =
    object : Migration(62, 63) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_62_63_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_61_62 =
    object : Migration(61, 62) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_61_62_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_60_61 =
    object : Migration(60, 61) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_60_61_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_59_60 =
    object : Migration(59, 60) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_59_60_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_58_59 =
    object : Migration(58, 59) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_58_59_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_57_58 =
    object : Migration(57, 58) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_57_58_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_45_46 =
    object : Migration(45, 46) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_45_46_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_46_47 =
    object : Migration(46, 47) {
        override suspend fun migrate(connection: SQLiteConnection) {
            // Some version-46 databases in the field already have this column.
            if ("musicVideoType" in tableColumns(connection, "speed_dial")) return
            MIGRATION_46_47_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_47_48 =
    object : Migration(47, 48) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_47_48_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_48_49 =
    object : Migration(48, 49) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_48_49_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_51_52 =
    object : Migration(51, 52) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_51_52_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_52_53 =
    object : Migration(52, 53) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_52_53_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_56_57 =
    object : Migration(56, 57) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_56_57_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_55_56 =
    object : Migration(55, 56) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_55_56_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_54_55 =
    object : Migration(54, 55) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_54_55_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_53_54 =
    object : Migration(53, 54) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_53_54_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_50_51 =
    object : Migration(50, 51) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_50_51_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_49_50 =
    object : Migration(49, 50) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_49_50_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_44_45 =
    object : Migration(44, 45) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_44_45_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_39_40 =
    object : Migration(39, 40) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_39_40_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_40_41 =
    object : Migration(40, 41) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_40_41_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_41_42 =
    object : Migration(41, 42) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_41_42_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_42_43 =
    object : Migration(42, 43) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_42_43_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_43_44 =
    object : Migration(43, 44) {
        override suspend fun migrate(connection: SQLiteConnection) {
            MIGRATION_43_44_SQL.forEach { connection.execSQL(it) }
        }
    }

val MIGRATION_36_37 =
    object : Migration(36, 37) {
        override suspend fun migrate(connection: SQLiteConnection) = normalizeLegacySpeedDial(connection)
    }

val MIGRATION_37_38 =
    object : Migration(37, 38) {
        override suspend fun migrate(connection: SQLiteConnection) = Unit
    }

val MIGRATION_38_39 =
    object : Migration(38, 39) {
        override suspend fun migrate(connection: SQLiteConnection) {
            normalizeLegacySpeedDial(connection)
            val columns = tableColumns(connection, "speed_dial")
            // Legacy imports can already contain these columns at version 38.
            for (column in listOf("subtitleIds", "albumId", "albumName")) {
                if (column !in columns) connection.execSQL("ALTER TABLE speed_dial ADD COLUMN `$column` TEXT")
            }
        }
    }

private fun tableColumns(
    connection: SQLiteConnection,
    table: String,
): Set<String> =
    connection.prepare("PRAGMA table_info($table)").use { statement ->
        buildSet {
            while (statement.step()) add(statement.getText(1))
        }
    }

private fun normalizeLegacySpeedDial(connection: SQLiteConnection) {
    val hasLegacyTable =
        connection.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'speed_dial_item'").use { it.step() }
    if (hasLegacyTable) connection.execSQL("ALTER TABLE speed_dial_item RENAME TO speed_dial")
    val hasIndex =
        connection.prepare("PRAGMA table_info(speed_dial)").use { statement ->
            var found = false
            while (statement.step()) {
                if (statement.getText(1) == "index") found = true
            }
            found
        }
    if (!hasIndex) connection.execSQL("ALTER TABLE speed_dial ADD COLUMN `index` INTEGER NOT NULL DEFAULT 0")
}
