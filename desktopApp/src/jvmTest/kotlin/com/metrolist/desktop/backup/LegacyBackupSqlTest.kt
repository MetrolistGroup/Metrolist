package com.metrolist.desktop.backup

import com.metrolist.shared.backup.legacySpeedDialIndexExpression
import com.metrolist.shared.backup.legacyTableSourceExpression
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class LegacyBackupSqlTest {
    @Test
    fun speedDialIndexesPreserveLegacyOrdering() {
        val expression = legacySpeedDialIndexExpression("speed_dial_item")

        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("ATTACH DATABASE ':memory:' AS legacy")
                statement.execute("CREATE TABLE speed_dial (id TEXT NOT NULL, \"index\" INTEGER NOT NULL)")
                statement.execute("CREATE TABLE legacy.speed_dial_item (id TEXT NOT NULL, createDate INTEGER NOT NULL)")
                statement.execute("INSERT INTO legacy.speed_dial_item VALUES ('later', 20), ('tie-b', 10), ('tie-a', 10)")
                statement.executeUpdate(
                    """
                    INSERT INTO speed_dial (id, "index")
                    SELECT id, $expression
                    FROM ${legacyTableSourceExpression("speed_dial_item", needsSpeedDialIndex = true)}
                    """.trimIndent(),
                )

                val restored =
                    statement.executeQuery("SELECT id, \"index\" FROM speed_dial ORDER BY \"index\"").use { result ->
                        buildList {
                            while (result.next()) add(result.getString(1) to result.getInt(2))
                        }
                    }
                assertEquals(listOf("tie-a" to 0, "tie-b" to 1, "later" to 2), restored)
            }
        }
    }
}
