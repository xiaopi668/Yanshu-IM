package im.client.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 朋友圈正文、群名这类用户输入曾用字符串拼接进 JSON，
 * 一遇到换行就变成非法 JSON（服务端报 invalid character '\n' in string literal）。
 * 现在改成 buildJsonObject，这里锁住这个行为。
 */
class JsonBodyTest {

    // 与 Api.createMoment 相同的构造方式
    private fun momentBody(text: String, images: List<String>): String = buildJsonObject {
        put("text", text)
        put("images", buildJsonArray { images.forEach { add(JsonPrimitive(it)) } })
    }.toString()

    @Test
    fun multilineTextStaysValidJson() {
        val text = "第一行\n第二行\t带制表符"
        val body = momentBody(text, emptyList())
        // 能被解析就说明是合法 JSON
        val parsed = Json.parseToJsonElement(body).jsonObject
        assertEquals(text, parsed["text"]!!.jsonPrimitive.content)
        assertEquals(0, parsed["images"]!!.jsonArray.size)
    }

    @Test
    fun quotesAndBackslashesSurvive() {
        val text = "他说\"你好\"，路径 C:\\temp"
        val parsed = Json.parseToJsonElement(momentBody(text, emptyList())).jsonObject
        assertEquals(text, parsed["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun imageKeysAreAnArray() {
        val parsed = Json.parseToJsonElement(
            momentBody("带图", listOf("k1.jpg", "k2.jpg"))
        ).jsonObject
        assertEquals(2, parsed["images"]!!.jsonArray.size)
        assertEquals("k1.jpg", parsed["images"]!!.jsonArray[0].jsonPrimitive.content)
    }
}
