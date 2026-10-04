package im.client.auth

import kotlinx.coroutines.runBlocking
import java.net.URL
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * OIDC 自动回调的本地验证：不需要真实 IdP，也不需要浏览器。
 * 服务端对同源页面用 fragment、对回环/自定义 scheme 用 query，解析必须两种都认。
 */
class OidcCallbackTest {

    @Test
    fun extractTokenFromFragmentAndQuery() {
        // 同源页面：fragment
        assertEquals("tk-1", extractOidcToken("#token=tk-1&uid=u1"))
        // 本地回环监听器 / 自定义 scheme：query
        assertEquals("tk-2", extractOidcToken("http://127.0.0.1:38123/?token=tk-2&uid=u1"))
        assertEquals("tk-3", extractOidcToken("yanshu://oidc/callback?token=tk-3&uid=u1"))
        // 带路径与既有 query
        assertEquals("tk-4", extractOidcToken("yanshu://oidc/callback?a=1&token=tk-4"))
        // 其它参数排在 token 前
        assertEquals("tk-5", extractOidcToken("#uid=u1&token=tk-5"))
    }

    @Test
    fun extractTokenRejectsGarbage() {
        assertNull(extractOidcToken(""))
        assertNull(extractOidcToken("#uid=u1"))
        assertNull(extractOidcToken("?token="))
        assertNull(extractOidcToken("yanshu://oidc/callback"))
    }

    @Test
    fun returnToIsAppendedAndEncoded() {
        val url = withReturnTo("https://idp/authorize?client_id=x", "http://127.0.0.1:38123/")
        // 追加而不是覆盖已有 query
        assertEquals(true, url.startsWith("https://idp/authorize?client_id=x&return_to="))
        // 冒号与斜杠已编码，回环地址不会被 query 结构吃掉
        assertEquals(true, url.endsWith("http%3A%2F%2F127.0.0.1%3A38123%2F"))
    }

    /**
     * 桌面端回环监听器的端到端验证：
     * 真的起监听器，再模拟浏览器「带着令牌回跳」，断言客户端拿到了令牌。
     */
    @Test
    fun desktopLoopbackReceivesToken() {
        runBlocking {
        val result = oidcLoginVia("https://idp.example/authorize?client_id=x", 15_000) { fullUrl ->
            val encoded = fullUrl.substringAfter("return_to=")
            val returnTo = URLDecoder.decode(encoded, "UTF-8")
            // 断言 return_to 指向本机回环，端口是随机分配的
            assertEquals(true, returnTo.startsWith("http://127.0.0.1:"), "return_to=$returnTo")
            // 模拟浏览器回跳（在另一个线程发起，避免阻塞等待方）
            Thread {
                runCatching { URL(returnTo + "?token=loopback-token-1&uid=u1").readText() }
            }.start()
        }
        val token = assertIs<OidcLoginResult.Token>(result, "应拿到令牌，实际 $result")
        assertEquals("loopback-token-1", token.token)
        }
    }

    /** 浏览器一直不回跳时必须超时返回 Manual，而不是永久挂住 */
    @Test
    fun desktopLoopbackTimesOutWithoutCallback() {
        runBlocking {
            val result = oidcLoginVia("https://idp.example/authorize", 1_500) { /* 什么都不做 */ }
            assertIs<OidcLoginResult.Manual>(result, "应回退到手工粘贴，实际 $result")
        }
    }
}
