package im.client.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 登录时判断「是不是缺第二因子」。
 *
 * 这个判定直接决定界面显示「验证码输入框」还是「密码错误」——
 * 判错会让用户去改密码，越改越乱（人机验证那次踩过同类问题）。
 */
class TotpRequiredTest {

    private fun err(body: String) = ApiException("x", 401, body)

    @Test
    fun detectsTotpRequired() {
        assertTrue(err("""{"error":"需要两步验证验证码","totp_required":true}""").isTotpRequired())
        // 字段顺序与空白不同也要认出来
        assertTrue(err("""{ "totp_required" : true , "error" : "x" }""").isTotpRequired())
    }

    @Test
    fun falseForOtherErrors() {
        assertFalse(err("""{"error":"bad credentials"}""").isTotpRequired())
        assertFalse(err("""{"error":"totp_required is just text here"}""").isTotpRequired())
        assertFalse(err("""{"totp_required":false}""").isTotpRequired())
    }

    @Test
    fun tolerantToNonJsonBody() {
        // 反代返回 HTML、或空响应时不能抛异常
        assertFalse(err("<html>502 Bad Gateway</html>").isTotpRequired())
        assertFalse(err("").isTotpRequired())
    }
}
