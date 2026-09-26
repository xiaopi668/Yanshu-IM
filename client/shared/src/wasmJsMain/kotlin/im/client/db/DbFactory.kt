package im.client.db

import app.cash.sqldelight.db.SqlDriver

/** Web 端暂不持久化（内存缓存 + 服务端同步） */
actual fun createDriver(): SqlDriver? = null
