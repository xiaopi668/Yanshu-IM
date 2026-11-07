package im.client.totp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 存档文本的格式。
 *
 * 这段文字是用户在「手机丢了」时唯一能读到的指引，格式错了不会崩，
 * 但会让人看不懂怎么用 —— 所以钉住几个关键事实。
 */
class RecoveryTextTest {

    private val codes = listOf("9ce8-89ba", "1b74-830f", "96d2-b8d7")

    @Test
    fun containsAllCodesAndCount() {
        val text = recoveryCodesAsText("alice", codes, "2026-11-08 00:30")
        codes.forEach { c -> assertTrue(text.contains(c), "缺少恢复码 " + c) }
        assertTrue(text.contains("共 3 个"), "应写明数量")
        assertTrue(text.contains("alice"), "应写明账号")
        assertTrue(text.contains("2026-11-08 00:30"), "应写明生成时间")
    }

    @Test
    fun explainsOneTimeUse() {
        val text = recoveryCodesAsText("", codes, "")
        // 空账号/空时间时不应该留下 "账号：" 这种半截行
        assertTrue(!text.contains("账号：\n"), "空账号不应留下空标签")
        assertTrue(text.contains("每个只能使用一次"), "必须说明一次性")
        assertTrue(text.contains("两步验证码"), "必须说明填在哪里")
    }

    @Test
    fun numberingIsPadded() {
        val text = recoveryCodesAsText("bob", List(12) { "c-" + it }, "")
        // 个位数补空格，双位数不补 —— 打印出来两列才对得齐
        assertTrue(text.contains("   1.  c-0"), "个位编号应右补空格")
        assertTrue(text.contains("  10.  c-9"), "两位编号不应多空格")
    }
}
