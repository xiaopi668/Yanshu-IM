package im.client.net

import kotlinx.coroutines.CoroutineScope

/** expect/actual：各平台用 Ktor WebSocket。open 挂起直到连接建立（或失败返回 false）。 */
expect class WsSocket(url: String, scope: CoroutineScope) {
    suspend fun open(onFrame: (ByteArray) -> Unit): Boolean
    /** 挂起直到读循环退出（服务端断开 / 连接异常 / close()），上层据此判定会话结束并重连 */
    suspend fun awaitClosed()
    suspend fun send(bytes: ByteArray)
    fun close()
}
