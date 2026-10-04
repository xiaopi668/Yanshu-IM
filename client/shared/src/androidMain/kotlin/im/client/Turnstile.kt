package im.client

actual fun registerTurnstileCallback(onToken: (String) -> Unit) {}
// 桌面/Android 不渲染小组件（返回 false），因此也不会有加载失败回调
actual fun registerTurnstileErrorCallback(onError: (String) -> Unit) {}
actual fun renderTurnstileWidget(siteKey: String, containerId: String): Boolean = false
