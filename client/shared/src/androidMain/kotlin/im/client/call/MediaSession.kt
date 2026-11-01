package im.client.call

/**
 * 桌面 / Android 的媒体面：暂未实现。
 *
 * 现状说明（不糊弄）：
 * - Android 有官方 SDK（io.livekit:livekit-android），接起来是一条清晰的路：
 *   Room.connect() + RECORD_AUDIO 运行时权限 + AudioManager 切换听筒/扬声器 + 前台服务。
 * - 桌面（JVM）没有官方 Kotlin SDK，要么用 webrtc-java 手搓 LiveKit 信令，要么内嵌 WebView。
 *
 * 在接入之前，这里返回 supported=false，UI 会明确提示「该平台暂不支持通话」，
 * 而不是让人对着一个没有声音的"通话中"界面发呆。
 */
private object UnsupportedMediaSession : MediaSession {
    override val supported: Boolean = false

    override suspend fun join(url: String, token: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("该平台暂不支持通话"))

    override fun setMuted(muted: Boolean) = Unit

    override suspend fun leave() = Unit
}

actual fun createMediaSession(): MediaSession = UnsupportedMediaSession
