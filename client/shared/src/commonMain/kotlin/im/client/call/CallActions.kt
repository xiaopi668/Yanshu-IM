package im.client.call

/**
 * 通话动作登记表：让「拿不到 [im.client.CallController] 的地方」也能结束通话。
 *
 * 目前唯一的使用者是 Android 的前台服务通知栏（「挂断」按钮）：Service 里没有
 * Controller 引用，也不该持有 Activity。做法由界面层在登录后把挂断动作登记进来，
 * 系统侧只调 [requestHangUp]。
 *
 * 为什么不直接让 Service 拿 Controller：跨进程/跨生命周期的引用会泄漏 Activity，
 * 而本项目已经踩过「DOM/系统资源不会随 Compose 销毁」的坑（AGENT.md §6.2），
 * 用登记表 + 空值兜底是最小、最容易验证的接法。
 *
 * 线程约定：读写都在主线程（界面层登记、通知按钮回调），因此不加锁。
 */
object CallActions {
    /** 界面层登记的挂断动作；未登录/已退出时为 null，调用方必须容忍 */
    var hangUp: (() -> Unit)? = null

    /** 通知栏展示的对方标识（由界面层在通话状态变化时更新，后缀 8 位 UID） */
    var peerLabel: String = ""

    /** 请求挂断：没有登记动作时安全返回 false */
    fun requestHangUp(): Boolean {
        val action = hangUp ?: return false
        action()
        return true
    }
}
