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

    /** 设置免提；未接通或不支持时忽略 */
    fun setSpeaker(on: Boolean)

    /** 翻转免提（UI 按钮直接用它，省得自己读当前值） */
    fun toggleSpeaker()

    /** 跳到本应用的系统设置页（权限被拒后的唯一出路）；不支持/无宿主时返回 false */
    fun openAppSettings(): Boolean
}
