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

/** js() 只能是顶层/属性内的单表达式 —— 包装函数放顶层 */
@JsFun("(siteKey, containerId) => { if (window.turnstile) { window.turnstile.render(document.getElementById(containerId), { sitekey: siteKey, callback: function(t) { window.turnstileToken = t; } }); } }")
private external fun jsRenderTurnstile(siteKey: String, containerId: String)

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
        if (!jsScriptLoaded()) {
            jsLoadScript()
            GlobalScope.launch {
                repeat(20) {
                    if (jsTurnstileReady()) { jsRenderTurnstile(siteKey, containerId); return@repeat }
                    delay(500)
                }
            }
        } else {
            jsRenderTurnstile(siteKey, containerId)
        }
        GlobalScope.launch {
            while (true) {
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
