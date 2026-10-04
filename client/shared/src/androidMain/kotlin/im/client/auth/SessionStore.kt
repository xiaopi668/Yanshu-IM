package im.client.auth

import android.content.Context
import android.content.SharedPreferences
import im.client.db.appContext

private const val FILE = "yanshu_session"

private fun prefs(): SharedPreferences? = appContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)

actual fun saveSession(session: SavedSession) {
    try {
        prefs()?.edit()
            ?.putString("token", session.token)
            ?.putString("api", session.apiBase)
            ?.putString("ws", session.wsBase)
            ?.apply()
    } catch (_: Throwable) {
    }
}

// 块状体：表达式体函数里不能写 return，android 这侧本机没有 SDK 编不到，只能靠读代码保证
actual fun loadSession(): SavedSession? {
    try {
        val p = prefs() ?: return null
        val token = p.getString("token", "") ?: ""
        if (token.isEmpty()) return null
        return SavedSession(token, p.getString("api", "") ?: "", p.getString("ws", "") ?: "")
    } catch (_: Throwable) {
        return null
    }
}

actual fun clearSession() {
    try {
        prefs()?.edit()?.clear()?.apply()
    } catch (_: Throwable) {
    }
}
