package im.client.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import android.content.Context

// 由 App 初始化时传入 Context
var appContext: Context? = null

actual fun createDriver(): SqlDriver? {
    val ctx = appContext ?: return null
    return AndroidSqliteDriver(ImDatabase.Schema, ctx, "im.db")
}
