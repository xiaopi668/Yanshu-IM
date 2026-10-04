package im.client

/** 注册 Turnstile token 回调（Web 端有效） */
expect fun registerTurnstileCallback(onToken: (String) -> Unit)

/**
 * 注册人机验证失败回调（Web 端有效）：脚本被 CSP/插件拦截、网络到不了
 * challenges.cloudflare.com、Site Key 与域名不匹配、验证超时等。
 * 没有它的话页面只会留下一个永远转圈的框，用户和运维都无从判断原因。
 */
expect fun registerTurnstileErrorCallback(onError: (String) -> Unit)

/**
 * 渲染 Turnstile 小组件；返回 false = 平台不支持。
 *
 * 初始是隐藏的，等 [positionTurnstileWidget] 拿到布局位置后再显示 ——
 * Web 端 Compose 画在 canvas 上、DOM 里没有对应节点，小组件只能靠外部定位，
 * 之前写死 position:fixed;bottom:72px 会变成一个浮层盖住页面其它元素。
 */
expect fun renderTurnstileWidget(siteKey: String, containerId: String): Boolean

/** 按 Compose 布局回传的位置/尺寸摆放小组件（CSS 像素，窗口坐标系），并显示出来 */
expect fun positionTurnstileWidget(containerId: String, left: Int, top: Int, width: Int, height: Int)

/** 隐藏小组件（验证已通过、或当前不在注册界面时） */
expect fun hideTurnstileWidget(containerId: String)
