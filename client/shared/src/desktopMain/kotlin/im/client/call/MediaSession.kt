package im.client.call

/**
 * 桌面端媒体面：通过独立进程（Rust 侧车）接入 LiveKit。
 *
 * 取舍见 `desktop-call/README.md`：
 * - 桌面没有官方 Kotlin SDK；
 * - 相比 JCEF（+120MB Chromium）或 webrtc-java 手搓 LiveKit 信令（数周工作量），
 *   用 Rust 官方 SDK 做侧车是「体积 / 工期 / 维护」三者里最平衡的一条。
 *
 * 侧车缺失（未随包分发 / 未装到 PATH）时 supported=false，
 * UI 会提示暂不支持，不会假装在通话。
 */
private object SidecarMediaSession : MediaSession {

    private var session: SidecarSession? = null
    private var broken: ((String) -> Unit)? = null

    override val supported: Boolean get() = Sidecar.locate() != null

    override fun setOnBroken(callback: (String) -> Unit) {
        broken = callback
    }

    override suspend fun join(url: String, token: String): Result<Unit> {
        if (url.isBlank()) {
            return Result.failure(IllegalStateException("服务端未配置通话地址（livekit_url）"))
        }
        val binary = Sidecar.locate() ?: return Result.failure(
            IllegalStateException("未找到通话组件（yanshu-call-agent）")
        )

        // 上一次通话若没清干净，先收掉，避免两个进程抢麦克风
        session?.let { runCatching { it.close() } }
        session = null

        return runCatching {
            val s = SidecarSession.start(binary)
            if (!s.awaitReady()) {
                runCatching { s.close() }
                error("通话组件启动失败（未收到 ready）")
            }
            s.onBroken = { reason ->
                // 运行期断掉：交给 CallController，界面显示失败而不是「已接通」
                broken?.invoke(reason)
                session?.let { runCatching { it.close() } }
                session = null
            }
            s.sendJoin(url, token)
            if (!s.awaitState("connected", JOIN_TIMEOUT_MS)) {
                runCatching { s.close() }
                error("通话接入超时（${JOIN_TIMEOUT_MS / 1000} 秒内未连上媒体服务器）")
            }
            session = s
        }
    }

    override fun setMuted(muted: Boolean) {
        session?.sendMuted(muted)
    }

    override suspend fun leave() {
        val s = session ?: return
        session = null
        runCatching { s.close() }
    }

    private const val JOIN_TIMEOUT_MS = 15_000L
}

actual fun createMediaSession(): MediaSession = SidecarMediaSession
