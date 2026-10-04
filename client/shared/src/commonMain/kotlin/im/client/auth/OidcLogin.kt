package im.client.auth

/**
 * OIDC 自动回调：让令牌自己回到客户端，用户不必再从浏览器复制粘贴。
 *
 * 各平台拿回令牌的方式不同：
 *   - Web：页面本身就是回调目标（同源），服务端把令牌放在 fragment，刷新后由 [oidcTokenFromLaunch] 读取
 *   - 桌面：起一个本地回环监听器（127.0.0.1 随机端口），服务端以 query 形式把令牌回跳给它
 *   - Android：用自定义 scheme（[ANDROID_OIDC_REDIRECT]），系统把 URI 交回 App
 *
 * 桌面与 Android 的 return_to 不在本站同源范围内，需要在服务端
 * IM_OIDC_RETURN_ALLOWLIST 里显式放行（见 README）。
 */
sealed interface OidcLoginResult {
    /** 直接拿到了令牌，可以登录 */
    data class Token(val token: String) : OidcLoginResult

    /** 页面已跳去授权方（Web 端）：本次调用到此结束，回来时用 [oidcTokenFromLaunch] 取令牌 */
    data object Redirecting : OidcLoginResult

    /** 拿不到自动回调（未配置白名单、超时、平台不支持），需要用户手工粘贴令牌 */
    data class Manual(val reason: String) : OidcLoginResult
}

/** Android 自定义 scheme 回调地址；服务端白名单里填 `yanshu:` 即可放行 */
const val ANDROID_OIDC_REDIRECT = "yanshu://oidc/callback"

/**
 * 从回调串里取出 token，兼容 fragment（同源页面）与 query（回环监听器 / 自定义 scheme）两种形式。
 * 纯函数，便于单测。
 */
fun extractOidcToken(raw: String): String? {
    if (raw.isEmpty()) return null
    val fragment = raw.substringAfter('#', "")
    val query = raw.substringBefore('#').substringAfter('?', "")
    for (part in (fragment + "&" + query).split('&')) {
        val i = part.indexOf('=')
        if (i <= 0) continue
        if (part.substring(0, i) == "token") {
            val v = part.substring(i + 1)
            if (v.isNotEmpty()) return v
        }
    }
    return null
}

/** 把 return_to 追加到授权地址上 */
fun withReturnTo(authorizeUrl: String, returnTo: String): String {
    val sep = if (authorizeUrl.contains('?')) "&" else "?"
    return authorizeUrl + sep + "return_to=" + encodeComponent(returnTo)
}

/** 只编码会破坏 query 结构的字符；`:` 与 `/` 也编掉，避免个别代理改写 */
internal fun encodeComponent(s: String): String = buildString {
    for (c in s) {
        when {
            c.isLetterOrDigit() || c in "-._~" -> append(c)
            c == ':' -> append("%3A")
            c == '/' -> append("%2F")
            else -> append(c)
        }
    }
}

/**
 * 打开授权页并等待回调。
 * 返回 [OidcLoginResult.Manual] 表示本平台/本次配置拿不到自动回调，UI 应回退到手工粘贴。
 */
expect suspend fun oidcLogin(authorizeUrl: String, timeoutMs: Long): OidcLoginResult

/**
 * 启动时取回上一次 OIDC 回调留下的令牌（Web 读 location.hash；Android 读启动 Intent）。
 * 桌面端由 [oidcLogin] 直接返回，这里是 null。
 */
expect fun oidcTokenFromLaunch(): String?
