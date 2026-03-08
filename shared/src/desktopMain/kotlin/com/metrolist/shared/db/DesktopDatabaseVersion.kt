package com.metrolist.shared.db

import org.sqlite.SQLiteConfig
import java.io.File
import java.sql.DriverManager

internal fun checkDesktopDatabaseVersion(dbFile: File) {
    if (!dbFile.isFile) return
    val config = SQLiteConfig().apply { setReadOnly(true) }
    val version =
        runCatching {
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}", config.toProperties()).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("PRAGMA user_version").use { result ->
                        if (result.next()) result.getInt(1) else null
                    }
                }
            }
        }.getOrNull()
    version?.let(::checkDatabaseVersion)
}
