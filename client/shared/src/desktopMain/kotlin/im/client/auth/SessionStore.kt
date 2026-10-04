package im.client.auth

import java.util.prefs.Preferences

private const val NODE = "im/yanshu"

actual fun saveSession(session: SavedSession) {
    try {
        val p = Preferences.userRoot().node(NODE)
        p.put("token", session.token)
        p.put("api", session.apiBase)
        p.put("ws", session.wsBase)
        p.flush()
    } catch (_: Throwable) {
        // 存不下就退化成「每次都要登录」，不该因此挡住应用启动
    }
}

actual fun loadSession(): SavedSession? = try {
    val p = Preferences.userRoot().node(NODE)
    val token = p.get("token", "")
    if (token.isEmpty()) null
    else SavedSession(token, p.get("api", ""), p.get("ws", ""))
} catch (_: Throwable) {
    null
}

actual fun clearSession() {
    try {
        val p = Preferences.userRoot().node(NODE)
        p.clear()
        p.flush()
    } catch (_: Throwable) {
    }
}
