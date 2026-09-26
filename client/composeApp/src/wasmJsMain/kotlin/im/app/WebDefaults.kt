package im.app

import kotlinx.browser.window

// 浏览器端：默认地址跟随当前域名（页面手动覆盖仍然可用）
fun applyWebDefaults() {
    val origin = window.location.origin          // e.g. https://im.xiaopi.ink
    val host = window.location.host              // im.xiaopi.ink
    if (origin.startsWith("http")) {
        DEFAULT_API = origin
        DEFAULT_WS = "wss://$host/ws"
    }
}
