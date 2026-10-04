package im.client.auth

/**
 * 登录会话。
 * token 必须和所连的服务端地址一起存 —— 只存 token 的话，改了 API 地址会拿旧 token 打到错误的服务端。
 */
data class SavedSession(val token: String, val apiBase: String, val wsBase: String)

/**
 * 登录态持久化（桌面=Preferences、Android=SharedPreferences、Web=localStorage）。
 * 没有它，桌面/Android 重启、Web 一刷新就会掉回登录页。
 */
expect fun saveSession(session: SavedSession)

/** 没有存过或读取失败返回 null */
expect fun loadSession(): SavedSession?

/** 退出登录、token 失效时调用 */
expect fun clearSession()
