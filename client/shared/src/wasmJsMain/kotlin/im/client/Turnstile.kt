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
 * 建容器并按 Cloudflare 要求渲染。
 *
 * Compose Web 把界面画在 canvas 上，DOM 里没有名为 containerId 的节点，必须自己建 div。
 * 建好后先 display:none —— 位置要等 Compose 的 onGloballyPositioned 回传，
 * 在那之前显示出来就会闪一下、或者停在左上角。
 *
 * 重复渲染同一容器会被 Cloudflare 判为 already rendered 抛错，所以记住 widgetId 走 reset；
 * 同时挂上 error/timeout/expired 回调，把失败原因写到 window.turnstileError。
 */
@JsFun("(siteKey, containerId) => { let el = document.getElementById(containerId); if (!el) { el = document.createElement('div'); el.id = containerId; document.body.appendChild(el); } el.style.cssText = 'position:fixed;display:none;z-index:5;'; window.turnstileToken = ''; window.turnstileError = ''; try { if (window.turnstileWidgetId) { window.turnstile.reset(window.turnstileWidgetId); return true; } window.turnstileWidgetId = window.turnstile.render(el, { sitekey: siteKey, callback: function(t) { window.turnstileToken = t; window.turnstileError = ''; }, 'error-callback': function(code) { window.turnstileError = '人机验证失败（错误码 ' + code + '）：常见原因是 Site Key 与当前域名不匹配，或该密钥已被删除'; }, 'timeout-callback': function() { window.turnstileError = '人机验证超时，请重新验证'; }, 'expired-callback': function() { window.turnstileToken = ''; window.turnstileError = '验证已过期，请重新验证'; } }); return true; } catch (e) { window.turnstileError = '渲染人机验证失败：' + e; return false; } }")
private external fun jsRenderTurnstile(siteKey: String, containerId: String): Boolean

/** 位置与尺寸由 Compose 布局给出（窗口坐标系、CSS 像素）：精确落在「人机验证」那一格上，不再是浮层 */
// 兜底：如果算出来的位置跑出视口（坐标换算出错、布局尚未稳定等），
// 就退回到「底部居中」——保证小组件永远可见、且不会盖住登录卡片。
@JsFun("(containerId, left, top, width, height) => { const el = document.getElementById(containerId); if (!el) return false; let L = left, T = top, W = width, H = height; if (L < 0 || T < 0 || L + W > innerWidth + 1 || T + H > innerHeight + 1) { W = Math.min(W, innerWidth - 16); H = Math.min(H, innerHeight - 16); L = Math.max(0, (innerWidth - W) / 2); T = Math.max(0, innerHeight - H - 16); } el.style.cssText = 'position:fixed;left:' + L + 'px;top:' + T + 'px;width:' + W + 'px;height:' + H + 'px;display:flex;align-items:center;justify-content:center;z-index:5;'; return true; }")
private external fun jsPlaceTurnstile(containerId: String, left: Int, top: Int, width: Int, height: Int): Boolean

@JsFun("(containerId) => { const el = document.getElementById(containerId); if (el) el.style.display = 'none'; }")
private external fun jsHideTurnstile(containerId: String)

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
                    // 渲染完成后再摆一次：渲染可能晚于布局，那一次的定位是落空的
                    lastPlacement?.let { p ->
                        jsPlaceTurnstile(containerId, p[0], p[1], p[2], p[3])
                    }
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

/**
 * 最后一次布局位置。
 *
 * 为什么要记住它：渲染小组件通常发生在拿到布局之后（要先等 Cloudflare 脚本就绪），
 * 而 onGloballyPositioned 只在布局**变化**时触发。若渲染晚于布局，
 * 那一次 positionTurnstileWidget 会因为容器还不存在而落空 —— 小组件就永远停在默认位置。
 * 所以渲染成功后必须用记住的位置再摆一次。
 */
private var lastPlacement: IntArray? = null

actual fun positionTurnstileWidget(containerId: String, left: Int, top: Int, width: Int, height: Int) {
    lastPlacement = intArrayOf(left, top, width, height)
    try {
        jsPlaceTurnstile(containerId, left, top, width, height)
    } catch (_: Throwable) {
    }
}

actual fun hideTurnstileWidget(containerId: String) {
    try {
        jsHideTurnstile(containerId)
    } catch (_: Throwable) {
    }
}
