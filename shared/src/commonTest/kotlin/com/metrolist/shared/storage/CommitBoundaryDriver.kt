package com.metrolist.shared.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement

internal class CommitBoundaryDriver(
    private val delegate: SQLiteDriver,
) : SQLiteDriver by delegate {
    var beforeCommit: () -> Unit = {}
    var afterCommit: () -> Unit = {}

    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        return object : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement {
                val statement = connection.prepare(sql)
                if (sql != "END TRANSACTION") return statement
                return object : SQLiteStatement by statement {
                    override fun step(): Boolean {
                        beforeCommit()
                        val result = statement.step()
                        afterCommit()
                        return result
                    }
                }
            }
        }
    }
}
