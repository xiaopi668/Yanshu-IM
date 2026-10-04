package im.client.auth

import kotlinx.browser.window

// 浏览器刷新后靠 localStorage 恢复登录态
private const val K_TOKEN = "yanshu.token"
private const val K_API = "yanshu.api"
private const val K_WS = "yanshu.ws"

actual fun saveSession(session: SavedSession) {
    try {
        val ls = window.localStorage
        ls.setItem(K_TOKEN, session.token)
        ls.setItem(K_API, session.apiBase)
        ls.setItem(K_WS, session.wsBase)
    } catch (_: Throwable) {
        // 隐身模式/被策略禁用时写不进去，退化成每次登录
    }
}

actual fun loadSession(): SavedSession? = try {
    val ls = window.localStorage
    val token = ls.getItem(K_TOKEN)
    if (token.isNullOrEmpty()) null
    else SavedSession(token, ls.getItem(K_API) ?: "", ls.getItem(K_WS) ?: "")
} catch (_: Throwable) {
    null
}

actual fun clearSession() {
    try {
        val ls = window.localStorage
        ls.removeItem(K_TOKEN)
        ls.removeItem(K_API)
        ls.removeItem(K_WS)
    } catch (_: Throwable) {
    }
}
