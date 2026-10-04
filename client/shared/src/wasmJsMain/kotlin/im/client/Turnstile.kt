package im.client

import kotlinx.browser.document
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private var tokenCallback: ((String) -> Unit)? = null
private var errorCallback: ((String) -> Unit)? = null

actual fun registerTurnstileCallback(onToken: (String) -> Unit) {
    tokenCallback = onToken
}

actual fun registerTurnstileErrorCallback(onError: (String) -> Unit) {
    errorCallback = onError
}

/**
 * 创建（或复用）容器后渲染小组件。
 * Compose Web 的界面画在 canvas 上，DOM 里并不存在名为 containerId 的元素，
 * 所以必须自己建 div —— 直接 getElementById 会拿到 null，render(null, …) 抛异常。
 *
 * 另两点：
 * - 重复渲染同一容器会被 Cloudflare 判为 already rendered 而抛错，因此记住 widgetId 走 reset；
 * - 装上 error/timeout/expired 回调，把失败原因写到 window.turnstileError，
 *   否则失败时页面上只剩一个永远转圈的框，看不出是密钥错、域名不匹配还是网络不通。
 */
@JsFun("(siteKey, containerId) => { let el = document.getElementById(containerId); if (!el) { el = document.createElement('div'); el.id = containerId; document.body.appendChild(el); } el.style.cssText = 'position:fixed;left:50%;transform:translateX(-50%);bottom:72px;z-index:9999;'; window.turnstileToken = ''; window.turnstileError = ''; try { if (window.turnstileWidgetId) { window.turnstile.reset(window.turnstileWidgetId); return true; } window.turnstileWidgetId = window.turnstile.render(el, { sitekey: siteKey, callback: function(t) { window.turnstileToken = t; window.turnstileError = ''; }, 'error-callback': function(code) { window.turnstileError = '人机验证失败（错误码 ' + code + '）：常见原因是 Site Key 与当前域名不匹配，或该密钥已被删除'; }, 'timeout-callback': function() { window.turnstileError = '人机验证超时，请重新验证'; }, 'expired-callback': function() { window.turnstileToken = ''; window.turnstileError = '验证已过期，请重新验证'; } }); return true; } catch (e) { window.turnstileError = '渲染人机验证失败：' + e; return false; } }")
private external fun jsRenderTurnstile(siteKey: String, containerId: String): Boolean

@JsFun("() => (typeof window.turnstileToken === 'string') ? window.turnstileToken : ''")
private external fun jsReadToken(): String

@JsFun("() => (typeof window.turnstileError === 'string') ? window.turnstileError : ''")
private external fun jsReadError(): String

@JsFun("() => typeof window.turnstile !== 'undefined'")
private external fun jsTurnstileReady(): Boolean

@JsFun("() => !!document.getElementById('cf-turnstile-api')")
private external fun jsScriptLoaded(): Boolean

@JsFun("() => { const s = document.createElement('script'); s.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js'; s.id = 'cf-turnstile-api'; s.async = true; document.body.appendChild(s); }")
private external fun jsLoadScript()

actual fun renderTurnstileWidget(siteKey: String, containerId: String): Boolean {
    return try {
        if (!jsScriptLoaded()) jsLoadScript()
        GlobalScope.launch {
            var rendered = false
            for (attempt in 0 until 40) { // 最多等 20s 脚本就绪
                if (jsTurnstileReady()) {
                    rendered = jsRenderTurnstile(siteKey, containerId)
                    break
                }
                delay(500)
            }
            if (!rendered) {
                // 明确说出原因。国内网络访问 challenges.cloudflare.com 经常不通，
                // 也可能被 CSP / 广告拦截插件挡住 —— 不报出来就只能一直转圈。
                errorCallback?.invoke(
                    "人机验证脚本加载失败（20 秒内未就绪）：请检查网络能否访问 challenges.cloudflare.com，" +
                        "或被 CSP / 广告拦截插件挡住；也可在管理后台临时关闭「人机验证」后再注册"
                )
                return@launch
            }
            for (attempt in 0 until 240) { // 验证结果最多等 2 分钟
                val t = jsReadToken()
                if (t.isNotEmpty()) {
                    tokenCallback?.invoke(t)
                    return@launch
                }
                val e = jsReadError()
                if (e.isNotEmpty()) {
                    errorCallback?.invoke(e)
                    return@launch
                }
                delay(500)
            }
            errorCallback?.invoke("人机验证 2 分钟内未完成，请刷新页面重试")
        }
        true
    } catch (_: Throwable) {
        false
    }
}
