package com.metrolist.shared.db

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.throwSQLiteException
import org.sqlite.core.CoreResultSet
import org.sqlite.core.CoreStatement
import org.sqlite.jdbc4.JDBC4ResultSet
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

private inline fun <T> sqliteCall(block: () -> T): T =
    try {
        block()
    } catch (error: SQLException) {
        throwSQLiteException(error.errorCode, error.message)
    }

class JdbcSQLiteDriver : SQLiteDriver {
    override val hasConnectionPool: Boolean = false

    override fun open(fileName: String): SQLiteConnection =
        sqliteCall {
            Class.forName("org.sqlite.JDBC")
            val connection = java.sql.DriverManager.getConnection("jdbc:sqlite:$fileName")
            // A competing writer (another instance, history import) can hold the lock for several seconds.
            connection.createStatement().use { it.execute("PRAGMA busy_timeout = 15000") }
            JdbcSQLiteConnection(connection)
        }
}

private class JdbcSQLiteConnection(
    private val delegate: java.sql.Connection,
) : SQLiteConnection {
    private var transactionDepth = 0

    override fun prepare(sql: String): SQLiteStatement =
        sqliteCall {
            JdbcSQLiteStatement(delegate.prepareStatement(sql)) {
                val normalizedSql = sql.trimStart().uppercase()
                when (normalizedSql.substringBefore(' ')) {
                    "BEGIN" -> transactionDepth = 1
                    "SAVEPOINT" -> transactionDepth++
                    "RELEASE" -> transactionDepth = (transactionDepth - 1).coerceAtLeast(0)
                    "COMMIT", "END" -> transactionDepth = 0
                    "ROLLBACK" -> if (!normalizedSql.startsWith("ROLLBACK TO")) transactionDepth = 0
                }
            }
        }

    override fun inTransaction(): Boolean = transactionDepth > 0 || !delegate.autoCommit

    override fun close() = sqliteCall { delegate.close() }
}

private class JdbcSQLiteStatement(
    private val delegate: PreparedStatement,
    private val onExecuted: () -> Unit,
) : SQLiteStatement {
    private val sqliteStatement = delegate as CoreStatement

    // Reuse sqlite-jdbc's prepared names; its live result set clears metadata on reset.
    private val columnNames = sqliteCall { (delegate.metaData as CoreResultSet).colsMeta }
    private val metadata by lazy { JDBC4ResultSet(sqliteStatement).apply { colsMeta = columnNames } }
    private val columnTypes by lazy { IntArray(columnNames.size) { index -> sqliteCall { metadata.getColumnType(index + 1) } } }
    private var resultSet: ResultSet? = null
    private var executed = false

    override fun bindBlob(
        index: Int,
        value: ByteArray,
    ) = sqliteCall { delegate.setBytes(index, value) }

    override fun bindDouble(
        index: Int,
        value: Double,
    ) = sqliteCall { delegate.setDouble(index, value) }

    override fun bindLong(
        index: Int,
        value: Long,
    ) = sqliteCall { delegate.setLong(index, value) }

    override fun bindText(
        index: Int,
        value: String,
    ) = sqliteCall { delegate.setString(index, value) }

    override fun bindNull(index: Int) = sqliteCall { delegate.setNull(index, Types.NULL) }

    override fun getBlob(index: Int): ByteArray = sqliteCall { requireResultSet().getBytes(index + 1) }

    override fun getDouble(index: Int): Double = sqliteCall { requireResultSet().getDouble(index + 1) }

    override fun getLong(index: Int): Long = sqliteCall { requireResultSet().getLong(index + 1) }

    override fun getText(index: Int): String = sqliteCall { requireResultSet().getString(index + 1) }

    override fun isNull(index: Int): Boolean = sqliteCall { rowColumnType(requireResultSet(), index) == SQLITE_DATA_NULL }

    override fun getColumnCount(): Int = columnNames.size

    override fun getColumnName(index: Int): String = columnNames[index]

    override fun getColumnType(index: Int): Int =
        resultSet?.let { result ->
            sqliteCall { rowColumnType(result, index) }
        } ?: when (columnTypes[index]) {
            Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> SQLITE_DATA_BLOB
            Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> SQLITE_DATA_FLOAT
            Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.BIT, Types.BOOLEAN -> SQLITE_DATA_INTEGER
            Types.NULL -> SQLITE_DATA_NULL
            else -> SQLITE_DATA_TEXT
        }

    override fun step(): Boolean {
        return sqliteCall {
            if (!executed) {
                executed = true
                if (!delegate.execute()) {
                    onExecuted()
                    return@sqliteCall false
                }
                onExecuted()
                resultSet = delegate.resultSet
            }
            resultSet?.next() == true
        }
    }

    override fun reset() {
        sqliteCall {
            resultSet?.close()
            resultSet = null
            executed = false
        }
    }

    override fun clearBindings() = sqliteCall { delegate.clearParameters() }

    override fun close() {
        sqliteCall {
            resultSet?.close()
            delegate.close()
        }
    }

    private fun rowColumnType(
        result: ResultSet,
        index: Int,
    ): Int {
        val column = (result as CoreResultSet).checkCol(index + 1)
        // JDBC metadata uses declared types for typed NULLs; SQLite's row type preserves NULL without decoding values.
        return sqliteStatement.pointer.safeRunInt<SQLException> { db, pointer -> db.column_type(pointer, column) }
    }

    private fun requireResultSet(): ResultSet = checkNotNull(resultSet) { "Statement has no result row" }
}
