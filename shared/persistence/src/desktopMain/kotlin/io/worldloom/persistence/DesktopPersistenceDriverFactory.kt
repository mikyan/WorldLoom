package io.worldloom.persistence

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.worldloom.persistence.db.WorldloomDatabase
import java.util.Properties

class DesktopPersistenceDriverFactory(
    private val databasePath: String,
) {
    fun create(): SqlDriver {
        val properties = Properties().apply { put("foreign_keys", "true") }
        return JdbcSqliteDriver("jdbc:sqlite:$databasePath", properties, WorldloomDatabase.Schema)
    }
}
