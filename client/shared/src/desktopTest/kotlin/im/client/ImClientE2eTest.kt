package im.client

import im.client.net.ConnState
import im.client.proto.MsgType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 客户端 E2E：两个 ImClient 实例通过真实 gateway/logic 收发消息。
 * 需要本地起 MySQL/Redis + logic + gateway（见 deploy/docker-compose.yml）。
 */
class ImClientE2eTest {
    private val apiBase = System.getenv("IM_TEST_API") ?: "http://127.0.0.1:10002"
    private val wsBase = System.getenv("IM_TEST_WS") ?: "ws://127.0.0.1:10001/ws"

    @Test
    fun aliceToBobMessageDelivery() = runBlocking {
        val alice = ImClient(apiBase, wsBase)
        alice.wireCallbacks()
        val bob = ImClient(apiBase, wsBase)
        bob.wireCallbacks()

        try {
            val (aliceUid, _) = alice.login("alice", "secret1")
            val (bobUid, _) = bob.login("bob", "secret1")
            assertTrue(aliceUid.isNotEmpty() && bobUid.isNotEmpty())

            alice.startSession()
            bob.startSession()
            withTimeout(10_000) {
                alice.connectionState.first { it == ConnState.Authenticated }
                bob.connectionState.first { it == ConnState.Authenticated }
            }

            val convId = alice.openSingle(bobUid)

            coroutineScope {
                val received = launch {
                    withTimeout(15_000) {
                        val msg = bob.connection.notifies
                            .filter { it.conversationId == convId && it.msgType == MsgType.Text }
                            .map { it.text }
                            .first { it.contains("hello bob from kmp client!") }
                        assertTrue(msg.contains("hello bob from kmp client!"))
                    }
                }
                alice.sendMessage(convId, "hello bob from kmp client!")
                received.join()
            }
        } finally {
            alice.stop()
            bob.stop()
        }
    }

    @Test
    fun groupMessageDelivery() = runBlocking {
        val alice = ImClient(apiBase, wsBase)
        alice.wireCallbacks()
        val bob = ImClient(apiBase, wsBase)
        bob.wireCallbacks()

        try {
            val (_, _) = alice.login("alice", "secret1")
            val (bobUid, _) = bob.login("bob", "secret1")

            alice.startSession()
            bob.startSession()
            withTimeout(10_000) {
                alice.connectionState.first { it == ConnState.Authenticated }
                bob.connectionState.first { it == ConnState.Authenticated }
            }

            val gid = alice.api.createGroup(alice.myToken, "test-group", listOf(bobUid))
            val payload = "group hello ${System.currentTimeMillis()}"

            coroutineScope {
                val received = launch {
                    withTimeout(15_000) {
                        val msg = bob.connection.notifies
                            .filter { it.conversationId == gid && it.msgType == MsgType.Text }
                            .map { it.text }
                            .first { it == payload }
                        assertTrue(msg == payload)
                    }
                }
                alice.sendMessage(gid, payload)
                received.join()
            }
        } finally {
            alice.stop()
            bob.stop()
        }
    }
}
