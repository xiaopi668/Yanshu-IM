package im.client.net

import kotlinx.coroutines.CoroutineScope

/** expect/actual：各平台用 Ktor WebSocket。open 挂起直到连接建立（或失败返回 false）。 */
expect class WsSocket(url: String, scope: CoroutineScope) {
    suspend fun open(onFrame: (ByteArray) -> Unit): Boolean
    suspend fun send(bytes: ByteArray)
    fun close()
}
