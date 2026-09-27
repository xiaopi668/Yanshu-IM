package im.client.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.close
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

actual class WsSocket actual constructor(
    private val url: String,
    private val scope: CoroutineScope,
) {
    private val client = HttpClient(Js) { install(WebSockets) }
    private var session: io.ktor.websocket.DefaultWebSocketSession? = null
    private var readJob: Job? = null

    actual suspend fun open(onFrame: (ByteArray) -> Unit): Boolean {
        val opened = CompletableDeferred<Boolean>()
        readJob = scope.launch {
            try {
                client.webSocket(urlString = url) {
                    session = this
                    opened.complete(true)
                    for (frame in incoming) {
                        if (frame is Frame.Binary) onFrame(frame.readBytes())
                    }
                    opened.complete(false) // 服务端断开
                }
            } catch (_: Throwable) {
                opened.complete(false)
            }
        }
        return opened.await()
    }

    actual suspend fun awaitClosed() {
        readJob?.join()
    }

    actual suspend fun send(bytes: ByteArray) {
        session?.send(Frame.Binary(true, bytes))
    }

    actual fun close() {
        scope.launch {
            try {
                session?.close(CloseReason(CloseReason.Codes.NORMAL, "bye"))
                client.close()
            } catch (_: Throwable) {
                // 关闭失败（连接已断）直接忽略
            }
        }
    }
}
