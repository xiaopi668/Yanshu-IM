package im.client.call

/**
 * 通话媒体面。
 *
 * 信令（邀请/接听/挂断）走网关的 WebSocket；真正的语音流由自托管 LiveKit SFU 承载，
 * 各端在这里接入：
 *
 * - Web（wasm）：livekit-client（随包发，不依赖 CDN）
 * - Android（androidMain）：LiveKit Android SDK（connect → 开麦 → 退出时释放）
 * - 桌面：没有官方 Kotlin SDK，[supported] 返回 false，UI 会明确提示「该平台暂不支持通话」
 *
 * 做成接口 + expect/actual 是因为三端媒体栈完全不同（浏览器 WebRTC / webrtc-android /
 * 桌面没有官方 Kotlin SDK），而信令与 UI 应当共用一份。
 *
 * 平台专有的**附加能力**（免提、重连提示、跳系统设置）不在这个接口里，见 [CallMediaExtras]：
 * 接口签名是三端共用的契约，不为单一平台去改它。
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

    // ---------- 视频（可选能力） ----------
    // 默认全部按「不支持」处理：桌面侧车只做音频，Android 视频属下一阶段。
    // 只有真正实现了的端（目前是 Web）才覆盖。

    /** 该端是否支持视频通话 */
    val videoSupported: Boolean get() = false

    /** 开/关摄像头。未接通时调用应安全忽略。 */
    fun setVideoEnabled(enabled: Boolean) {}

    /**
     * 把视频画面的位置告诉平台层（Compose 坐标，单位 px）。
     *
     * 为什么需要它：Compose 画在 canvas 上，而视频必须用平台的 video/SurfaceView 渲染
     * （浏览器端是 DOM <video>）。位置只能由 UI 回传，平台层在自己那边摆放，
     * 否则画面会固定停在某个角落、盖住按钮 —— 人机验证小组件踩过同样的坑。
     */
    fun placeVideoSurfaces(left: Int, top: Int, width: Int, height: Int, localSize: Int) {}
}

expect fun createMediaSession(): MediaSession
