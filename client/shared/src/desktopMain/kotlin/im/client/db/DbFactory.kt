package im.client.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual fun createDriver(): SqlDriver? {
    // 本地库默认放 ~/.yanshu；可用 IM_CLIENT_DB_DIR 重定向。
    // 这个口子不只是为了便携安装：集成测试会切换账号，触发 clearCaches()，
    // 默认路径下等于把开发者真实的本地消息缓存清掉，所以测试必须指到临时目录。
    val dir = System.getenv("IM_CLIENT_DB_DIR")?.takeIf { it.isNotBlank() }?.let { File(it) }
        ?: File(System.getProperty("user.home"), ".yanshu")
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
