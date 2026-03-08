package com.metrolist.android.db

import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.shared.db.MusicDatabase

internal suspend fun MusicDatabase.executeTestSql(sql: String) {
    useWriterConnection { connection -> connection.usePrepared(sql) { it.step() } }
}

internal class QueryCallbackDriver(
    private val callback: (String) -> Unit,
) : SQLiteDriver {
    private val delegate = AndroidSQLiteDriver()
    override val hasConnectionPool: Boolean get() = delegate.hasConnectionPool

    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        return object : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement {
                val statement = connection.prepare(sql)
                return object : SQLiteStatement by statement {
                    private var executed = false

                    override fun step(): Boolean {
                        if (!executed) {
                            executed = true
                            callback(sql)
                        }
                        return statement.step()
                    }

                    override fun reset() {
                        executed = false
                        statement.reset()
                    }
                }
            }
        }
    }
}
