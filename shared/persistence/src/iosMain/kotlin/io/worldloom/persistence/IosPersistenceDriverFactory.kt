package io.worldloom.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import io.worldloom.persistence.db.WorldloomDatabase

class IosPersistenceDriverFactory(
    private val databaseName: String = "worldloom.db",
) {
    fun create(): SqlDriver = NativeSqliteDriver(WorldloomDatabase.Schema, databaseName)
}
