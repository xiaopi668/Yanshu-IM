package im.client.auth

import com.sun.net.httpserver.HttpServer
import im.client.openUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress

/**
 * 桌面端：起一个本地回环监听器当回调目标（RFC 8252 推荐做法）。
 *
 * 监听器只绑定 127.0.0.1、端口随机、收到一次令牌即结束，避免长期占用端口。
 * 服务端需要把 `http://127.0.0.1` 放进 IM_OIDC_RETURN_ALLOWLIST。
 */
actual suspend fun oidcLogin(authorizeUrl: String, timeoutMs: Long): OidcLoginResult =
    oidcLoginVia(authorizeUrl, timeoutMs) { fullUrl -> openUrl(fullUrl) }

/**
 * 可测试版本：把「最终要打开的授权地址（已带 return_to）」交给调用方。
 * 生产代码传 openUrl；测试传一个直接向回环地址发起请求的 lambda，从而在无浏览器环境下
 * 也能完整验证监听器这条链路。
 */
internal suspend fun oidcLoginVia(
    authorizeUrl: String,
    timeoutMs: Long,
    onAuthorizeUrl: (String) -> Unit,
): OidcLoginResult {
    val server = try {
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    } catch (e: Throwable) {
        return OidcLoginResult.Manual("无法启动本地回环监听器：${e.message}")
    }
    val got = CompletableDeferred<String?>()
    server.createContext("/") { ex ->
        val token = extractOidcToken(ex.requestURI.toString())
        val html = if (token != null) {
            "<!doctype html><meta charset=utf-8><title>雁书 · 登录成功</title>" +
                "<body style=\"font-family:system-ui;padding:40px;text-align:center\">" +
                "<h2>登录成功</h2><p>可以关闭此页面，回到雁书客户端。</p></body>"
        } else {
            "<!doctype html><meta charset=utf-8><title>雁书 · 登录失败</title>" +
                "<body style=\"font-family:system-ui;padding:40px;text-align:center\">" +
                "<h2>未收到令牌</h2><p>请回到客户端重试，或使用手工粘贴令牌的方式。</p></body>"
        }
        val bytes = html.toByteArray()
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
        if (token != null && !got.isCompleted) got.complete(token)
    }
    server.start()
    return try {
        val port = server.address.port
        onAuthorizeUrl(withReturnTo(authorizeUrl, "http://127.0.0.1:$port/"))
        val token = withTimeoutOrNull(timeoutMs) { got.await() }
        if (token != null) {
            OidcLoginResult.Token(token)
        } else {
            OidcLoginResult.Manual("等待浏览器回调超时；请确认服务端 IM_OIDC_RETURN_ALLOWLIST 已放行 http://127.0.0.1")
        }
    } finally {
        server.stop(0)
    }
}

/** 桌面端由 [oidcLogin] 直接拿到令牌，无需启动时消费 */
actual fun oidcTokenFromLaunch(): String? = null
