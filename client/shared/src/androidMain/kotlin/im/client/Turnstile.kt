package im.client

actual fun registerTurnstileCallback(onToken: (String) -> Unit) {}
actual fun renderTurnstileWidget(siteKey: String, containerId: String): Boolean = false
