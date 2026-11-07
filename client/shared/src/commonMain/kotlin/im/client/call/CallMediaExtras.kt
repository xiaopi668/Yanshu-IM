package im.client.call

import kotlinx.coroutines.flow.StateFlow

/**
 * 通话媒体的「附加能力」：免提、媒体重连、权限落地。
 *
 * 为什么不塞进 [MediaSession]：那个接口是三端共用的契约（见 AGENT.md §3.1），
 * 签名不能动；而免提/重连/跳系统设置只有 Android 有真实实现。
 * Web 端由浏览器自己管路由，桌面根本没有媒体面。
 *
 * 约定（各端 actual 必须满足）：
 * - 未接通时调用 [setSpeaker] / [toggleSpeaker] 必须安全（空实现即可，不能崩）；
 * - [speakerSupported] 为 false 时 UI 不显示免提按钮；
 * - [reconnecting] 只反映**已接通后**的重连，入会阶段的连接失败走 `CallMediaState.Failed`；
 * - [openAppSettings] 在拿不到宿主 Context 时返回 false（UI 据此不渲染「去设置」）。
 */
expect object CallMediaExtras {
    /** 免提是否开启（听筒 ↔ 扬声器）。不支持路由控制的平台恒为 false */
    val speakerOn: StateFlow<Boolean>

    /** 该平台是否支持切换免提（决定 UI 是否显示按钮） */
    val speakerSupported: Boolean

    /** 通话中网络切换导致的媒体重连（LiveKit 自动重连）；恒 false 表示平台不提供 */
    val reconnecting: StateFlow<Boolean>

    /** 麦克风权限是否已被「拒绝且不再询问」——UI 据此提示「去设置里开启」 */
    val micPermissionBlocked: StateFlow<Boolean>

    /** 摄像头权限是否已被拒（不明说「不再询问」——两种都提示去设置即可） */
    val videoBlocked: StateFlow<Boolean>

    /** 记录摄像头权限被拒的状态 */
    fun setVideoBlocked(blocked: Boolean)

    // ---------- 摄像头翻转 / 本地镜像（docs/AGENT-camera-switch-mirror.md §4.3） ----------
    // 这三个成员的语义是「界面要显示什么」，真实动作在 MediaSession（UI 不直接碰 SDK）。

    /** 该平台是否支持前后摄像头切换（决定是否显示「翻转」按钮）；桌面恒 false */
    val cameraSwitchSupported: Boolean

    /** 本地预览是否镜像（决定「镜像」按钮文案与是否已应用）；前摄默认 true、后摄不镜像 */
    val mirrorOn: StateFlow<Boolean>

    /**
     * 「翻转」此刻不可用的原因（空串 = 可用）。
     *
     * 为什么需要它：只有一颗摄像头的设备上，按钮要么先置灰、要么点一次才知道 ——
     * 两种都不能静默无效。平台侧在「摄像头打开 / 切换失败」时写入人话，
     * UI 据此置灰按钮并显示原因。
     */
    val cameraSwitchHint: StateFlow<String>

    /** 设置免提；未接通或不支持时忽略 */
    fun setSpeaker(on: Boolean)

    /** 翻转免提（UI 按钮直接用它，省得自己读当前值） */
    fun toggleSpeaker()

    /** 跳到本应用的系统设置页（权限被拒后的唯一出路）；不支持/无宿主时返回 false */
    fun openAppSettings(): Boolean
}
