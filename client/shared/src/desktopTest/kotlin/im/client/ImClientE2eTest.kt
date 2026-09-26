package im.client

import im.client.net.ConnState
import im.client.proto.MsgType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import im.client.api.Api
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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



class Phase2E2eTest {
    private val apiBase = System.getenv("IM_TEST_API") ?: "http://127.0.0.1:10002"
    private val wsBase = System.getenv("IM_TEST_WS") ?: "ws://127.0.0.1:10001/ws"
    private val api = Api(apiBase)

    @Test
    fun yidRegisterSearchAndContactFlow() = runBlocking {
        // 注册自定义雁书号
        val suffix = System.currentTimeMillis().toString(36)
        val yid = "e2e_$suffix"
        val rA = api.registerRaw("t2a_$suffix", "secret1", "A2", yid)
        val uidA = rA.uid; val tokA = rA.token
        assertTrue(yid == api.meYid(tokA), "me 应返回雁书号")

        // 按雁书号搜索
        val found = api.searchByYid(tokA, yid)
        assertTrue(found.uid == uidA)

        // 好友申请 → 接受 → 通讯录可见
        val rB = api.registerRaw("t2b_$suffix", "secret1", "B2", "b_$suffix")
        val uidB = rB.uid; val tokB = rB.token
        api.requestContact(tokA, uidB, "交个朋友")
        val reqs = api.contactRequests(tokB)
        assertTrue(reqs.any { it.id.isNotEmpty() && it.status == "pending" && it.from_uid == uidA })
        val reqId = reqs.first { it.from_uid == uidA }.id
        api.acceptContact(tokB, reqId)

        val contactsA = api.contacts(tokA)
        assertTrue(contactsA.any { it.uid == uidB })
        val contactsB = api.contactRequests(tokB)
        assertTrue(contactsB.any { it.id == reqId && it.status == "accepted" })
    }

    @Test
    fun momentsCreateLikeComment() = runBlocking {
        val suffix = System.currentTimeMillis().toString(36)
        val rA = api.registerRaw("t3a_$suffix", "secret1", "A3", "t3a_$suffix")
        val uidA = rA.uid; val tokA = rA.token
        val rB = api.registerRaw("t3b_$suffix", "secret1", "B3", "t3b_$suffix")
        val tokB = rB.token
        // A 发动态
        val mid = api.createMoment(tokA, "hello moments $suffix", emptyList())
        assertTrue(mid.isNotEmpty())
        // A/B 互加好友（直加接口，仅为让 B 能看到）
        api.requestContact(tokB, uidA, "hi")
        val reqs = api.contactRequests(tokA)
        reqs.filter { it.status == "pending" && it.from_uid != uidA }.forEach {
            api.acceptContact(tokA, it.id)
        }
        // B 的 feed 里有 A 的动态
        val feed = api.momentFeed(tokB, "")
        println("DEBUG feed=${feed.map { it.id to it.uid }} contacts=${api.contacts(tokB)}")
        assertTrue(feed.any { it.id == mid && it.text.contains("hello moments") }, "feed=$feed contacts=${api.contacts(tokB)} reqs=${api.contactRequests(tokB)}")
        // 点赞 + 评论
        api.likeMoment(tokB, mid)
        api.commentMoment(tokB, mid, "赞一个")
        val feed2 = api.momentFeed(tokA, "")
        val m = feed2.first { it.id == mid }
        assertTrue(m.likes == 1, "likes=${m.likes}")
        assertTrue(m.comments.isNotEmpty(), "comments=${m.comments}")
        // B 视角应显示已点赞
        val feedB = api.momentFeed(tokB, "")
        assertTrue(feedB.first { it.id == mid }.liked_by_me, "B 应显示已点赞")
    }
}

@kotlinx.serialization.Serializable
internal data class RegResp(val uid: String, val token: String, val yid: String)

suspend internal fun Api.registerRaw(username: String, password: String, nickname: String, yid: String): RegResp {
    val body = im.client.api.json.encodeToString(
        mapOf("username" to username, "password" to password, "nickname" to nickname, "yid" to yid)
    )
    val text = rawRequest("POST", "/v1/register", body, null)
    return im.client.api.json.decodeFromString<RegResp>(text)
}

suspend fun Api.meYid(token: String): String {
    val text = rawRequest("GET", "/v1/me", null, token)
    return im.client.api.json.parseToJsonElement(text).jsonObject["yid"]?.jsonPrimitive?.content ?: ""
}


class Phase3E2eTest {
    private val apiBase = System.getenv("IM_TEST_API") ?: "http://127.0.0.1:10002"

    @Test
    fun messageSearch() = runBlocking {
        val api = Api(apiBase)
        val suffix = System.currentTimeMillis().toString(36)
        val rA = api.registerRaw("t4a_$suffix", "secret1", "A4", "t4a_$suffix")
        val tokA = rA.token
        val rB = api.registerRaw("t4b_$suffix", "secret1", "B4", "t4b_$suffix")
        val uidB = rB.uid; val tokB = rB.token
        // 建会话并发送带关键词的消息
        val convId = api.createSingle(tokA, uidB)
        val client = ImClient(apiBase, System.getenv("IM_TEST_WS") ?: "ws://127.0.0.1:10001/ws")
        client.wireCallbacks()
        client.login("t4a_$suffix", "secret1")
        client.startSession()
        client.connectionState.first { it == im.client.net.ConnState.Authenticated }
        client.sendMessage(convId, "XYZZY $suffix")
        kotlinx.coroutines.delay(800)
        val hits = api.searchMessages(tokB, "XYZZY $suffix")
        assertTrue(hits.any { it.text.contains("XYZZY") && it.conversation_id == convId })
        client.stop()
    }
}
