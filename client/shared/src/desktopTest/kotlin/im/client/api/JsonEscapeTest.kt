package im.client.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 请求体是手拼 JSON，密码/邮箱里的特殊字符必须转义，
 * 否则会拼出非法 JSON（服务端只回 400，排查很费劲）。
 *
 * 期望值用拼接构造，避免在测试里堆一串反斜杠把人看晕。
 */
class JsonEscapeTest {

    @Test
    fun plainTextUnchanged() {
        assertEquals("abc123", jsonEscape("abc123"))
        assertEquals("中文也不动", jsonEscape("中文也不动"))
    }

    @Test
    fun quoteAndBackslashEscaped() {
        val input = "a\"b\\c"
        val expected = "a" + "\\\"" + "b" + "\\\\" + "c"
        assertEquals(expected, jsonEscape(input))
    }

    @Test
    fun controlCharsEscaped() {
        val input = "l1\nl2\ttab"
        val expected = "l1" + "\\n" + "l2" + "\\t" + "tab"
        assertEquals(expected, jsonEscape(input))
    }

    @Test
    fun controlCharBelowSpaceUsesUnicodeEscape() {
        // 0x01 这类不可见字符不能原样写出
        assertEquals("\\u0001", jsonEscape("\u0001"))
    }

    @Test
    fun outputFitsIntoJsonBody() {
        // 真实场景：把转义结果拼进 JSON，整体应与手写的期望串完全一致
        val body = "{\"p\":\"" + jsonEscape("a\"b\\c") + "\"}"
        assertEquals("{\"p\":\"a\\\"b\\\\c\"}", body)
        // 反解：把 \" 还原成引号后，内容应与原始输入一致（证明没有丢字符）
        val decoded = body.removePrefix("{\"p\":\"").removeSuffix("\"}")
            .replace("\\\"", "\"").replace("\\\\", "\\")
        assertEquals("a\"b\\c", decoded)
    }
}
