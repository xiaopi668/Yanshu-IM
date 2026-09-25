package im.client

import kotlin.time.TimeSource

actual fun platformOf(): String = "web"

// 单调时钟毫秒：仅用于本地 pending 消息占位显示，服务端 notify 会带真实 sentAt 覆盖
private val startedAt = TimeSource.Monotonic.markNow()

actual fun currentTimeMillis(): Long = startedAt.elapsedNow().inWholeMilliseconds
