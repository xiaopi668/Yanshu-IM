package im.client.auth

import kotlinx.browser.window

/**
 * Web 端：页面自己就是回调目标（与 API 同源时，服务端把令牌放在 fragment）。
 * 这里直接在当前标签页跳去授权方；页面的下一次加载由 [oidcTokenFromLaunch] 读取 fragment 完成登录。
 */
@JsFun("(url) => { window.location.href = url; }")
private external fun jsNavigate(url: String)

@JsFun("() => window.location.hash")
private external fun jsHash(): String

@JsFun("() => { const u = window.location; history.replaceState(null, '', u.pathname + u.search); }")
private external fun jsClearHash()

actual suspend fun oidcLogin(authorizeUrl: String, timeoutMs: Long): OidcLoginResult {
    val here = window.location.href.substringBefore('#')
    jsNavigate(withReturnTo(authorizeUrl, here))
    // 页面正在跳转，本次调用不会再有结果；回来后走 oidcTokenFromLaunch()
    return OidcLoginResult.Redirecting
}

actual fun oidcTokenFromLaunch(): String? {
    val hash = jsHash()
    if (hash.isEmpty()) return null
    val token = extractOidcToken(hash) ?: return null
    // 立刻把令牌从地址栏抹掉：浏览器历史、截图、分享链接里都不该留下它
    jsClearHash()
    return token
}
