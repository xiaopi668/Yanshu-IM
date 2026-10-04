package im.client

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private var tokenCallback: ((String) -> Unit)? = null

actual fun registerTurnstileCallback(onToken: (String) -> Unit) {
    tokenCallback = onToken
}

/**
 * 创建（或复用）容器后渲染小组件。
 * Compose Web 的界面画在 canvas 上，DOM 里并不存在名为 containerId 的元素，
 * 所以这里必须自己建 div —— 之前直接 document.getElementById(containerId) 拿到 null，
 * turnstile.render(null, …) 抛异常，表现就是「人机验证一直加载不出来」。
 */
@JsFun("(siteKey, containerId) => { let el = document.getElementById(containerId); if (!el) { el = document.createElement('div'); el.id = containerId; document.body.appendChild(el); } el.style.cssText = 'position:fixed;left:50%;transform:translateX(-50%);bottom:72px;z-index:9999;'; try { window.turnstile.render(el, { sitekey: siteKey, callback: function(t) { window.turnstileToken = t; } }); return true; } catch (e) { return false; } }")
private external fun jsRenderTurnstile(siteKey: String, containerId: String): Boolean

@JsFun("() => (typeof window.turnstileToken === 'string') ? window.turnstileToken : ''")
private external fun jsReadToken(): String

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
            // 等 api.js 就绪再渲染。
            // 注意：repeat 里用 return@repeat 只是跳过本次 delay，不会跳出循环，
            // 旧写法在脚本就绪后会把小组件重复 render 20 次。
            for (attempt in 0 until 40) { // 最多等 20s
                if (jsTurnstileReady()) {
                    jsRenderTurnstile(siteKey, containerId)
                    break
                }
                delay(500)
            }
            // 拿 token 最多等 2 分钟（脚本被网络/CSP 拦截时 UI 侧会据此提示）
            for (attempt in 0 until 240) {
                val t = jsReadToken()
                if (t.isNotEmpty()) {
                    tokenCallback?.invoke(t)
                    break
                }
                delay(500)
            }
        }
        true
    } catch (_: Throwable) {
        false
    }
}
