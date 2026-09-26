package im.client.db

import app.cash.sqldelight.db.SqlDriver

/** expect/actual：各平台创建 SQLite 驱动；返回 null 表示该平台不持久化（Web 降级为内存缓存） */
expect fun createDriver(): SqlDriver?
