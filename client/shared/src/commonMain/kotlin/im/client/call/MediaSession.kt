package im.client.call

/**
 * 通话媒体面。
 *
 * 信令（邀请/接听/挂断）走网关的 WebSocket；真正的语音流由自托管 LiveKit SFU 承载，
 * 各端在这里接入：
 *
 * - Web（wasm）：livekit-client（随包发，不依赖 CDN）
 * - Android / 桌面：暂未实现，[supported] 返回 false，UI 会明确提示「该平台暂不支持通话」
 *
 * 做成接口 + expect/actual 是因为三端媒体栈完全不同（浏览器 WebRTC / webrtc-android /
 * 桌面没有官方 Kotlin SDK），而信令与 UI 应当共用一份。
 */
interface MediaSession {
    /** 该平台是否支持通话媒体 */
    val supported: Boolean

    /**
     * 加入房间并打开麦克风。
     * @param url LiveKit SFU 地址（来自 site-config 的 livekit_url）
     * @param token 服务端签发的 room join token（每人一个，identity 不同）
     * @return 失败时带上可读原因（权限被拒、连不上、token 过期…）
     */
    suspend fun join(url: String, token: String): Result<Unit>

    /** 静音 / 取消静音 */
    fun setMuted(muted: Boolean)

    /** 离开房间并释放麦克风 */
    suspend fun leave()

    /**
     * 注册「媒体层异常」回调（可选）。
     *
     * 为什么需要：join 成功只代表当时连上了。运行中媒体进程崩溃、网络彻底断开时，
     * 界面若还显示「已接通」，用户就对着无声通话发呆 —— 正是本项目反复踩过的
     * 「静默失败」。默认空实现，只有能检测到异常的那一端（桌面侧车）覆盖它。
     */
    fun setOnBroken(callback: (String) -> Unit) {}
}

expect fun createMediaSession(): MediaSession
