package com.metrolist.shared.db

import app.cash.sqldelight.db.SqlDriver

expect class DatabaseDriverFactory {
    fun createDriver(): SqlDriver
}

fun createDatabase(driverFactory: DatabaseDriverFactory): MetrolistDatabase {
    val driver = driverFactory.createDriver()
    return MetrolistDatabase(driver)
}
