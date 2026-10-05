package im.client.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 登录请求必须带上人机验证令牌。
 *
 * 服务端在站点开启人机验证时会校验 /v1/login 的 turnstile_token（main.go 的 checkTurnstile
 * 对登录和注册都做检查）。而客户端这边曾经**根本没有这个参数** —— 令牌永远发不出去，
 * 于是只要站点开了人机验证，登录必然被拒，界面上还看不出任何原因。
 *
 * 这个测试守住「令牌确实进了请求体」。
 */
class LoginTurnstileTest {
    private lateinit var server: HttpServer
    private var port = 0
    private var lastBody = ""
    private var lastPath = ""

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
        lastPath = ex.requestURI.path
        lastBody = ex.requestBody.readBytes().decodeToString()
        val body = """{"uid":"u1","token":"tok-from-server","yid":"ys12345678"}""".toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    @Test
    fun loginSendsTurnstileToken() {
        runBlocking {
            Api("http://127.0.0.1:$port").login("alice", "pw", "web", "ts-token-abc")
        }
        assertEquals("/v1/login", lastPath)
        assertTrue(lastBody.contains(""""username":"alice""""), "请求体应含用户名：$lastBody")
        assertTrue(
            lastBody.contains(""""turnstile_token":"ts-token-abc""""),
            "登录请求必须带人机验证令牌，实际：$lastBody",
        )
    }

    @Test
    fun loginWithoutTurnstileStillWorks() {
        // 站点没开人机验证时是空串，服务端不校验
        runBlocking {
            Api("http://127.0.0.1:$port").login("bob", "pw", "web")
        }
        assertTrue(lastBody.contains(""""turnstile_token":""""), "字段应始终存在（空串即可）：$lastBody")
    }
}
