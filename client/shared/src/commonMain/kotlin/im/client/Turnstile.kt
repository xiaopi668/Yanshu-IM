package im.client

/** 注册 Turnstile token 回调（Web 端有效） */
expect fun registerTurnstileCallback(onToken: (String) -> Unit)

/**
 * 注册人机验证失败回调（Web 端有效）：脚本被 CSP/插件拦截、网络到不了
 * challenges.cloudflare.com、Site Key 与域名不匹配、验证超时等。
 * 没有它的话页面只会留下一个永远转圈的框，用户和运维都无从判断原因。
 */
expect fun registerTurnstileErrorCallback(onError: (String) -> Unit)

/** 渲染 Turnstile 小组件；返回 false = 平台不支持 */
expect fun renderTurnstileWidget(siteKey: String, containerId: String): Boolean
