package im.client.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import im.client.ImClient
import im.client.file.PickedFile
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 附件链路的本地桩测试：起一个只实现必要接口的 HTTP 服务，不需要真实服务端，也不需要对象存储。
 *
 * 重点守一条安全约束 —— **消息体里只允许出现对象 key，绝不能出现登录 JWT**。
 * 这正是第一轮 P0 修复的内容（此前 Attachment.url 里塞的是 `/v1/download?key=..&token=<7天JWT>`，
 * 会随 MsgNotify 广播给会话全部成员并永久落库）。一旦回归，等于把账号凭证发给每个能看到消息的人。
 */
class AttachmentFlowTest {
    private lateinit var server: HttpServer
    private var port = 0

    private val seen = mutableListOf<String>()
    private val authByPath = mutableMapOf<String, String?>()
    private var putBytes = -1

    private val key = "image/20261004/0123456789abcdef"
    private val jwt = "jwt-token-abcdefghijklmnop"

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> handle(ex) }
        server.start()
        port = server.address.port
    }

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path
        seen += ex.requestMethod + " " + path
        authByPath[path] = ex.requestHeaders.getFirst("Authorization")
        val body = when (path) {
            "/v1/me" ->
                """{"uid":"u1","username":"alice","nickname":"Alice","avatar":"","yid":"ys12345678","yid_changed":true}"""
            "/v1/upload-token" -> """{"key":"$key","put_url":"http://127.0.0.1:$port/put/1"}"""
            "/v1/attachments/ticket" ->
                """{"url":"http://127.0.0.1:$port/v1/download?key=$key&ticket=deadbeef"}"""
            "/put/1" -> {
                putBytes = ex.requestBody.readBytes().size
                ""
            }
            else -> ""
        }
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, if (bytes.isEmpty()) -1L else bytes.size.toLong())
        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
    }

    private fun api() = Api("http://127.0.0.1:$port")

    @Test
    fun attachmentUrlGoesThroughTicketEndpoint() = runBlocking {
        val url = api().attachmentUrl(jwt, key)
        assertTrue(seen.contains("POST /v1/attachments/ticket"), "应调用票据接口，实际: $seen")
        // 登录凭据走 Authorization 头，不进 URL
        assertEquals("Bearer $jwt", authByPath["/v1/attachments/ticket"])
        assertTrue(url.contains("ticket="), "返回的地址应带票据: $url")
        assertFalse(url.contains(jwt), "下载地址里不能出现登录 JWT: $url")
    }

    @Test
    fun uploadAttachmentReturnsKeyAndPutsBytes() = runBlocking {
        val data = ByteArray(1024) { it.toByte() }
        val got = api().uploadAttachment(jwt, "image", data)
        assertEquals(key, got)
        assertEquals(1024, putBytes, "应把字节 PUT 到预签名地址")
        assertEquals("Bearer $jwt", authByPath["/v1/upload-token"])
    }

    @Test
    fun sendingAttachmentStoresKeyNotToken() = runBlocking {
        val client = ImClient("http://127.0.0.1:$port", "ws://127.0.0.1:1/ws")
        client.loginWithToken(jwt)
        val conv = "s_1_2"
        client.sendAttachment(conv, PickedFile("a.png", "image/png", ByteArray(64)))

        val msg = assertNotNull(client.storeInternal().messages.value[conv]?.firstOrNull(), "消息应已入本地存储")
        val url = msg.attachment?.url ?: ""
        assertEquals(key, url, "消息里应只存对象 key")
        assertFalse(url.contains(jwt), "消息体里绝不能出现登录 JWT")
        assertTrue(url.isNotBlank() && !url.startsWith("http"), "不应是完整 URL: $url")
    }
}
