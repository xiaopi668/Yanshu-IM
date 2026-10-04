package im.app

import kotlinx.browser.window

// 浏览器端：默认地址跟随当前域名（页面手动覆盖仍然可用）
fun applyWebDefaults() {
    // 构建时显式钉了地址（-Pim.api）就不再按页面域名推导：
    // 那说明部署方明确指定了服务端在哪，跟随域名反而可能指错。
    if (ImBuildConfig.API_PINNED) return
    val origin = window.location.origin          // e.g. https://im.xiaopi.ink
    val host = window.location.host              // im.xiaopi.ink
    if (origin.startsWith("http")) {
        DEFAULT_API = origin
        DEFAULT_WS = "wss://$host/ws"
    }
}
