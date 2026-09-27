package im.client.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** 断线重连验证：无服务监听时建连必失败，应按退避重试；disconnect() 后不再重连 */
class ImConnectionReconnectTest {
    @Test
    fun failedConnectRetriesAndDisconnectStopsIt() = runBlocking {
        val conn = ImConnection("ws://127.0.0.1:1/ws")   // 端口 1 无监听，建连必然失败
        conn.connect("fake-token")
        try {
            // 首次失败 → Disconnected，随后按指数退避发起第二次尝试 → Connecting
            withTimeout(15_000) {
                conn.state.first { it == ConnState.Disconnected }
                conn.state.first { it == ConnState.Connecting }
            }
        } finally {
            conn.disconnect()
        }
        assertEquals(ConnState.Disconnected, conn.state.value)
        // 主动断开后 3 秒内不应再出现重连尝试
        delay(3_000)
        assertEquals(ConnState.Disconnected, conn.state.value)
    }
}
