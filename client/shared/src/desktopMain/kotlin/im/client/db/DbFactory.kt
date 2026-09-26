package im.client.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual fun createDriver(): SqlDriver? {
    val dir = File(System.getProperty("user.home"), ".yanshu")
    dir.mkdirs()
    val file = File(dir, "im.db")
    val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
    try {
        ImDatabase.Schema.create(driver)
    } catch (e: Throwable) {
        System.err.println("[db] schema create failed: ${e.message}")
        // 表已存在
    }
    return driver
}
