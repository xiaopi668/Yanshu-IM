package im.client.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 错误文案必须是「人能读的一句话」。
 * 之前是 'HTTP 400: {"error":"..."}' —— 把原始 JSON 响应体直接显示给用户。
 */
class ApiErrorMessageTest {

    @Test
    fun extractsErrorFieldFromJson() {
        assertEquals(
            "yid: 5-20位，字母开头，可含数字/_/-",
            httpErrorMessage(400, """{"error":"yid: 5-20位，字母开头，可含数字/_/-"}"""),
        )
    }

    @Test
    fun acceptsMessageFieldToo() {
        assertEquals("邮箱格式不正确", httpErrorMessage(400, """{"message":"邮箱格式不正确"}"""))
    }

    @Test
    fun prefersErrorOverMessage() {
        assertEquals("甲", httpErrorMessage(400, """{"error":"甲","message":"乙"}"""))
    }

    @Test
    fun passesThroughShortPlainText() {
        assertEquals("service unavailable", httpErrorMessage(503, "service unavailable"))
    }

    @Test
    fun neverShowsRawJsonOrHtml() {
        val noField = httpErrorMessage(400, """{"foo":"bar"}""")
        assertTrue(!noField.contains("{"), "不应把 JSON 显示给用户：$noField")
        val html = httpErrorMessage(502, "<html><body>502 Bad Gateway</body></html>")
        assertTrue(!html.contains("<"), "不应把 HTML 显示给用户：$html")
        val long = httpErrorMessage(500, "x".repeat(5000))
        assertTrue(long.length < 100, "超长正文应替换为兜底文案，实际长度 " + long.length)
    }

    @Test
    fun mapsCommonStatusCodesWhenBodyEmpty() {
        assertEquals("登录已过期，请重新登录", httpErrorMessage(401, ""))
        assertEquals("没有权限执行该操作", httpErrorMessage(403, ""))
        assertEquals("操作太频繁，请稍后再试", httpErrorMessage(429, ""))
        assertTrue(httpErrorMessage(500, "").contains("服务端异常"))
        assertTrue(httpErrorMessage(418, "").contains("418"))
    }

    @Test
    fun exceptionCarriesStatusForProgrammaticChecks() {
        val e = ApiException(httpErrorMessage(401, ""), 401, """{"error":"token revoked"}""")
        assertEquals(401, e.status)
        assertEquals("登录已过期，请重新登录", e.message)
        assertEquals("""{"error":"token revoked"}""", e.body)
    }
}
