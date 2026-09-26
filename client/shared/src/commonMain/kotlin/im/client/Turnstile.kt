package im.client

/** 注册 Turnstile token 回调（Web 端有效） */
expect fun registerTurnstileCallback(onToken: (String) -> Unit)

/** 渲染 Turnstile 小组件；返回 false = 平台不支持 */
expect fun renderTurnstileWidget(siteKey: String, containerId: String): Boolean
