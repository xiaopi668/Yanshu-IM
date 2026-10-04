package im.client.auth

import android.net.Uri
import im.client.openUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android 端：用自定义 scheme 把令牌交回 App（需要 AndroidManifest 给 MainActivity 配 intent-filter，
 * 并设 launchMode=singleTask，这样 App 在后台时走 onNewIntent 而不是新起一个实例）。
 */
object AndroidOidc {
    /** 正在等待回调的登录流程 */
    private var pending: CompletableDeferred<String>? = null

    /** 冷启动时 Intent 就带着令牌（App 被回收后由 deep link 拉起），此时还没有等待者 */
    private var launched: String? = null

    /** MainActivity 收到 URI 时调用；onCreate 与 onNewIntent 都走这里 */
    fun onUri(uri: Uri?) {
        val token = extractOidcToken(uri?.toString() ?: "") ?: return
        val waiter = pending
        if (waiter != null && !waiter.isCompleted) waiter.complete(token) else launched = token
    }

    /** 提供者本来可以用 Intent 直接判断，这里统一成 suspend 等待 */
    fun newWaiter(): CompletableDeferred<String> = CompletableDeferred<String>().also { pending = it }

    fun clearWaiter() {
        pending = null
    }

    fun takeLaunched(): String? = launched.also { launched = null }
}

actual suspend fun oidcLogin(authorizeUrl: String, timeoutMs: Long): OidcLoginResult {
    val waiter = AndroidOidc.newWaiter()
    return try {
        openUrl(withReturnTo(authorizeUrl, ANDROID_OIDC_REDIRECT))
        val token = withTimeoutOrNull(timeoutMs) { waiter.await() }
        if (token != null) {
            OidcLoginResult.Token(token)
        } else {
            OidcLoginResult.Manual("等待浏览器回调超时；请确认服务端 IM_OIDC_RETURN_ALLOWLIST 已放行 yanshu:")
        }
    } finally {
        AndroidOidc.clearWaiter()
    }
}

actual fun oidcTokenFromLaunch(): String? = AndroidOidc.takeLaunched()
