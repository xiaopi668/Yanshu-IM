package im.client

actual fun registerTurnstileCallback(onToken: (String) -> Unit) {}
// 桌面/Android 不渲染小组件（返回 false），因此也没有定位与隐藏动作
actual fun registerTurnstileErrorCallback(onError: (String) -> Unit) {}
actual fun renderTurnstileWidget(siteKey: String, containerId: String): Boolean = false
actual fun positionTurnstileWidget(containerId: String, left: Int, top: Int, width: Int, height: Int) {}
actual fun hideTurnstileWidget(containerId: String) {}
