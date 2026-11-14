package com.metrolist.shared.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import java.util.Collections

internal class QueryRecordingDriver : SQLiteDriver {
    var onQuery: (String) -> Unit = {}
    private val delegate = JdbcSQLiteDriver()
    private val queries = Collections.synchronizedList(mutableListOf<String>())

    fun queries(): List<String> = synchronized(queries) { queries.toList() }

    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        return object : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement {
                val statement = connection.prepare(sql)
                return object : SQLiteStatement by statement {
                    private var started = false

                    override fun step(): Boolean {
                        if (!started) {
                            started = true
                            queries.add(sql)
                            onQuery(sql)
                        }
                        return statement.step()
                    }

                    override fun reset() {
                        statement.reset()
                        started = false
                    }
                }
            }
        }
    }
}
