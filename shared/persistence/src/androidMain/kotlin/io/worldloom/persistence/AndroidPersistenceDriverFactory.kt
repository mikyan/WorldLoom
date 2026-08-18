package io.worldloom.persistence

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.worldloom.persistence.db.WorldloomDatabase

class AndroidPersistenceDriverFactory(
    private val context: Context,
    private val databaseName: String = "worldloom.db",
) {
    fun create(): SqlDriver = AndroidSqliteDriver(WorldloomDatabase.Schema, context, databaseName)
}
